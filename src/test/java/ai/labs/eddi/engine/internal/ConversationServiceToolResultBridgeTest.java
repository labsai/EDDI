/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
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
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The two places {@link ConversationService} forwards the pipeline's
 * {@code onToolResult} to a streaming handler: the event sink it installs on
 * the turn memory, and the idempotency wrapper around the handler when the
 * request carries an {@code Idempotency-Key}. A tool call finishing inside the
 * pipeline must reach the handler through either.
 */
@DisplayName("ConversationService — tool_result reaches the streaming handler")
class ConversationServiceToolResultBridgeTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-bridge";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-bridge-1";
    private static final String USER_ID = "user-bridge";

    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private IConversationMemoryStore conversationMemoryStore;
    @Mock
    private IConversationDescriptorStore conversationDescriptorStore;
    @Mock
    private IUserMemoryStore userMemoryStore;
    @Mock
    private IConversationCoordinator conversationCoordinator;
    @Mock
    private IConversationSetup conversationSetup;
    @Mock
    private ICacheFactory cacheFactory;
    @Mock
    private IRuntime runtime;
    @Mock
    private IContextLogger contextLogger;
    @Mock
    private AuditLedgerService auditLedgerService;
    @Mock
    private GdprComplianceService gdprComplianceService;
    @Mock
    private TenantQuotaService tenantQuotaService;
    @Mock
    private IScheduleStore scheduleStore;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private ICache<String, ConversationState> conversationStateCache;
    @Mock
    private IAgent agent;
    @Mock
    private IConversation conversation;

    private ConversationService conversationService;
    private final List<IConversationMemory> builtOver = new ArrayList<>();
    private final List<IConversationOutputRenderer> renderers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        doReturn(new HashMap<String, String>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());
        doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        doReturn(1L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
        doReturn(snapshot()).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(false).when(conversation).isEnded();
        doAnswer(inv -> {
            builtOver.add(inv.getArgument(0));
            renderers.add(inv.getArgument(2));
            return conversation;
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
        // The "pipeline": a tool call finishes through the sink the service
        // installed on the memory, then the turn renders.
        doAnswer(inv -> {
            IConversationMemory memory = builtOver.getLast();
            memory.getEventSink().onToolResult("lookup", "c1", "error", 12L, "upstream said no");
            renderers.getLast().renderOutput(memory);
            return null;
        }).when(conversation).say(anyString(), any());
        stubRuntimeInline();

        conversationService = new ConversationService(agentFactory, conversationMemoryStore, conversationDescriptorStore, userMemoryStore,
                conversationCoordinator, conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService, gdprComplianceService,
                tenantQuotaService, scheduleStore, agentStore, jsonSerialization, new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);
        // Without the service the Idempotency-Key is ignored and the handler is never
        // wrapped.
        conversationService.turnIdempotency = new TurnIdempotencyService(600, 30, new SimpleMeterRegistry(), System::currentTimeMillis);
    }

    @Test
    @DisplayName("the memory event sink forwards onToolResult to the streaming handler")
    void eventSinkForwardsToolResult() throws Exception {
        StreamingResponseHandler handler = mock(StreamingResponseHandler.class);

        conversationService.sayStreaming(CONVERSATION_ID, false, true, List.of(), new InputData("hello", new HashMap<>()), handler);
        runQueuedTurns();

        verify(handler).onToolResult("lookup", "c1", "error", 12L, "upstream said no");
    }

    @Test
    @DisplayName("the idempotency wrapper forwards onToolResult to the caller handler too")
    void idempotentHandlerForwardsToolResult() throws Exception {
        StreamingResponseHandler handler = mock(StreamingResponseHandler.class);
        var input = new InputData("hello", new HashMap<>());
        input.setIdempotencyKey("k-bridge");

        conversationService.sayStreaming(CONVERSATION_ID, false, true, List.of(), input, handler);
        runQueuedTurns();

        verify(handler).onToolResult("lookup", "c1", "error", 12L, "upstream said no");
    }

    private void runQueuedTurns() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator, atLeastOnce()).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        for (Callable<Void> turn : captor.getAllValues()) {
            turn.call();
        }
        verify(conversation, times(1)).say(anyString(), any());
    }

    @SuppressWarnings("unchecked")
    private void stubRuntimeInline() {
        doAnswer(invocation -> {
            Callable<Object> callable = invocation.getArgument(0);
            IRuntime.IFinishedExecution<Object> listener = invocation.getArgument(1);
            Object result = callable.call();
            listener.onComplete(result);
            return CompletableFuture.completedFuture(result);
        }).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());
    }

    private static ConversationMemorySnapshot snapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setEnvironment(ENV);
        snapshot.setRevision(1L);
        var stepSnapshot = new ConversationStepSnapshot();
        var workflowRun = new WorkflowRunSnapshot();
        workflowRun.getLifecycleTasks().add(new ResultSnapshot("input:initial", "message 0", null, new Date(), null, true));
        stepSnapshot.getWorkflows().add(workflowRun);
        snapshot.getConversationSteps().add(stepSnapshot);
        var output = new ConversationOutput();
        output.put("input", "message 0");
        snapshot.getConversationOutputs().add(output);
        return snapshot;
    }
}
