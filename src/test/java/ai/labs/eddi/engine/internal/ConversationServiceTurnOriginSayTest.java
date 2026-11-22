/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
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
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.EscalationGuard;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.engine.security.TurnOriginTestSupport;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The origin taint of email plan v3 §5.3 through {@code say}: a turn of a
 * tainted conversation runs with the taint bound, and a turn submitted from a
 * tainted turn — a delegation continuing an existing conversation — or carrying
 * the taint in its context taints the conversation for good.
 */
@DisplayName("ConversationService — the email origin taint on say")
class ConversationServiceTurnOriginSayTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "0123456789abcdef01234568";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "0123456789abcdef89abcd01";
    private static final String USER_ID = "user-origin";

    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private IConversationMemoryStore conversationMemoryStore;
    @Mock
    private IConversationCoordinator conversationCoordinator;
    @Mock
    private ICacheFactory cacheFactory;
    @Mock
    private IRuntime runtime;
    @Mock
    private IContextLogger contextLogger;
    @Mock
    private TenantQuotaService tenantQuotaService;
    @Mock
    private ICache<String, ConversationState> conversationStateCache;
    @Mock
    private IAgent agent;
    @Mock
    private IConversation conversation;

    private final ResolutionPrincipalContext principals = new ResolutionPrincipalContext();
    private ConversationService conversationService;
    private final List<IConversationMemory> builtOver = new ArrayList<>();
    private final List<IConversationOutputRenderer> renderers = new ArrayList<>();
    /** What the guard answered inside the pipeline, per turn. */
    private final List<Boolean> refusedInPipeline = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        doReturn(new HashMap<String, String>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());
        doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        doReturn(1L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
        doReturn(false).when(conversation).isEnded();
        doAnswer(inv -> {
            builtOver.add(inv.getArgument(0));
            renderers.add(inv.getArgument(2));
            return conversation;
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
        // The "pipeline": asks the guard what an update_email_senders call would get.
        doAnswer(inv -> {
            IConversationMemory memory = builtOver.getLast();
            refusedInPipeline.add(EscalationGuard.refuseTool("update_email_senders").isPresent());
            memory.setConversationState(ConversationState.READY);
            renderers.getLast().renderOutput(memory);
            return null;
        }).when(conversation).say(anyString(), any());
        doAnswer(invocation -> {
            Callable<Object> callable = invocation.getArgument(0);
            IRuntime.IFinishedExecution<Object> listener = invocation.getArgument(1);
            Object result = callable.call();
            listener.onComplete(result);
            return CompletableFuture.completedFuture(result);
        }).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());

        conversationService = new ConversationService(agentFactory, conversationMemoryStore, mock(IConversationDescriptorStore.class),
                mock(IUserMemoryStore.class), conversationCoordinator, mock(IConversationSetup.class), cacheFactory, runtime, contextLogger,
                mock(AuditLedgerService.class), mock(GdprComplianceService.class), tenantQuotaService, mock(IScheduleStore.class),
                mock(IAgentStore.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);
        conversationService.resolutionPrincipalContext = principals;
        doReturn("default").when(tenantQuotaService).getDefaultTenantId();
    }

    @AfterEach
    void tearDown() {
        principals.clear();
    }

    @Test
    @DisplayName("a turn of a stored tainted conversation runs tainted")
    void storedTaintIsBound() throws Exception {
        doReturn(snapshot(TurnOrigin.EMAIL)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        say(new HashMap<>());
        runQueuedTurn();

        assertEquals(List.of(true), refusedInPipeline);
        assertEquals(TurnOrigin.EMAIL, persistedSnapshot().getOrigin());
    }

    @Test
    @DisplayName("a turn submitted from a tainted turn taints an untainted conversation, and the taint is stored")
    void taintedSubmitterTaints() throws Exception {
        doReturn(snapshot(null)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        // converseWithAgent with an existing conversationId: the parent's tainted turn
        // is bound on the submitting thread.
        principals.bind(new ResolutionPrincipal("someone-else", Provenance.SELF_ASSERTED, TurnOrigin.EMAIL));
        say(new HashMap<>());
        principals.clear();
        runQueuedTurn();

        assertEquals(List.of(true), refusedInPipeline);
        assertEquals(TurnOrigin.EMAIL, persistedSnapshot().getOrigin());
    }

    /**
     * The httpcall (or MCP client) of a tainted turn that continues a conversation
     * through EDDI's own API — {@code POST /agents/{conversationId}}, MCP
     * {@code talk_to_agent} — arrives with {@code X-Eddi-Turn-Origin: email}; the
     * turn it submits is refused the guarded tool, and the conversation stays
     * tainted.
     */
    @Test
    @DisplayName("a turn submitted for a request carrying X-Eddi-Turn-Origin taints the conversation and is refused update_email_senders")
    void taintedRequestTaints() throws Exception {
        doReturn(snapshot(null)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
            say(new HashMap<>());
        }
        runQueuedTurn();

        assertEquals(List.of(true), refusedInPipeline);
        assertEquals(TurnOrigin.EMAIL, persistedSnapshot().getOrigin());
    }

    @Test
    @DisplayName("a turn whose context carries turnOrigin=email taints the conversation")
    void contextTaints() throws Exception {
        doReturn(snapshot(null)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        say(new HashMap<>(Map.of(TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, TurnOrigin.EMAIL))));
        runQueuedTurn();

        assertEquals(List.of(true), refusedInPipeline);
        assertEquals(TurnOrigin.EMAIL, persistedSnapshot().getOrigin());
    }

    @Test
    @DisplayName("an untainted turn of an untainted conversation is not refused")
    void untainted() throws Exception {
        doReturn(snapshot(null)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        say(new HashMap<>());
        runQueuedTurn();

        assertEquals(List.of(false), refusedInPipeline);
        assertNull(persistedSnapshot().getOrigin());
    }

    private void say(Map<String, Context> context) throws Exception {
        conversationService.say(CONVERSATION_ID, false, true, List.of(), new InputData("hello", context), false,
                mock(ConversationResponseHandler.class));
    }

    private void runQueuedTurn() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        captor.getValue().call();
        assertTrue(refusedInPipeline.size() == 1, "the pipeline must have run once");
    }

    private ConversationMemorySnapshot persistedSnapshot() throws Exception {
        ArgumentCaptor<ConversationMemorySnapshot> stored = ArgumentCaptor.forClass(ConversationMemorySnapshot.class);
        verify(conversationMemoryStore, atLeastOnce()).storeConversationMemorySnapshot(stored.capture());
        return stored.getValue();
    }

    private static ConversationMemorySnapshot snapshot(String origin) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setOrigin(origin);
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
