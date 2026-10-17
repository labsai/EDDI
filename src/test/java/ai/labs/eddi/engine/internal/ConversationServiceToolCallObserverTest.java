/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WS8a — a plain {@code say} carries the response handler's tool-call observer
 * onto the turn's memory, and a handler without one clears it (a memory reused
 * across turns must not keep the previous turn's observer).
 */
class ConversationServiceToolCallObserverTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "observer-agent-id";
    private static final String CONVERSATION_ID = "observer-conversation-id";
    private static final String USER_ID = "observer-user-id";
    private static final int AGENT_TIMEOUT = 30;

    private IAgentFactory agentFactory;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationCoordinator conversationCoordinator;
    private IRuntime runtime;
    private TenantQuotaService tenantQuotaService;
    private SimpleMeterRegistry meterRegistry;

    private ConversationService conversationService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationCoordinator = mock(IConversationCoordinator.class);
        runtime = mock(IRuntime.class);
        tenantQuotaService = mock(TenantQuotaService.class);
        meterRegistry = new SimpleMeterRegistry();

        var conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        var conversationSetup = mock(IConversationSetup.class);
        var cacheFactory = mock(ICacheFactory.class);
        var conversationStateCache = (ICache<String, ConversationState>) mock(ICache.class);
        var contextLogger = mock(IContextLogger.class);
        var auditLedgerService = mock(AuditLedgerService.class);
        var gdprComplianceService = mock(GdprComplianceService.class);
        var scheduleStore = mock(IScheduleStore.class);
        var agentStore = mock(IAgentStore.class);
        var userMemoryStore = mock(IUserMemoryStore.class);
        var jsonSerialization = mock(IJsonSerialization.class);

        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());
        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        when(auditLedgerService.isEnabled()).thenReturn(false);

        conversationService = new ConversationService(agentFactory, conversationMemoryStore,
                conversationDescriptorStore, userMemoryStore, conversationCoordinator,
                conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization, meterRegistry,
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), AGENT_TIMEOUT);
    }

    private ConversationMemorySnapshot snapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setEnvironment(ENV);
        snapshot.setConversationState(ConversationState.READY);

        var stepSnapshot = new ConversationStepSnapshot();
        var workflowRun = new WorkflowRunSnapshot();
        workflowRun.getLifecycleTasks().add(new ResultSnapshot("input:initial", "hello", null, new Date(), null, true));
        stepSnapshot.getWorkflows().add(workflowRun);
        snapshot.getConversationSteps().add(stepSnapshot);
        var output = new ConversationOutput();
        output.put("input", "hello");
        snapshot.getConversationOutputs().add(output);
        return snapshot;
    }

    private IConversationMemory driveSay(ConversationResponseHandler handler) throws Exception {
        IAgent agent = mock(IAgent.class);
        IConversation conversation = mock(IConversation.class);
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot());
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);
        when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);
        when(conversation.isEnded()).thenReturn(false);
        ArgumentCaptor<IConversationMemory> memoryCaptor = ArgumentCaptor.forClass(IConversationMemory.class);
        when(agent.continueConversation(memoryCaptor.capture(), any(), any())).thenReturn(conversation);

        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(), new InputData("hello", Map.of()), false, handler);

        // The turn builder runs on the request thread inside say(), so the memory is
        // final here.
        IConversationMemory memory = memoryCaptor.getValue();
        return memory;
    }

    @Test
    @DisplayName("a handler's observer is installed on the turn's memory")
    void handlerObserverIsInstalled() throws Exception {
        Consumer<String> observer = name -> {
        };
        var handler = IConversationService.observing(observer, snapshot -> {
        });

        IConversationMemory memory = driveSay(handler);

        assertSame(observer, memory.getToolCallObserver());
    }

    @Test
    @DisplayName("a handler without an observer leaves the turn with none")
    void handlerWithoutObserverLeavesNone() throws Exception {
        IConversationMemory memory = driveSay(mock(ConversationResponseHandler.class));

        assertNull(memory.getToolCallObserver());
    }
}
