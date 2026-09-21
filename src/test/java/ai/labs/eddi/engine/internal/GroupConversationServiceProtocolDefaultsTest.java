/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The protocol defaults a group falls back to when it configures no
 * {@code protocol} block — which is the common shape, since nothing backfills
 * one at save time.
 * <p>
 * The engine documented (and {@code docs/group-conversations.md} published) a
 * 180s default introduced specifically because 60s timed out thinking models
 * during synthesis. The fallback nonetheless handed out a literal 60, so the
 * documented default was reachable only for the odd config that supplied a
 * protocol with a non-positive timeout.
 *
 * @author ginccc
 */
@DisplayName("GroupConversationService — protocol defaults")
class GroupConversationServiceProtocolDefaultsTest {

    private static final String GROUP_ID = "group-1";

    @Mock
    private IAgentGroupStore groupStore;
    @Mock
    private IGroupConversationStore conversationStore;
    @Mock
    private IConversationService conversationService;
    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private AgentSigningService agentSigningService;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private NonceCacheService nonceCacheService;

    private GroupConversationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                mock(IScheduleStore.class), nonceCacheService, null,
                new CallerIdentityContext(null, null), "default", 3);
    }

    private GroupConversation conversation() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId(GROUP_ID);
        return gc;
    }

    private void storedConfigWith(ProtocolConfig protocol) throws Exception {
        var config = new AgentGroupConfiguration();
        config.setProtocol(protocol);
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resourceId);
        when(groupStore.read(GROUP_ID, 1)).thenReturn(config);
    }

    @Test
    @DisplayName("a group with no protocol block gets the documented 180s default, not 60s")
    void noProtocolBlockUsesTheDocumentedDefault() throws Exception {
        storedConfigWith(null);

        assertEquals(ProtocolConfig.DEFAULT_AGENT_TIMEOUT_SECONDS, service.resolveAgentTimeoutSeconds(conversation()));
        assertEquals(180, ProtocolConfig.DEFAULT_AGENT_TIMEOUT_SECONDS,
                "the published default in docs/group-conversations.md");
    }

    @Test
    @DisplayName("a configured timeout still wins over the default")
    void configuredTimeoutWins() throws Exception {
        storedConfigWith(new ProtocolConfig(45, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.SKIP));

        assertEquals(45, service.resolveAgentTimeoutSeconds(conversation()));
    }

    @Test
    @DisplayName("a non-positive configured timeout falls back to the default")
    void nonPositiveTimeoutFallsBack() throws Exception {
        storedConfigWith(new ProtocolConfig(0, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.SKIP));

        assertEquals(ProtocolConfig.DEFAULT_AGENT_TIMEOUT_SECONDS, service.resolveAgentTimeoutSeconds(conversation()));
    }

    @Test
    @DisplayName("an unreadable group config falls back to the default, not to a tighter 60s")
    void unreadableConfigFallsBackToTheDefault() throws Exception {
        // getCurrentResourceId declares no checked exception, so the store failure
        // this guards against surfaces unchecked.
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenThrow(new IllegalStateException("store unavailable"));

        assertEquals(ProtocolConfig.DEFAULT_AGENT_TIMEOUT_SECONDS, service.resolveAgentTimeoutSeconds(conversation()),
                "a follow-up is an ordinary member turn — it has no reason to get a tighter budget than one inside the discussion");
    }

    @Test
    @DisplayName("a missing group falls back to the default")
    void missingGroupFallsBackToTheDefault() throws Exception {
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(null);

        assertEquals(ProtocolConfig.DEFAULT_AGENT_TIMEOUT_SECONDS, service.resolveAgentTimeoutSeconds(conversation()));
    }
}
