/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.integrations.channels.ChannelConnectionCredentials;
import ai.labs.eddi.integrations.channels.FakeChatChannelType;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The channel store against a registry that holds a third, connection-backed
 * type: discovered types are accepted, their platformConfig is an allow-list,
 * their connection is checked, and their routing keys are unique.
 */
class RestChannelIntegrationStoreChannelTypeTest {

    private static final String OTHER_ID = "aabbccddeeff112233445566";

    private IChannelIntegrationStore channelStore;
    private IDocumentDescriptorStore descriptorStore;
    private ChannelConnectionCredentials credentials;
    private ResourceAccessGuard accessGuard;
    private RestChannelIntegrationStore sut;

    @BeforeEach
    void setUp() throws Exception {
        channelStore = mock(IChannelIntegrationStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        credentials = mock(ChannelConnectionCredentials.class);
        when(credentials.validateForSave(any(), anyMap())).thenReturn(List.of());
        lenient().when(descriptorStore.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(IDescriptorStore.NO_LIMIT), eq(false)))
                .thenReturn(List.of());
        accessGuard = mock(ResourceAccessGuard.class);
        sut = new RestChannelIntegrationStore(channelStore, descriptorStore, accessGuard, null, null, null,
                FakeChatChannelType.registry(), credentials);
    }

    private static ChannelIntegrationConfiguration fakeConfig(Map<String, String> platformConfig) {
        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName("Fake chat");
        cfg.setChannelType(FakeChatChannelType.ID);
        cfg.setDefaultTargetName("assistant");
        var target = new ChannelTarget();
        target.setName("assistant");
        target.setTargetId("agent-abc");
        target.setType(ChannelTarget.TargetType.AGENT);
        cfg.setTargets(List.of(target));
        cfg.setPlatformConfig(platformConfig);
        return cfg;
    }

    private static Map<String, String> pc(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("pc(...) takes key/value pairs, got " + kv.length + " arguments");
        }
        var map = new HashMap<String, String>();
        map.put("connection", "fakechat-bot");
        map.put("appId", "app-1");
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("a discovered type is accepted")
    void discoveredTypeAccepted() {
        assertDoesNotThrow(() -> sut.validateConfiguration(fakeConfig(pc())));
    }

    @Test
    @DisplayName("an unknown type names the registered ones")
    void unknownTypeListsRegistered() {
        var cfg = fakeConfig(pc());
        cfg.setChannelType("teams");
        var ex = assertThrows(BadRequestException.class, () -> sut.validateConfiguration(cfg));
        assertTrue(ex.getMessage().contains("[email, fakechat, slack, webhook]"), ex.getMessage());
    }

    @Test
    @DisplayName("a type with surrounding whitespace is unknown, not silently a different integration")
    void paddedTypeRefused() {
        var cfg = fakeConfig(pc());
        cfg.setChannelType(" slack");
        assertThrows(BadRequestException.class, () -> sut.validateConfiguration(cfg));
    }

