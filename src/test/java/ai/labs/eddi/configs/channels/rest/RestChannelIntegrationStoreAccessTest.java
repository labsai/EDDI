/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A channel target is a standing invitation, and is gated accordingly.
 *
 * <h3>What this is defending</h3> Once a channel is configured, every inbound
 * message reaches its target as a <em>system-initiated</em> conversation, which
 * is deliberately below the USE gate. So the check has to happen when the
 * channel is written: otherwise an editor can aim a channel they control at a
 * colleague's private agent and relay its replies into a room of their
 * choosing, having never held access to it. Triggers, schedules and group
 * membership are the same shape and are checked the same way.
 *
 * <p>
 * The refusal must also land <em>before</em> the write, not after — a channel
 * that was rejected but stored is a channel that still routes.
 */
class RestChannelIntegrationStoreAccessTest {

    private static final String AGENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String GROUP_ID = "bbbbbbbbbbbbbbbbbbbbbbbb";

    private IChannelIntegrationStore channelStore;
    private ResourceAccessGuard accessGuard;
    private RestChannelIntegrationStore store;

    @BeforeEach
    void setUp() {
        channelStore = mock(IChannelIntegrationStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        store = new RestChannelIntegrationStore(channelStore, mock(IDocumentDescriptorStore.class), accessGuard);
    }

    private static ChannelIntegrationConfiguration config(ChannelTarget.TargetType type, String targetId) {
        var target = new ChannelTarget();
        target.setName("support");
        target.setTargetId(targetId);
        target.setType(type);
        target.setTriggers(List.of("support"));

        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName("My Slack Hub");
        cfg.setChannelType("slack");
        cfg.setDefaultTargetName("support");
        cfg.setTargets(List.of(target));
        return cfg;
    }

    @Test
    @DisplayName("creating a channel aimed at an agent the author cannot use is refused, and nothing is stored")
    void refusesCreateForUngrantedAgent() throws Exception {
        doThrow(new ForbiddenException("nope")).when(accessGuard).requireAgentUseAccess(AGENT_ID);

        assertThrows(ForbiddenException.class,
                () -> store.createChannel(config(ChannelTarget.TargetType.AGENT, AGENT_ID)));

        verify(channelStore, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("updating a channel to aim at an agent the author cannot use is refused")
    void refusesUpdateForUngrantedAgent() throws Exception {
        doThrow(new ForbiddenException("nope")).when(accessGuard).requireAgentUseAccess(AGENT_ID);

        assertThrows(ForbiddenException.class,
                () -> store.updateChannel("cccccccccccccccccccccccc", 1,
                        config(ChannelTarget.TargetType.AGENT, AGENT_ID)));

        verify(channelStore, never()).update(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a GROUP target is checked as a group, not silently skipped")
    void checksGroupTargets() {
        // A group discussion is reachable by exactly the same route and is a guarded
        // descriptor too. Checking only AGENT targets would leave the whole GROUP
        // branch as an open door.
        doThrow(new ForbiddenException("nope")).when(accessGuard).requireUseAccess(eq(GROUP_ID), anyString());

        assertThrows(ForbiddenException.class,
                () -> store.createChannel(config(ChannelTarget.TargetType.GROUP, GROUP_ID)));

        verify(accessGuard).requireUseAccess(eq(GROUP_ID), anyString());
        verify(accessGuard, never()).requireAgentUseAccess(GROUP_ID);
    }

    @Test
    @DisplayName("a permitted target reaches the store")
    void allowsPermittedTarget() throws Exception {
        when(channelStore.create(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new IResourceStore.IResourceId() {
                    @Override
                    public String getId() {
                        return "cccccccccccccccccccccccc";
                    }

                    @Override
                    public Integer getVersion() {
                        return 1;
                    }
                });

        store.createChannel(config(ChannelTarget.TargetType.AGENT, AGENT_ID));

        verify(accessGuard).requireAgentUseAccess(AGENT_ID);
        verify(channelStore).create(org.mockito.ArgumentMatchers.any());
    }
}
