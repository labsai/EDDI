/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.api.IDeploymentStatusReader;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelTargetRouter} with a third, connection-backed channel type
 * registered.
 */
@SuppressWarnings("unchecked")
class ChannelTargetRouterChannelTypeTest {

    private IChannelIntegrationStore channelStore;
    private IDocumentDescriptorStore descriptorStore;
    private SecretResolver secretResolver;
    private ChannelTargetRouter router;
    private final List<DocumentDescriptor> descriptors = new ArrayList<>();
    private int nextId = 0;

    @BeforeEach
    void setUp() throws Exception {
        channelStore = mock(IChannelIntegrationStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString()))
                .thenAnswer(inv -> "resolved-" + inv.getArgument(0, String.class).replace("${vault:", "").replace("}", ""));
        IDeploymentStatusReader agentAdmin = mock(IDeploymentStatusReader.class);
        when(agentAdmin.readAllDeploymentStatuses(any())).thenReturn(List.of());
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, ChannelTarget>getCache(anyString(), any(Duration.class))).thenReturn(mock(ICache.class));
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean())).thenReturn(descriptors);
        router = new ChannelTargetRouter(channelStore, descriptorStore, agentAdmin, mock(IAgentStore.class), secretResolver,
                mock(VaultHostAllowlist.class), cacheFactory, FakeChatChannelType.registry());
    }

    private ChannelIntegrationConfiguration store(String type, String name, Map<String, String> platformConfig) throws Exception {
        String id = String.format("5262b802dc6c4008b5%02x", nextId++);
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/" + id + "?version=1"));
        descriptors.add(descriptor);
        var config = new ChannelIntegrationConfiguration();
        config.setName(name);
        config.setChannelType(type);
        config.setDefaultTargetName("assistant");
        var target = new ChannelTarget();
        target.setName("assistant");
        target.setTargetId("agent-" + name);
        target.setType(ChannelTarget.TargetType.AGENT);
        config.setTargets(List.of(target));
        config.setPlatformConfig(platformConfig);
        when(channelStore.read(id, 1)).thenReturn(config);
        return config;
    }

    private static Map<String, String> pc(String... kv) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("a connection-backed type gets nothing resolved from the vault, even in a key called botToken")
    void noVaultResolution() throws Exception {
        store(FakeChatChannelType.ID, "fake", pc("channelId", "C1", "connection", "bot", "appId", "${vault:app-id}",
                "botToken", "${vault:bot-token}", "signingSecret", "${vault:signing}"));

        var integration = router.getIntegration(FakeChatChannelType.ID, "C1").orElseThrow();
        Map<String, String> platformConfig = integration.getPlatformConfig();
        assertEquals("${vault:app-id}", platformConfig.get("appId"), "an allowed key stays literal");
        assertFalse(platformConfig.containsKey("botToken"), "a key outside the allow-list is dropped");
        assertFalse(platformConfig.containsKey("signingSecret"));
        assertEquals("bot", platformConfig.get("connection"));
        verify(secretResolver, never()).resolveValue(anyString());
    }

    @Test
    @DisplayName("Slack keeps resolving its two credential keys")
    void slackUnchanged() throws Exception {
        store("slack", "slack", pc("channelId", "C2", "botToken", "${vault:bot}", "signingSecret", "${vault:sig}"));
        var resolved = router.resolveTarget("slack", "C2", "hello");
        assertNotNull(resolved);
        assertEquals("resolved-bot", resolved.botToken());
        assertEquals("resolved-sig", resolved.signingSecret());
    }

    @Test
    @DisplayName("a connection-backed type adds no signing secret to the pool")
    void noSigningSecretPooled() throws Exception {
        store(FakeChatChannelType.ID, "fake", pc("channelId", "C1", "connection", "bot", "appId", "a", "signingSecret", "plain"));
        assertTrue(router.getSigningSecrets(FakeChatChannelType.ID).isEmpty());
    }
}
