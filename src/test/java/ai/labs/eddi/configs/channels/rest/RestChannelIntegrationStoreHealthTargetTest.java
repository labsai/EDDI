/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("Channel integrations — the health-outcomes webhook target (H5)")
class RestChannelIntegrationStoreHealthTargetTest {

    private IChannelIntegrationStore channelStore;
    private ResourceAccessGuard accessGuard;
    private RestChannelIntegrationStore store;

    @BeforeEach
    void setUp() {
        channelStore = mock(IChannelIntegrationStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        store = new RestChannelIntegrationStore(channelStore, mock(IDocumentDescriptorStore.class), accessGuard);
    }

    static ChannelTarget healthTarget(List<String> agentIds) {
        ChannelTarget target = new ChannelTarget();
        target.setName("crm-outcomes");
        target.setKind(ChannelTarget.KIND_HEALTH_OUTCOMES);
        target.setAgentIds(agentIds);
        target.setTriggers(List.of("order.*"));
        return target;
    }

    static ChannelIntegrationConfiguration webhook(ChannelTarget target) {
        ChannelIntegrationConfiguration cfg = new ChannelIntegrationConfiguration();
        cfg.setName("crm");
        cfg.setChannelType("webhook");
        cfg.setPlatformConfig(Map.of("signingSecret", "${vault:crm-hook}"));
        cfg.setDefaultTargetName(target.getName());
        cfg.setTargets(List.of(target));
        return cfg;
    }

    @Test
    @DisplayName("a webhook target of kind health-outcomes with agentIds and no targetId is valid")
    void valid() {
        assertDoesNotThrow(() -> store.validateConfiguration(webhook(healthTarget(List.of("agent-1", "agent-2")))));
    }

    @Test
    @DisplayName("refused: unknown kind, no agentIds, too many, a blank one, a targetId, GROUP type, observeMode, a non-webhook channel")
    void refused() {
        ChannelTarget unknown = healthTarget(List.of("a"));
        unknown.setKind("metrics");
        assertTrue(
                assertThrows(BadRequestException.class, () -> store.validateConfiguration(webhook(unknown))).getMessage().contains("unknown kind"));
        assertThrows(BadRequestException.class, () -> store.validateConfiguration(webhook(healthTarget(null))));
        assertThrows(BadRequestException.class, () -> store.validateConfiguration(webhook(healthTarget(List.of()))));
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= RestChannelIntegrationStore.MAX_HEALTH_AGENTS; i++) {
            many.add("agent-" + i);
        }
        assertThrows(BadRequestException.class, () -> store.validateConfiguration(webhook(healthTarget(many))));
        assertThrows(BadRequestException.class, () -> store.validateConfiguration(webhook(healthTarget(List.of(" ")))));
        ChannelTarget withTargetId = healthTarget(List.of("a"));
        withTargetId.setTargetId("agent-x");
        assertThrows(BadRequestException.class, () -> store.validateConfiguration(webhook(withTargetId)));
        ChannelTarget group = healthTarget(List.of("a"));
        group.setType(ChannelTarget.TargetType.GROUP);
        assertThrows(BadRequestException.class, () -> store.validateConfiguration(webhook(group)));
        ChannelTarget slack = healthTarget(List.of("a"));
        ChannelIntegrationConfiguration slackChannel = webhook(slack);
        slackChannel.setChannelType("slack");
        slackChannel.setPlatformConfig(Map.of());
        assertTrue(assertThrows(BadRequestException.class, () -> store.validateConfiguration(slackChannel)).getMessage().contains("webhook"));
    }

    @Test
    @DisplayName("the author needs EDIT on every bound agent — a refusal stores nothing")
    void editOnEveryAgent() throws Exception {
        doThrow(new ForbiddenException("nope")).when(accessGuard).requireAccess("agent-2", AccessLevel.EDIT, "agent");
        assertThrows(ForbiddenException.class, () -> store.createChannel(webhook(healthTarget(List.of("agent-1", "agent-2")))));
        verify(accessGuard).requireAccess("agent-1", AccessLevel.EDIT, "agent");
        verify(channelStore, never()).create(any());
        verify(accessGuard, never()).requireAgentUseAccess(any());
    }
}
