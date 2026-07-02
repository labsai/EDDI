/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService.ConversationAwaitingApprovalException;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the <strong>say()-path HITL guards</strong> in
 * {@link ConversationService} — the single most common end-user action during a
 * pending approval (sending another message) at the layer where the guards
 * live.
 *
 * <p>
 * Covers three finding-driven gaps that previously had ZERO coverage:
 * </p>
 * <ul>
 * <li><b>Fast-fail</b> (finding 10): say() into an AWAITING_HUMAN memory throws
 * {@link ConversationAwaitingApprovalException} BEFORE any quota/reference
 * bookkeeping, and the response handler is never invoked.</li>
 * <li><b>Queued-say guard</b> (finding 10): a turn accepted before the pause
 * committed re-reads the persisted state inside the coordinator callable and is
 * dropped — onSkipped fires with the persisted state, the turn is never
 * executed, and no full-document store overwrites the pause.</li>
 * <li><b>Zombie-pause guard</b> (deliverable B): a turn whose memory carried a
 * stale AWAITING_HUMAN state that this turn did not produce is discarded — no
 * store, no schedule, no counter.</li>
 * </ul>
 */
class ConversationServiceSayHitlTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-say-hitl";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "conv-say-hitl-1";
    private static final String USER_ID = "user-say-hitl";
    private static final int AGENT_TIMEOUT = 30;

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
    private ICache<String, ConversationState> conversationStateCache;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        doReturn(new HashMap<String, String>()).when(contextLogger)
                .createLoggingContext(any(), any(), any(), any());
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                new SimpleMeterRegistry(), AGENT_TIMEOUT);
    }

    // =========================================================================
    // Fast-fail: say() on an AWAITING_HUMAN memory
    // =========================================================================

    @Nested
    @DisplayName("say() fast-fail on AWAITING_HUMAN")
    class FastFail {

        @Test
        @DisplayName("throws ConversationAwaitingApprovalException BEFORE quota/reference bookkeeping; handler never invoked")
        void awaitingHuman_fastFails() throws Exception {
            // Loaded memory is AWAITING_HUMAN
            doReturn(snapshot(ConversationState.AWAITING_HUMAN))
                    .when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            var ex = assertThrows(ConversationAwaitingApprovalException.class,
                    () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(),
                            new InputData("hi again", Map.of()), false, handler));
            assertTrue(ex.getMessage().contains("awaiting human approval"),
                    "message must be actionable, got: " + ex.getMessage());

            // Fast-fail happens BEFORE quota acquisition and before the turn is submitted
            verify(tenantQuotaService, never()).acquireApiCallSlot();
            verify(conversationCoordinator, never()).submitInOrder(anyString(), any());
            // The turn never ran, and the handler is never notified (neither onComplete
            // nor onSkipped) — the caller learns via the exception → REST 409.
            verifyNoInteractions(handler);
            verify(agentFactory, never()).getAgent(any(), any(), any());
        }
    }

    // =========================================================================
    // Queued-say guard: pause committed after acceptance
    // =========================================================================

    @Nested
    @DisplayName("queued-say guard (persisted state changed after acceptance)")
    class QueuedSayGuard {

        /**
         * Wires the happy say() path up to submit: loaded memory READY (passes
         * fast-fail), agent deployed, quota allowed, continueConversation returns a
         * live (non-ended) conversation. Returns the captured coordinator callable so
         * the test can drive the queued turn deterministically.
         */
        private Callable<Void> acceptTurnAndCaptureCallable(IConversation conversation,
                                                            ConversationResponseHandler handler)
                throws Exception {
            doReturn(snapshot(ConversationState.READY))
                    .when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();

            IAgent agent = mock(IAgent.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(conversation).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
            doReturn(false).when(conversation).isEnded();

            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(),
                    new InputData("queued message", Map.of()), false, handler);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("persisted AWAITING_HUMAN → onSkipped(persisted state), turn not executed, memory not stored, no metrics leak")
        void persistedAwaitingHuman_skips() throws Exception {
            IConversation conversation = mock(IConversation.class);
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            Callable<Void> queued = acceptTurnAndCaptureCallable(conversation, handler);

            // Inside the callable the persisted state is re-read and reports the pause
            // that committed after this turn was accepted.
            doReturn(ConversationState.AWAITING_HUMAN)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            queued.call();

            // The turn is dropped: the pipeline never ran (no runtime submission, no
            // conversation.say), and the pause document is never overwritten.
            verify(runtime, never()).submitCallable(any(), any(), any());
            verify(conversation, never()).say(anyString(), any());
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());

            // onSkipped fires with the PERSISTED state — the client gets a prompt,
            // honest answer (REST → 409 "awaiting approval") instead of a watchdog
            // timeout, and the metrics reference is removed (not leaked).
            ArgumentCaptor<SimpleConversationMemorySnapshot> snapCaptor = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            verify(handler).onSkipped(snapCaptor.capture());
            assertEquals(ConversationState.AWAITING_HUMAN, snapCaptor.getValue().getConversationState(),
                    "onSkipped snapshot must reflect the persisted pause state");
            verify(handler, never()).onComplete(any());
        }

        @Test
        @DisplayName("persisted IN_PROGRESS → onSkipped(IN_PROGRESS), turn not executed, memory not stored")
        void persistedInProgress_skips() throws Exception {
            IConversation conversation = mock(IConversation.class);
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            Callable<Void> queued = acceptTurnAndCaptureCallable(conversation, handler);

            // A resume is executing (IN_PROGRESS) — the queued say must not race it.
            doReturn(ConversationState.IN_PROGRESS)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            queued.call();

            verify(runtime, never()).submitCallable(any(), any(), any());
            verify(conversation, never()).say(anyString(), any());
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());

            ArgumentCaptor<SimpleConversationMemorySnapshot> snapCaptor = ArgumentCaptor.forClass(SimpleConversationMemorySnapshot.class);
            verify(handler).onSkipped(snapCaptor.capture());
            assertEquals(ConversationState.IN_PROGRESS, snapCaptor.getValue().getConversationState());
        }

        @Test
        @DisplayName("persisted READY → turn proceeds normally (guard is not over-eager)")
        void persistedReady_proceeds() throws Exception {
            IConversation conversation = mock(IConversation.class);
            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);

            Callable<Void> queued = acceptTurnAndCaptureCallable(conversation, handler);

            // Persisted state is READY — the queued-say guard must let the turn run.
            doReturn(ConversationState.READY)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            // Runtime executes the pipeline callable inline, then signals completion.
            stubRuntimeInline();

            queued.call();

            // The turn actually executed against the live conversation.
            verify(conversation).say(eq("queued message"), any());
            verify(handler, never()).onSkipped(any());
        }
    }

    // =========================================================================
    // Zombie-pause guard: onComplete drops a stale AWAITING_HUMAN this turn
    // did not produce (memory carried it in from a divergent snapshot).
    // =========================================================================

    @Nested
    @DisplayName("zombie-pause guard (stale AWAITING_HUMAN at submit AND at completion)")
    class ZombiePauseGuard {

        @Test
        @DisplayName("memory AWAITING_HUMAN at submit and completion → discarded: no store, no schedule, no counter")
        void staleAwaitingHuman_discarded() throws Exception {
            // Loaded memory is READY at say()-entry so the fast-fail passes and the
            // turn is accepted.
            var loadedSnapshot = snapshot(ConversationState.READY);
            doReturn(loadedSnapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(false).when(conversation).isEnded();
            // Capture the real live memory handed to continueConversation so we can
            // simulate the divergent-backend condition on it.
            var memoryRef = new java.util.concurrent.atomic.AtomicReference<IConversationMemory>();
            doAnswer(inv -> {
                memoryRef.set(inv.getArgument(0));
                return conversation;
            }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            // Persisted (column) state is READY → the queued-say guard passes.
            doReturn(ConversationState.READY)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            stubRuntimeInline();

            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);
            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(),
                    new InputData("stale turn", Map.of()), false, handler);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());

            // Divergence: between acceptance and the coordinator running the callable,
            // the loaded memory snapshot carries a stale AWAITING_HUMAN the CAS'd
            // column already resolved. Set it now so memoryStateAtSubmit captures it,
            // and the (no-op) pipeline leaves it AWAITING_HUMAN at completion.
            memoryRef.get().setConversationState(ConversationState.AWAITING_HUMAN);
            doNothing().when(conversation).say(anyString(), any()); // pipeline leaves state as-is

            captor.getValue().call();

            // The turn did not PRODUCE this pause → the result is dropped entirely:
            // never persisted, never re-armed, never counted. Persisting would
            // resurrect a terminally resolved approval as a zombie.
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("memory READY at submit → a genuine pause this turn produced IS persisted and armed")
        void genuinePause_persistedAndArmed() throws Exception {
            // Companion: proves the zombie guard is not over-eager — a pause the turn
            // legitimately produces (memoryStateAtSubmit READY) is persisted.
            var loadedSnapshot = snapshot(ConversationState.READY);
            doReturn(loadedSnapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();

            // Agent config with a finite timeout policy → the pause arms a schedule.
            var agentConfig = new AgentConfiguration();
            var hitlConfig = new AgentConfiguration.HitlConfig();
            hitlConfig.setApprovalTimeout("PT30M");
            hitlConfig.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            agentConfig.setHitlConfig(hitlConfig);
            doReturn(agentConfig).when(agentStore).read(AGENT_ID, AGENT_VERSION);

            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
            doReturn(false).when(conversation).isEnded();
            var memoryRef = new java.util.concurrent.atomic.AtomicReference<IConversationMemory>();
            doAnswer(inv -> {
                memoryRef.set(inv.getArgument(0));
                return conversation;
            }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());

            doReturn(ConversationState.READY)
                    .when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
            stubRuntimeInline();

            ConversationResponseHandler handler = mock(ConversationResponseHandler.class);
            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, List.of(),
                    new InputData("please delete my account", Map.of()), false, handler);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());

            // The pipeline itself pauses THIS turn — memoryStateAtSubmit was READY,
            // the pause is produced during execution.
            doAnswer(inv -> {
                memoryRef.get().setConversationState(ConversationState.AWAITING_HUMAN);
                return null;
            }).when(conversation).say(anyString(), any());

            captor.getValue().call();

            // A genuine pause is persisted and its finite-policy timeout schedule armed.
            verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
            ArgumentCaptor<ai.labs.eddi.engine.schedule.model.ScheduleConfiguration> schedCaptor = ArgumentCaptor
                    .forClass(ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(schedCaptor.capture());
            var schedule = schedCaptor.getValue();
            assertEquals("hitl-timeout-" + CONVERSATION_ID, schedule.getName());
            assertEquals("AUTO_REJECT", schedule.getMetadata().get("policy"));
            assertEquals("regular", schedule.getMetadata().get("surface"));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Runtime mock that runs the pipeline callable inline and fires onComplete. */
    @SuppressWarnings("unchecked")
    private void stubRuntimeInline() {
        doAnswer(invocation -> {
            Callable<Object> callable = invocation.getArgument(0);
            IRuntime.IFinishedExecution<Object> listener = invocation.getArgument(1);
            try {
                Object result = callable.call();
                listener.onComplete(result);
                return java.util.concurrent.CompletableFuture.completedFuture(result);
            } catch (Exception e) {
                listener.onFailure(e);
                return java.util.concurrent.CompletableFuture.failedFuture(e);
            }
        }).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());
    }

    /**
     * Minimal snapshot loadable into a live ConversationMemory in the given state.
     */
    private ConversationMemorySnapshot snapshot(ConversationState state) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(state);
        snapshot.setEnvironment(ENV);

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
}
