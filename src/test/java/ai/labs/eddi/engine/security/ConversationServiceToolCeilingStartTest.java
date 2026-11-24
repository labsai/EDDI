/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.internal.ConversationService;
import ai.labs.eddi.engine.internal.IContextLogger;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The tool ceiling of an email sender role (email plan v3 §9) through
 * {@code startConversation}: bound for the {@code CONVERSATION_START} turn,
 * stored on the memory, and inherited by a delegate the turn starts.
 */
@DisplayName("ConversationService — the sender-role tool ceiling on start and delegation")
class ConversationServiceToolCeilingStartTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "aabbccddee1122334455aa01";
    private static final String USER_ID = "email:aabbccddee1122334455aabb:ann@customer-a.com";
    private static final String CONVERSATION_ID = "aabbccddee1122334455cc01";

    private ConversationService conversationService;
    private CallerIdentityContext callerIdentityContext;
    private ResolutionPrincipalContext resolutionPrincipalContext;
    private IAgent agent;
    private IConversationMemory memory;
    private final AtomicReference<Set<String>> boundDuringStart = new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        IAgentFactory agentFactory = mock(IAgentFactory.class);
        IConversationMemoryStore conversationMemoryStore = mock(IConversationMemoryStore.class);
        IConversationSetup conversationSetup = mock(IConversationSetup.class);
        TenantQuotaService tenantQuotaService = mock(TenantQuotaService.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        doReturn(mock(ICache.class)).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);

        callerIdentityContext = new CallerIdentityContext(null, null);
        callerIdentityContext.clear();
        resolutionPrincipalContext = new ResolutionPrincipalContext();
        resolutionPrincipalContext.clear();

        conversationService = new ConversationService(agentFactory, conversationMemoryStore, mock(IConversationDescriptorStore.class),
                mock(IUserMemoryStore.class), mock(IConversationCoordinator.class), conversationSetup, cacheFactory, mock(IRuntime.class),
                mock(IContextLogger.class), mock(AuditLedgerService.class), mock(GdprComplianceService.class), tenantQuotaService,
                mock(IScheduleStore.class), mock(IAgentStore.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                (Event<HitlResumeCompletedEvent>) mock(Event.class), callerIdentityContext, 30);
        Field field = ConversationService.class.getDeclaredField("resolutionPrincipalContext");
        field.setAccessible(true);
        field.set(conversationService, resolutionPrincipalContext);

        agent = mock(IAgent.class);
        memory = mock(IConversationMemory.class);
        doAnswer(invocation -> invocation.getArgument(0)).when(conversationSetup).computeAnonymousUserIdIfEmpty(anyString(), any());
        doReturn(agent).when(agentFactory).getLatestReadyAgent(ENV, AGENT_ID);
        doReturn(new QuotaCheckResult(true, null)).when(tenantQuotaService).acquireConversationSlot();
        doAnswer(invocation -> {
            boundDuringStart.set(TurnToolCeiling.current());
            IConversation conversation = mock(IConversation.class);
            doReturn(memory).when(conversation).getConversationMemory();
            return conversation;
        }).when(agent).startConversation(anyString(), anyMap(), any(), isNull());
        doReturn(ConversationState.READY).when(memory).getConversationState();
        doReturn(new Stack<>()).when(memory).getRedoCache();
        doReturn(mock(IConversationStepStack.class)).when(memory).getAllSteps();
        doReturn(new ConversationProperties(memory)).when(memory).getConversationProperties();
        doReturn(CONVERSATION_ID).when(conversationMemoryStore).storeConversationMemorySnapshot(any());
    }

    @AfterEach
    void tearDown() {
        callerIdentityContext.clear();
        resolutionPrincipalContext.clear();
    }

    private static Map<String, Context> ceiling(String... tools) {
        return Map.of(TurnToolCeiling.CONTEXT_KEY, new Context(Context.ContextType.array, List.of(tools)));
    }

    @Test
    @DisplayName("a start whose context carries a ceiling runs its start turn under it, and stores it")
    void startIsBoundAndStored() throws Exception {
        conversationService.startConversation(ENV, AGENT_ID, USER_ID, ceiling("kb_search"));

        assertEquals(Set.of("kb_search"), boundDuringStart.get(), "a start-turn tool call must already run under the ceiling");
        verify(memory).setToolCeiling(Set.of("kb_search"));
    }

    @Test
    @DisplayName("a delegate started from a turn under a ceiling inherits it, under another user id too")
    void delegateInherits() throws Exception {
        resolutionPrincipalContext.bind(new ResolutionPrincipal(USER_ID, Provenance.SELF_ASSERTED, TurnOrigin.EMAIL, Set.of("kb_search",
                "converseWithAgent")));

        conversationService.startConversation(ENV, AGENT_ID, "someone-else", Map.of());

        assertEquals(Set.of("kb_search", "converseWithAgent"), boundDuringStart.get());
        verify(memory).setToolCeiling(Set.of("kb_search", "converseWithAgent"));
    }

    @Test
    @DisplayName("a delegate's own context can only narrow the inherited ceiling")
    void delegateOnlyNarrows() throws Exception {
        resolutionPrincipalContext.bind(new ResolutionPrincipal(USER_ID, Provenance.SELF_ASSERTED, TurnOrigin.EMAIL, Set.of("kb_search")));

        conversationService.startConversation(ENV, AGENT_ID, "someone-else", ceiling("kb_search", "http_post"));

        assertEquals(Set.of("kb_search"), boundDuringStart.get());
    }

    @Test
    @DisplayName("an ordinary start has no ceiling")
    void ordinaryStart() throws Exception {
        conversationService.startConversation(ENV, AGENT_ID, "alice", null);

        assertNull(boundDuringStart.get());
        verify(memory).setToolCeiling(isNull());
    }
}
