/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.internal.ConversationService;
import ai.labs.eddi.engine.internal.IContextLogger;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.ConversationMemory;
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
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The origin taint of email plan v3 §5.3 through conversation starts: a mail
 * taints the conversation it starts, every conversation a tainted turn starts
 * inherits the taint whatever its user id, and a tainted conversation's turns
 * run with it bound — so a delegated agent cannot call
 * {@code update_email_senders} either.
 */
@DisplayName("ConversationService — the email origin taint at conversation start")
class ConversationServiceTurnOriginTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "aabbccddee1122334455aa01";
    private static final String CONVERSATION_ID = "aabbccddee1122334455cc01";
    private static final String USER_ID = "alice";

    private ConversationService conversationService;
    private CallerIdentityContext callerIdentityContext;
    private ResolutionPrincipalContext resolutionPrincipalContext;
    private IConversationSetup conversationSetup;
    private IAgent agent;
    private IConversationMemory memory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        IAgentFactory agentFactory = mock(IAgentFactory.class);
        IConversationMemoryStore conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationSetup = mock(IConversationSetup.class);
        GdprComplianceService gdprComplianceService = mock(GdprComplianceService.class);
        TenantQuotaService tenantQuotaService = mock(TenantQuotaService.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        doReturn(mock(ICache.class)).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);

        callerIdentityContext = new CallerIdentityContext(null, null);
        callerIdentityContext.clear();
        resolutionPrincipalContext = new ResolutionPrincipalContext();
        resolutionPrincipalContext.clear();

        conversationService = new ConversationService(agentFactory, conversationMemoryStore, mock(IConversationDescriptorStore.class),
                mock(IUserMemoryStore.class), mock(IConversationCoordinator.class), conversationSetup, cacheFactory, mock(IRuntime.class),
                mock(IContextLogger.class), mock(AuditLedgerService.class), gdprComplianceService, tenantQuotaService, mock(IScheduleStore.class),
                mock(IAgentStore.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                (Event<HitlResumeCompletedEvent>) mock(Event.class), callerIdentityContext, 30);
        Field field = ConversationService.class.getDeclaredField("resolutionPrincipalContext");
        field.setAccessible(true);
        field.set(conversationService, resolutionPrincipalContext);

        agent = mock(IAgent.class);
        memory = mock(IConversationMemory.class);
        IConversation conversation = mock(IConversation.class);
        doAnswer(invocation -> invocation.getArgument(0)).when(conversationSetup).computeAnonymousUserIdIfEmpty(anyString(), any());
        doReturn(agent).when(agentFactory).getLatestReadyAgent(ENV, AGENT_ID);
        doReturn(new QuotaCheckResult(true, null)).when(tenantQuotaService).acquireConversationSlot();
        doReturn(conversation).when(agent).startConversation(anyString(), anyMap(), any(), isNull());
        doReturn(memory).when(conversation).getConversationMemory();
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

    @Test
    @DisplayName("a start whose context carries turnOrigin=email taints the conversation and its start turn")
    void emailStartIsTainted() throws Exception {
        var boundDuringStart = new AtomicReference<String>();
        doAnswer(invocation -> {
            boundDuringStart.set(TurnOrigin.current());
            IConversation conversation = mock(IConversation.class);
            doReturn(memory).when(conversation).getConversationMemory();
            return conversation;
        }).when(agent).startConversation(anyString(), anyMap(), any(), isNull());

        conversationService.startConversation(ENV, AGENT_ID, USER_ID,
                Map.of(TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, TurnOrigin.EMAIL)));

        verify(memory).setOrigin(TurnOrigin.EMAIL);
        assertEquals(TurnOrigin.EMAIL, boundDuringStart.get(), "a start-turn tool call must already run tainted");
    }

    @Test
    @DisplayName("an ordinary start is not tainted")
    void ordinaryStartIsNotTainted() throws Exception {
        conversationService.startConversation(ENV, AGENT_ID, USER_ID, null);

        verify(memory).setOrigin(isNull());
    }

    @Test
    @DisplayName("a delegate started from a tainted turn is tainted — under another user id too — and cannot call update_email_senders")
    void delegateOfTaintedTurnCannotEditSenders() throws Exception {
        // The parent: a mail-started turn of an email identity, as the poller runs it.
        resolutionPrincipalContext.bind(new ResolutionPrincipal("email:aabbccddee1122334455aabb:alice@partner.com", Provenance.SELF_ASSERTED,
                TurnOrigin.EMAIL));
        ToolExecutionService tools = toolExecutionService();
        var executed = new AtomicBoolean();
        var resultInChild = new AtomicReference<String>();
        // The delegate's CONVERSATION_START turn tries the tool.
        doAnswer(invocation -> {
            resultInChild.set(tools.executeToolWrapped("update_email_senders", "{}", null, CONVERSATION_ID, () -> {
                executed.set(true);
                return "updated";
            }, false, false, false, 60));
            IConversation conversation = mock(IConversation.class);
            doReturn(memory).when(conversation).getConversationMemory();
            return conversation;
        }).when(agent).startConversation(anyString(), anyMap(), any(), isNull());

        // Exactly the start converseWithAgent makes, under a different user id.
        conversationService.startConversation(ENV, AGENT_ID, USER_ID,
                Map.of(ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH, new Context(Context.ContextType.string, "1")));

        verify(memory).setOrigin(TurnOrigin.EMAIL);
        assertFalse(executed.get(), "the guarded tool must never run in the delegate");
        assertTrue(resultInChild.get().contains("started by an email"), resultInChild.get());
    }

    @Test
    @DisplayName("a later turn of a tainted conversation runs with the taint bound, from its stored memory alone")
    void laterTurnOfTaintedConversationIsRefused() throws Exception {
        // The child's memory as startConversation left it; nothing is bound on this
        // thread, as on the pool thread a later turn (or a HITL resume) runs on.
        var child = new ConversationMemory(AGENT_ID, 1, USER_ID);
        child.setOrigin(TurnOrigin.EMAIL);
        ToolExecutionService tools = toolExecutionService();
        var executed = new AtomicBoolean();
        var result = new AtomicReference<String>();
        Callable<Void> turn = () -> {
            result.set(tools.executeToolWrapped("update_email_senders", "{}", null, CONVERSATION_ID, () -> {
                executed.set(true);
                return "updated";
            }, false, false, false, 60));
            return null;
        };

        withResolutionPrincipal(child, turn).call();

        assertFalse(executed.get());
        assertTrue(result.get().contains("started by an email"));
        assertNull(resolutionPrincipalContext.current(), "the binding ends with the turn");
    }

    @Test
    @DisplayName("an untainted conversation's turn may call the tool")
    void untaintedTurnRuns() throws Exception {
        var plain = new ConversationMemory(AGENT_ID, 1, USER_ID);
        ToolExecutionService tools = toolExecutionService();
        var result = new AtomicReference<String>();

        withResolutionPrincipal(plain, () -> {
            result.set(tools.executeToolWrapped("update_email_senders", "{}", null, CONVERSATION_ID, () -> "updated", false, false, false, 60));
            return null;
        }).call();

        assertEquals("updated", result.get());
    }

    @SuppressWarnings("unchecked")
    private Callable<Void> withResolutionPrincipal(IConversationMemory conversationMemory, Callable<Void> work) throws Exception {
        Method method = ConversationService.class.getDeclaredMethod("withResolutionPrincipal", IConversationMemory.class, Callable.class);
        method.setAccessible(true);
        return (Callable<Void>) method.invoke(conversationService, conversationMemory, work);
    }

    static ToolExecutionService toolExecutionService() throws Exception {
        var meterRegistry = new SimpleMeterRegistry();
        ToolCacheService cache = new ToolCacheService();
        set(cache, "cacheFactory", new CacheFactory());
        set(cache, "meterRegistry", meterRegistry);
        cache.init();
        ToolRateLimiter rateLimiter = new ToolRateLimiter();
        set(rateLimiter, "meterRegistry", meterRegistry);
        rateLimiter.init();
        ToolCostTracker costTracker = new ToolCostTracker();
        set(costTracker, "meterRegistry", meterRegistry);
        costTracker.init();
        ToolExecutionService service = new ToolExecutionService();
        set(service, "cacheService", cache);
        set(service, "rateLimiter", rateLimiter);
        set(service, "costTracker", costTracker);
        set(service, "meterRegistry", meterRegistry);
        service.init();
        return service;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