    @Test
    @DisplayName("updating a webhook does not scan every integration for routing keys it cannot share")
    void webhookUpdateNoRoutingScan() throws Exception {
        String id = "ccddee00112233445566aabb";
        var webhook = fakeConfig(Map.of("signingSecret", "${vault:hook}"));
        webhook.setChannelType("webhook");
        when(channelStore.read(id, 1)).thenReturn(webhook);
        when(channelStore.update(eq(id), eq(1), any())).thenReturn(2);
        when(channelStore.getCurrentResourceId(id)).thenReturn(resourceId(id, 2));
        when(descriptorStore.readDescriptor(id, 2)).thenReturn(new DocumentDescriptor());

        sut.updateChannel(id, 1, webhook);

        // One scan: the name check. A routing-key scan could never find a match for a
        // type routed by its own resource id, and a store hiccup in it used to 503
        // the update.
        verify(descriptorStore, times(1)).readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(IDescriptorStore.NO_LIMIT), eq(false));
    }

    @Test
    @DisplayName("a plaintext credential under any key is refused, and nothing is stored")
    void plaintextCredentialRefused() throws Exception {
        var ex = assertThrows(BadRequestException.class, () -> sut.createChannel(fakeConfig(pc("appPassword", "hunter2hunter2"))));
        assertTrue(ex.getMessage().contains("platformConfig.appPassword is not a fakechat setting"), ex.getMessage());
        verify(channelStore, never()).create(any());
    }

    @Test
    @DisplayName("the connection check's errors refuse the save")
    void connectionCheckRefuses() {
        when(credentials.validateForSave(any(), anyMap())).thenReturn(List.of("platformConfig.connection: No connection 'fakechat-bot'."));
        var ex = assertThrows(BadRequestException.class, () -> sut.validateConfiguration(fakeConfig(pc())));
        assertEquals("platformConfig.connection: No connection 'fakechat-bot'.", ex.getMessage());
    }

    @Test
    @DisplayName("observe mode stays allowed on a chat type that supports it")
    void observeAllowed() {
        var cfg = fakeConfig(pc());
        var observer = new ChannelTarget();
        observer.setName("watcher");
        observer.setTargetId("agent-def");
        observer.setType(ChannelTarget.TargetType.AGENT);
        observer.setObserveMode(true);
        cfg.setTargets(List.of(cfg.getTargets().get(0), observer));
        assertDoesNotThrow(() -> sut.validateConfiguration(cfg));
    }

    @Test
    @DisplayName("two channel-less integrations of one application are refused: the router could route only one")
    void sameApplicationRefused() throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/" + OTHER_ID + "?version=1"));
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(IDescriptorStore.NO_LIMIT), eq(false)))
                .thenReturn(List.of(descriptor));
        var existing = fakeConfig(pc());
        existing.setName("Other");
        when(channelStore.read(OTHER_ID, 1)).thenReturn(existing);

        var ex = assertThrows(BadRequestException.class, () -> sut.createChannel(fakeConfig(pc())));
        assertEquals("Another channel integration already uses application id 'app-1' for type 'fakechat'.", ex.getMessage());
        verify(channelStore, never()).create(any());
    }

    @Test
    @DisplayName("the same application in two different channels is fine")
    void sameApplicationDifferentChannels() throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/" + OTHER_ID + "?version=1"));
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(IDescriptorStore.NO_LIMIT), eq(false)))
                .thenReturn(List.of(descriptor));
        var existing = fakeConfig(pc("channelId", "C1"));
        existing.setName("Other");
        when(channelStore.read(OTHER_ID, 1)).thenReturn(existing);
        when(channelStore.create(any())).thenReturn(resourceId("ccddee00112233445566aabb", 1));

        assertDoesNotThrow(() -> sut.createChannel(fakeConfig(pc("channelId", "C2"))));
    }

    @Test
    @DisplayName("duplicating a channel-bound integration is refused: the copy would take over the application's DMs")
    void duplicateWouldClaimApplication() throws Exception {
        String sourceId = "ddeeff00112233445566aabb";
        when(channelStore.read(sourceId, 1)).thenReturn(fakeConfig(pc("channelId", "C1")));
        when(channelStore.getCurrentResourceId(sourceId)).thenReturn(resourceId(sourceId, 1));

        var ex = assertThrows(BadRequestException.class, () -> sut.duplicateChannel(sourceId, 1));
        assertTrue(ex.getMessage().contains("direct messages"), ex.getMessage());
        verify(channelStore, never()).create(any());
    }

    @Test
    @DisplayName("duplicating a connection-backed integration needs edit access to the source, not just view access")
    void duplicateNeedsEditAccess() throws Exception {
        String sourceId = "ddeeff00112233445566aabb";
        when(channelStore.read(sourceId, 1)).thenReturn(fakeConfig(pc("channelId", "C1")));
        when(channelStore.getCurrentResourceId(sourceId)).thenReturn(resourceId(sourceId, 1));
        doThrow(new ForbiddenException("view only")).when(accessGuard).requireAccess(eq(sourceId), eq(AccessLevel.EDIT), any());

        assertThrows(ForbiddenException.class, () -> sut.duplicateChannel(sourceId, 1));
        verify(channelStore, never()).create(any());
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }
}
