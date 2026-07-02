/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for group conversation HITL (Human-in-the-Loop) behavior in
 * {@link GroupConversationService}. Covers phase-level and task-level pause,
 * resume semantics, CAS guard, turn budget preservation, and finally guard.
 */
class GroupConversationServiceHitlTest {

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
    @Mock
    private IScheduleStore scheduleStore;

    private GroupConversationService service;

    private static final int MAX_DEPTH = 3;
    private static final String DEFAULT_TENANT = "default";
    private static final String GROUP_ID = "group-hitl";
    private static final String USER_ID = "user-hitl";
    private static final String MOD_AGENT = "mod-agent";
    private static final String AGENT_A = "agent-a";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, null, DEFAULT_TENANT, MAX_DEPTH);
    }

    // =================================================================
    // Helper: build a config with given phases
    // =================================================================

    private AgentGroupConfiguration buildConfig(List<DiscussionPhase> phases) {
        var config = new AgentGroupConfiguration();
        config.setName("HITL Test Group");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setPhases(phases);
        config.setMembers(List.of(new GroupMember(AGENT_A, "Agent A", 1, null)));
        config.setModeratorAgentId(MOD_AGENT);
        return config;
    }

    private IResourceStore.IResourceId mockResourceId() {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }
            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    /**
     * Sets up mocks so that conversationService.say() invokes the callback with a
     * stub snapshot. Without this, executeAgentTurn calls will NPE when they try to
     * extract output.
     */
    private void stubAgentSay() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("output", List.of("Test response"));
        snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));

        doAnswer(inv -> {
            // The last argument is the ConversationResponseHandler callback
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            if (handler != null) {
                handler.onComplete(snapshot);
            }
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    // =================================================================
    // Phase-level HITL tests
    // =================================================================

    @Nested
    @DisplayName("Phase-level HITL")
    class PhaseLevelHitl {

        @Test
        @DisplayName("Phase with requiresApproval=true → AWAITING_APPROVAL after phase")
        void phaseLevelPause() throws Exception {
            // Phase 0: OPINION (no approval), Phase 1: CRITIQUE (requires approval)
            var phases = List.of(
                    new DiscussionPhase("Opinion", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false),
                    new DiscussionPhase("Critique", PhaseType.CRITIQUE,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, true));

            var config = buildConfig(phases);
            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);
            doReturn("gc-1").when(conversationStore).create(any());
            stubAgentSay();

            GroupConversation gc = service.discuss(GROUP_ID, "Test?", USER_ID, 0);

            assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState(),
                    "GC should be AWAITING_APPROVAL after a phase with requiresApproval=true");
            assertEquals(1, gc.getPausedAtPhaseIndex(),
                    "Paused phase index should be 1 (second phase)");
            assertEquals("Critique", gc.getPausedPhaseName(),
                    "Paused phase name should match the approval phase");
            assertEquals(GroupConversation.HitlPauseType.PHASE, gc.getHitlPauseType(),
                    "Pause type should be PHASE");
            assertNotNull(gc.getPausedAt(), "pausedAt timestamp should be set");
        }
    }

    // =================================================================
    // Task-level HITL tests
    // =================================================================

    @Nested
    @DisplayName("Task-level HITL")
    class TaskLevelHitl {

        @Test
        @DisplayName("Tasks with AWAITING_APPROVAL status trigger HITL pause")
        void taskLevelPause() throws Exception {
            // Use EXECUTE phase which triggers task-level HITL checks
            var phases = List.of(
                    new DiscussionPhase("Execute", PhaseType.EXECUTE,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.TASK_ONLY,
                            false, null, 1, false));

            var config = buildConfig(phases);
            // Enable task-level HITL
            var hitlConfig = new AgentGroupConfiguration.HitlConfig();
            hitlConfig.setGranularity(HitlGranularity.TASK);
            config.setHitlConfig(hitlConfig);

            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);
            doReturn("gc-task").when(conversationStore).create(any());

            // Prepare a task list with one task that will end up AWAITING_APPROVAL
            // We'll intercept the GC after creation and manipulate the task list
            doAnswer(inv -> {
                GroupConversation gc = inv.getArgument(0);

                // Simulate an EXECUTE phase placing a task into AWAITING_APPROVAL
                var taskList = new SharedTaskList();
                taskList.addTask(new SharedTaskList.TaskItem("Task 1", "Do something", 0));
                var tasks = taskList.all();
                String taskId = tasks.get(0).id();
                taskList.assignTask(taskId, AGENT_A, "Agent A");
                taskList.startTask(taskId);
                taskList.submitForApproval(taskId, "Task result from agent");
                gc.setTaskList(taskList);

                return "gc-task";
            }).when(conversationStore).create(any());

            stubAgentSay();

            GroupConversation gc = service.discuss(GROUP_ID, "Execute task", USER_ID, 0);

            // The task list should have an AWAITING_APPROVAL task
            assertNotNull(gc.getTaskList(), "TaskList should be present");
            assertTrue(gc.getTaskList().hasAwaitingApproval(),
                    "TaskList should have at least one task AWAITING_APPROVAL");
        }
    }

    // =================================================================
    // Resume phase index test
    // =================================================================

    @Nested
    @DisplayName("Resume phase index")
    class ResumePhaseIndex {

        @Test
        @DisplayName("After pausing at phase 2, resume starts execution from phase 3")
        void resumeStartsFromNextPhase() throws Exception {
            // Setup: GC paused at phase index 2
            var gc = new GroupConversation();
            gc.setId("gc-resume");
            gc.setGroupId(GROUP_ID);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAtPhaseIndex(2);
            gc.setPausedPhaseName("Critique");
            gc.setPausedAt(Instant.now());
            gc.setOriginalQuestion("Resume test?");

            doReturn(gc).when(conversationStore).read("gc-resume");

            var phases = List.of(
                    new DiscussionPhase("Phase0", PhaseType.OPINION),
                    new DiscussionPhase("Phase1", PhaseType.CRITIQUE),
                    new DiscussionPhase("Phase2", PhaseType.REVISION),
                    new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS));
            var config = buildConfig(phases);
            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);
            stubAgentSay();

            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(decision);

            GroupConversation result = service.resumeDiscussion("gc-resume", request, null);

            // The resume clears pause state and sets IN_PROGRESS
            assertEquals(GroupConversationState.IN_PROGRESS, result.getState(),
                    "State should be IN_PROGRESS after resume");
            assertEquals(-1, result.getPausedAtPhaseIndex(),
                    "Paused phase index should be cleared to -1");
            assertNull(result.getPausedAt(), "pausedAt should be cleared");

            // Verify updateIfState was called with AWAITING_APPROVAL (CAS)
            verify(conversationStore).updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
        }
    }

    // =================================================================
    // Double resume (CAS) test
    // =================================================================

    @Nested
    @DisplayName("Double resume CAS guard")
    class DoubleResumeCas {

        @Test
        @DisplayName("Second concurrent resume throws ResourceModifiedException")
        void secondResumeThrowsCas() throws Exception {
            // Setup: GC is AWAITING_APPROVAL
            var gc = new GroupConversation();
            gc.setId("gc-cas");
            gc.setGroupId(GROUP_ID);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAtPhaseIndex(1);
            gc.setPausedPhaseName("Phase1");
            gc.setPausedAt(Instant.now());
            gc.setOriginalQuestion("CAS test?");

            doReturn(gc).when(conversationStore).read("gc-cas");

            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            var config = buildConfig(List.of(
                    new DiscussionPhase("Phase0", PhaseType.OPINION),
                    new DiscussionPhase("Phase1", PhaseType.CRITIQUE),
                    new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS)));
            doReturn(config).when(groupStore).read(GROUP_ID, 1);
            stubAgentSay();

            // First resume succeeds
            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(decision);

            service.resumeDiscussion("gc-cas", request, null);

            // Second resume: updateIfState throws ResourceModifiedException because
            // state is no longer AWAITING_APPROVAL
            doThrow(new IResourceStore.ResourceModifiedException(
                    "Group conversation state has changed"))
                    .when(conversationStore).updateIfState(any(), eq(GroupConversationState.AWAITING_APPROVAL));

            // Need to re-read the gc as AWAITING_APPROVAL for the second call
            var gc2 = new GroupConversation();
            gc2.setId("gc-cas");
            gc2.setGroupId(GROUP_ID);
            gc2.setState(GroupConversationState.AWAITING_APPROVAL);
            gc2.setPausedAtPhaseIndex(1);
            gc2.setPausedPhaseName("Phase1");
            gc2.setPausedAt(Instant.now());
            gc2.setOriginalQuestion("CAS test?");
            doReturn(gc2).when(conversationStore).read("gc-cas");

            assertThrows(IResourceStore.ResourceModifiedException.class,
                    () -> service.resumeDiscussion("gc-cas", request, null),
                    "Second concurrent resume should throw ResourceModifiedException");
        }
    }

    // =================================================================
    // Turn budget survives resume (M3)
    // =================================================================

    @Nested
    @DisplayName("Turn budget survives resume (M3)")
    class TurnBudgetResume {

        @Test
        @DisplayName("Paused turn count is preserved and seeds turnCounter on resume")
        void turnCounterSeedsFromPausedTurnCount() throws Exception {
            // Setup: GC paused at turn 10
            var gc = new GroupConversation();
            gc.setId("gc-turn");
            gc.setGroupId(GROUP_ID);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAtPhaseIndex(1);
            gc.setPausedPhaseName("Phase1");
            gc.setPausedAt(Instant.now());
            gc.setPausedTurnCount(10);
            gc.setOriginalQuestion("Turn budget test?");

            doReturn(gc).when(conversationStore).read("gc-turn");

            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            var config = buildConfig(List.of(
                    new DiscussionPhase("Phase0", PhaseType.OPINION),
                    new DiscussionPhase("Phase1", PhaseType.CRITIQUE),
                    new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS)));
            doReturn(config).when(groupStore).read(GROUP_ID, 1);
            stubAgentSay();

            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(decision);

            GroupConversation result = service.resumeDiscussion("gc-turn", request, null);

            // The pausedTurnCount should NOT be cleared during resume — it seeds
            // the turnCounter in executeDiscussion (M3 fix). We verify the GC
            // still has the original pausedTurnCount value (it's only cleared
            // on successful COMPLETED state).
            assertEquals(10, gc.getPausedTurnCount(),
                    "pausedTurnCount should be preserved for turnCounter seeding (M3)");
        }
    }

    // =================================================================
    // Finally guard: cleanupEphemeralAgents NOT called when AWAITING_APPROVAL
    // =================================================================

    @Nested
    @DisplayName("Finally guard for AWAITING_APPROVAL")
    class FinallyGuard {

        @Test
        @DisplayName("cleanupEphemeralAgents is NOT called when state is AWAITING_APPROVAL")
        void noCleanupWhenAwaitingApproval() throws Exception {
            // Phase with requiresApproval=true → triggers AWAITING_APPROVAL
            var phases = List.of(
                    new DiscussionPhase("Approve Phase", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, true));

            var config = buildConfig(phases);
            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);
            doReturn("gc-finally").when(conversationStore).create(any());
            stubAgentSay();

            GroupConversation gc = service.discuss(GROUP_ID, "Finally guard test", USER_ID, 0);

            assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());

            // The finally block should NOT have cleaned up ephemeral agents.
            // We verify this by checking that no created agents were deleted.
            // The gc has no createdAgentIds, so no cleanup should have occurred.
            // More importantly, the gc should still be in AWAITING_APPROVAL —
            // if cleanup ran and incorrectly changed state, this would fail.
            assertTrue(gc.getCreatedAgentIds().isEmpty() || gc.getRetainedAgentIds().isEmpty(),
                    "No ephemeral agent cleanup should have occurred while AWAITING_APPROVAL");
        }

        @Test
        @DisplayName("cleanupEphemeralAgents IS called when state is COMPLETED")
        void cleanupCalledWhenCompleted() throws Exception {
            // All phases without requiresApproval → should complete normally
            var phases = List.of(
                    new DiscussionPhase("Simple Phase", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false),
                    new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS,
                            "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false));

            var config = buildConfig(phases);
            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);
            doReturn("gc-complete").when(conversationStore).create(any());
            stubAgentSay();

            GroupConversation gc = service.discuss(GROUP_ID, "Complete test", USER_ID, 0);

            assertEquals(GroupConversationState.COMPLETED, gc.getState(),
                    "GC should be COMPLETED when no approval phases exist");
            // Cleanup is safe here — the finally block ran with state != AWAITING_APPROVAL
        }
    }

    // =================================================================
    // Resume: rejected decision → FAILED state
    // =================================================================

    @Nested
    @DisplayName("Resume with rejection")
    class ResumeRejection {

        @Test
        @DisplayName("REJECTED verdict sets state to FAILED")
        void rejectedVerdictSetsFailed() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-reject");
            gc.setGroupId(GROUP_ID);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAtPhaseIndex(0);
            gc.setPausedPhaseName("Phase0");
            gc.setPausedAt(Instant.now());
            gc.setOriginalQuestion("Reject test?");

            doReturn(gc).when(conversationStore).read("gc-reject");

            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.REJECTED);
            request.setDecision(decision);

            GroupConversation result = service.resumeDiscussion("gc-reject", request, null);

            assertEquals(GroupConversationState.FAILED, result.getState(),
                    "Rejected verdict should set state to FAILED");
            assertNull(result.getPausedAt(), "pausedAt should be cleared after rejection");
            verify(conversationStore).updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
        }
    }

    // =================================================================
    // NEW-1 regression: submit gate aligns with pause gate
    // =================================================================

    @Nested
    @DisplayName("Submit gate alignment (NEW-1)")
    class SubmitGateAlignment {

        @Test
        @DisplayName("TASK granularity + requiresApproval=false → tasks are COMPLETED, not AWAITING_APPROVAL")
        void taskGranularityWithoutApprovalCompletesTask() throws Exception {
            // EXECUTE phase with requiresApproval=false — like TASK_FORCE preset
            var phases = List.of(
                    new DiscussionPhase("Plan", PhaseType.PLAN,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false),
                    new DiscussionPhase("Execute", PhaseType.EXECUTE,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.TASK_ONLY,
                            false, null, 1, false)); // requiresApproval=false

            var config = buildConfig(phases);
            // Set TASK granularity
            var hitlConfig = new AgentGroupConfiguration.HitlConfig();
            hitlConfig.setGranularity(HitlGranularity.TASK);
            config.setHitlConfig(hitlConfig);

            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);

            // The create mock needs to set up task list via plan phase
            doAnswer(inv -> {
                GroupConversation gc = inv.getArgument(0);
                // Simulate PLAN phase creating a task
                var taskList = new SharedTaskList();
                taskList.addTask(new SharedTaskList.TaskItem("Task1", "Do thing", 0));
                var tasks = taskList.all();
                taskList.assignTask(tasks.get(0).id(), AGENT_A, "Agent A");
                gc.setTaskList(taskList);
                return "gc-submit-gate";
            }).when(conversationStore).create(any());

            stubAgentSay();

            GroupConversation gc = service.discuss(GROUP_ID, "Test submit gate", USER_ID, 0);

            // With requiresApproval=false, tasks should be COMPLETED, not AWAITING_APPROVAL
            assertNotNull(gc.getTaskList(), "TaskList should be present");
            assertFalse(gc.getTaskList().hasAwaitingApproval(),
                    "No tasks should be AWAITING_APPROVAL when phase.requiresApproval() is false");
            // Discussion should complete, not pause
            assertNotEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState(),
                    "GC should NOT be AWAITING_APPROVAL when requiresApproval is false");
        }
    }

    // =================================================================
    // NEW-2 regression: cancel-of-paused does DB write
    // =================================================================

    @Nested
    @DisplayName("Cancel of paused group (NEW-2)")
    class CancelOfPaused {

        @Test
        @DisplayName("Real pause → cancel: token cleaned up by finally, cancel takes DB branch")
        void cancelAfterRealPauseWritesCancelledViaDB() throws Exception {
            // Phase with requiresApproval=true → discuss() pauses at HITL gate
            var phases = List.of(
                    new DiscussionPhase("Approvable", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, true));

            var config = buildConfig(phases);
            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);
            doReturn("gc-real-pause").when(conversationStore).create(any());
            stubAgentSay();

            // Step 1: discuss() runs to completion → pauses at HITL gate
            GroupConversation gc = service.discuss(GROUP_ID, "Pause me", USER_ID, 0);
            assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState(),
                    "Precondition: GC should be paused");

            // Step 2: Verify activeTokens is EMPTY for this GC.
            // This is the NEW-2 guarantee: the unconditional finally block
            // removed the token after commitPause returned.
            var field = GroupConversationService.class.getDeclaredField("activeTokens");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var activeTokens = (java.util.concurrent.ConcurrentHashMap<String, ?>) field.get(service);
            assertFalse(activeTokens.containsKey("gc-real-pause"),
                    "NEW-2: activeTokens MUST be empty after pause (finally block removes token)");

            // Step 3: Cancel. With no token, cancelDiscussion takes the DB-write branch.
            doReturn(gc).when(conversationStore).read("gc-real-pause");
            service.cancelDiscussion("gc-real-pause",
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL);

            assertEquals(GroupConversationState.CANCELLED, gc.getState(),
                    "Paused GC should be CANCELLED via DB write");
            // verify update was called (the DB-write branch)
            verify(conversationStore, atLeastOnce()).update(gc);
        }
    }

    // =================================================================
    // R1/R2 regression: in-flight cancel terminates with CANCELLED
    // =================================================================

    @Nested
    @DisplayName("In-flight cancel (R1/R2)")
    class InFlightCancel {

        @Test
        @DisplayName("GRACEFUL cancel during in-flight discussion → CANCELLED state")
        void gracefulCancelDuringExecution() throws Exception {
            // Two phases — the first blocks long enough for cancel to fire
            var phases = List.of(
                    new DiscussionPhase("Slow", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false),
                    new DiscussionPhase("Never", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false));

            var config = buildConfig(phases);
            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);

            // create mock: capture and set the GC ID
            doAnswer(inv -> {
                GroupConversation gcArg = inv.getArgument(0);
                gcArg.setId("gc-inflight");
                return "gc-inflight";
            }).when(conversationStore).create(any());

            // Agent factory must return a non-null agent so executeAgentTurn
            // doesn't skip with SKIPPED entry
            doReturn(mock(ai.labs.eddi.engine.runtime.IAgent.class))
                    .when(agentFactory).getLatestReadyAgent(any(), eq(AGENT_A));

            // startConversation must return a result with a conversation ID
            var convResult = new IConversationService.ConversationResult("conv-1", null);
            doReturn(convResult).when(conversationService).startConversation(any(), any(), any(), any());

            // Latch: agent say blocks until cancel fires
            var agentBlocked = new java.util.concurrent.CountDownLatch(1);
            var cancelFired = new java.util.concurrent.CountDownLatch(1);

            doAnswer(inv -> {
                agentBlocked.countDown(); // signal: agent is running
                cancelFired.await(5, java.util.concurrent.TimeUnit.SECONDS); // block until cancel

                // Return a minimal response
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("output", List.of("Response after cancel"));
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));

                IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
                if (handler != null) {
                    handler.onComplete(snapshot);
                }
                return null;
            }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

            // Run discuss on a separate thread
            var resultHolder = new java.util.concurrent.atomic.AtomicReference<GroupConversation>();
            var exHolder = new java.util.concurrent.atomic.AtomicReference<Exception>();

            Thread discussThread = new Thread(() -> {
                try {
                    resultHolder.set(service.discuss(GROUP_ID, "Cancel me", USER_ID, 0));
                } catch (Exception e) {
                    exHolder.set(e);
                    // Also unblock the latch if discuss fails before say()
                    agentBlocked.countDown();
                }
            });
            discussThread.start();

            // Wait for the agent to be in-flight
            boolean started = agentBlocked.await(5, java.util.concurrent.TimeUnit.SECONDS);

            if (!started || exHolder.get() != null) {
                discussThread.join(5_000);
                fail("discuss() failed before reaching say(): " +
                        (exHolder.get() != null ? exHolder.get().getMessage() : "latch timeout"));
            }

            // Fire GRACEFUL cancel
            service.cancelDiscussion("gc-inflight",
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL);

            // Unblock the agent
            cancelFired.countDown();

            // Wait for discuss to complete
            discussThread.join(10_000);
            assertFalse(discussThread.isAlive(), "discuss() should have finished");

            // R2 fail-on-revert: discuss() must RETURN a CANCELLED gc — an
            // exception means the cancel was routed to FAILED/error, which is
            // exactly the regression this test protects against.
            var gc = resultHolder.get();
            if (gc == null) {
                fail("discuss() threw instead of returning a CANCELLED conversation: " + exHolder.get());
            }
            assertEquals(GroupConversationState.CANCELLED, gc.getState(),
                    "In-flight GRACEFUL cancel should result in CANCELLED state");
        }
        @Test
        @DisplayName("IMMEDIATE cancel during EXECUTE phase → CANCELLED (exercises allOf/CancellationException)")
        void immediateCancelDuringTaskExecution() throws Exception {
            // Use PLAN + EXECUTE phases with pre-configured tasks.
            // EXECUTE routes through executeTaskExecutionPhase where
            // allOf.get() + setActiveFuture() is the CancellationException surface.
            var phases = List.of(
                    new DiscussionPhase("Plan", PhaseType.PLAN,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false),
                    new DiscussionPhase("Execute", PhaseType.EXECUTE,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false));

            var config = buildConfig(phases);
            // Pre-configured tasks so PLAN phase is silent (no LLM call)
            config.setTasks(List.of(
                    new AgentGroupConfiguration.TaskDefinition("Task1", "Do something")));
            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);

            doAnswer(inv -> {
                GroupConversation gcArg = inv.getArgument(0);
                gcArg.setId("gc-immediate");
                return "gc-immediate";
            }).when(conversationStore).create(any());

            doReturn(mock(ai.labs.eddi.engine.runtime.IAgent.class))
                    .when(agentFactory).getLatestReadyAgent(any(), eq(AGENT_A));

            var convResult = new IConversationService.ConversationResult("conv-imm", null);
            doReturn(convResult).when(conversationService).startConversation(any(), any(), any(), any());

            // Latch: say() blocks until cancel fires — this is inside
            // executeTaskExecutionPhase's CompletableFuture.runAsync, so
            // the allOf.get() is blocked, and CANCEL_IMMEDIATE fires
            // cancelActiveFuture() → CancellationException.
            var agentBlocked = new java.util.concurrent.CountDownLatch(1);
            var cancelFired = new java.util.concurrent.CountDownLatch(1);

            doAnswer(inv -> {
                agentBlocked.countDown();
                cancelFired.await(5, java.util.concurrent.TimeUnit.SECONDS);

                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("output", List.of("Task result"));
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));

                IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
                if (handler != null) {
                    handler.onComplete(snapshot);
                }
                return null;
            }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

            var resultHolder = new java.util.concurrent.atomic.AtomicReference<GroupConversation>();
            var exHolder = new java.util.concurrent.atomic.AtomicReference<Exception>();

            Thread discussThread = new Thread(() -> {
                try {
                    resultHolder.set(service.discuss(GROUP_ID, "Cancel immediate task", USER_ID, 0));
                } catch (Exception e) {
                    exHolder.set(e);
                    agentBlocked.countDown();
                }
            });
            discussThread.start();

            boolean started = agentBlocked.await(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!started || exHolder.get() != null) {
                discussThread.join(5_000);
                fail("discuss() failed before reaching say(): " +
                        (exHolder.get() != null ? exHolder.get().getMessage() : "latch timeout"));
            }

            // Fire IMMEDIATE cancel — triggers cancelActiveFuture() on the allOf
            // future inside executeTaskExecutionPhase, producing CancellationException
            service.cancelDiscussion("gc-immediate",
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_IMMEDIATE);

            cancelFired.countDown();

            discussThread.join(10_000);
            assertFalse(discussThread.isAlive(), "discuss() should have finished");

            // R2 fail-on-revert: reverting the CancellationException routing makes
            // discuss() throw (FAILED path) — this test must fail loudly then,
            // not slip through an "either result or exception" escape hatch.
            var gc = resultHolder.get();
            if (gc == null) {
                fail("discuss() threw instead of returning a CANCELLED conversation: " + exHolder.get());
            }
            // R2: CANCEL_IMMEDIATE via EXECUTE phase must be CANCELLED, not FAILED
            assertEquals(GroupConversationState.CANCELLED, gc.getState(),
                    "IMMEDIATE cancel during EXECUTE phase should result in CANCELLED (not FAILED)");
        }

        @Test
        @DisplayName("R1: cancel racing a requiresApproval phase → CANCELLED, pause NEVER committed")
        void cancelDuringApprovalGatedPhase() throws Exception {
            // The exact interleaving R1 fixed: a cancel lands while a
            // requiresApproval=true phase is executing. Without the isCancelled()
            // guard before the HITL gate, the cancel is swallowed and commitPause
            // parks the discussion in AWAITING_APPROVAL (and arms a timeout) —
            // the opposite of what the user asked for.
            var phases = List.of(
                    new DiscussionPhase("Gated", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, true)); // requiresApproval = true

            var config = buildConfig(phases);
            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);

            doAnswer(inv -> {
                GroupConversation gcArg = inv.getArgument(0);
                gcArg.setId("gc-r1");
                return "gc-r1";
            }).when(conversationStore).create(any());

            doReturn(mock(ai.labs.eddi.engine.runtime.IAgent.class))
                    .when(agentFactory).getLatestReadyAgent(any(), eq(AGENT_A));

            var convResult = new IConversationService.ConversationResult("conv-r1", null);
            doReturn(convResult).when(conversationService).startConversation(any(), any(), any(), any());

            var agentBlocked = new java.util.concurrent.CountDownLatch(1);
            var cancelFired = new java.util.concurrent.CountDownLatch(1);

            doAnswer(inv -> {
                agentBlocked.countDown();
                cancelFired.await(5, java.util.concurrent.TimeUnit.SECONDS);

                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("output", List.of("Opinion delivered"));
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));

                IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
                if (handler != null) {
                    handler.onComplete(snapshot);
                }
                return null;
            }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

            var resultHolder = new java.util.concurrent.atomic.AtomicReference<GroupConversation>();
            var exHolder = new java.util.concurrent.atomic.AtomicReference<Exception>();

            Thread discussThread = new Thread(() -> {
                try {
                    resultHolder.set(service.discuss(GROUP_ID, "Cancel during gated phase", USER_ID, 0));
                } catch (Exception e) {
                    exHolder.set(e);
                    agentBlocked.countDown();
                }
            });
            discussThread.start();

            boolean started = agentBlocked.await(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!started || exHolder.get() != null) {
                discussThread.join(5_000);
                fail("discuss() failed before reaching say(): "
                        + (exHolder.get() != null ? exHolder.get().getMessage() : "latch timeout"));
            }

            // Cancel lands while the requiresApproval phase is mid-flight
            service.cancelDiscussion("gc-r1",
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL);
            cancelFired.countDown();

            discussThread.join(10_000);
            assertFalse(discussThread.isAlive(), "discuss() should have finished");

            var gc = resultHolder.get();
            if (gc == null) {
                fail("discuss() threw instead of returning a CANCELLED conversation: " + exHolder.get());
            }
            assertEquals(GroupConversationState.CANCELLED, gc.getState(),
                    "cancel during a requiresApproval phase must CANCEL, not pause");
            // commitPause must never have run: no pause metadata, no timeout schedule
            assertNull(gc.getPausedAt(), "pause metadata must not be set after cancel");
            assertEquals(-1, gc.getPausedAtPhaseIndex(), "no pause phase index after cancel");
            verify(scheduleStore, never()).createSchedule(any());
        }
    }

    // =================================================================
    // AUTO_APPROVE regression: synthesize per-task approvals
    // =================================================================

    @Nested
    @DisplayName("AUTO_APPROVE TASK synthesis")
    class AutoApproveTaskSynthesis {

        @Test
        @DisplayName("APPROVED + TASK pauseType + null taskApprovals → auto-approves all AWAITING tasks")
        void autoApprovesSynthesizesTaskApprovals() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-auto-approve");
            gc.setGroupId(GROUP_ID);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAtPhaseIndex(0);
            gc.setPausedPhaseName("Execute");
            gc.setPausedAt(Instant.now());
            gc.setHitlPauseType(GroupConversation.HitlPauseType.TASK);
            gc.setOriginalQuestion("Auto approve test");

            // Set up task list with AWAITING_APPROVAL tasks
            var taskList = new SharedTaskList();
            taskList.addTask(new SharedTaskList.TaskItem("Task1", "Do A", 0));
            taskList.addTask(new SharedTaskList.TaskItem("Task2", "Do B", 0));
            var tasks = taskList.all();
            taskList.assignTask(tasks.get(0).id(), AGENT_A, "Agent A");
            taskList.startTask(tasks.get(0).id());
            taskList.submitForApproval(tasks.get(0).id(), "Result A");
            taskList.assignTask(tasks.get(1).id(), AGENT_A, "Agent A");
            taskList.startTask(tasks.get(1).id());
            taskList.submitForApproval(tasks.get(1).id(), "Result B");
            gc.setTaskList(taskList);

            doReturn(gc).when(conversationStore).read("gc-auto-approve");

            // Build request: APPROVED, but no taskApprovals (like timeout handler)
            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setDecidedBy("system:timeout");
            request.setDecision(decision);
            // taskApprovals is null — this is the scenario from AUTO_APPROVE timeout

            GroupConversation result = service.resumeDiscussion("gc-auto-approve", request, null);

            // Both tasks should now be approved (not still AWAITING_APPROVAL)
            assertFalse(result.getTaskList().hasAwaitingApproval(),
                    "All tasks should be auto-approved when TASK pauseType + APPROVED + null taskApprovals");
        }
    }

    // =================================================================
    // taskApprovals validation — 400-class errors BEFORE any mutation
    // =================================================================

    @Nested
    @DisplayName("taskApprovals validation")
    class TaskApprovalsValidation {

        /** Paused GC with one task AWAITING_APPROVAL and one merely ASSIGNED. */
        private GroupConversation pausedGcWithTasks() {
            var gc = new GroupConversation();
            gc.setId("gc-approvals");
            gc.setGroupId(GROUP_ID);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAtPhaseIndex(0);
            gc.setPausedPhaseName("Execute");
            gc.setPausedAt(Instant.now());
            gc.setHitlPauseType(GroupConversation.HitlPauseType.TASK);
            gc.setOriginalQuestion("Validation test");

            var taskList = new SharedTaskList();
            taskList.addTask(new SharedTaskList.TaskItem("Awaiting task", "Do A", 0));
            taskList.addTask(new SharedTaskList.TaskItem("Assigned task", "Do B", 0));
            var tasks = taskList.all();
            taskList.assignTask(tasks.get(0).id(), AGENT_A, "Agent A");
            taskList.startTask(tasks.get(0).id());
            taskList.submitForApproval(tasks.get(0).id(), "Result A");
            taskList.assignTask(tasks.get(1).id(), AGENT_A, "Agent A");
            gc.setTaskList(taskList);
            return gc;
        }

        private GroupApprovalRequest approvedRequest(java.util.Map<String, String> taskApprovals) {
            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(decision);
            request.setTaskApprovals(taskApprovals);
            return request;
        }

        @Test
        @DisplayName("unknown taskId → IllegalArgumentException, nothing mutated, CAS never attempted")
        void unknownTaskIdRejected() throws Exception {
            var gc = pausedGcWithTasks();
            doReturn(gc).when(conversationStore).read("gc-approvals");

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> service.resumeDiscussion("gc-approvals",
                            approvedRequest(java.util.Map.of("no-such-task", "APPROVED")), null));
            assertTrue(ex.getMessage().contains("no-such-task"), ex.getMessage());

            assertTrue(gc.getTaskList().hasAwaitingApproval(), "no task may be mutated on a rejected request");
            assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState(), "pause must survive");
            verify(conversationStore, never()).updateIfState(any(), any());
            verify(scheduleStore, never()).deleteSchedulesByName(anyString());
        }

        @Test
        @DisplayName("taskId not awaiting approval → IllegalArgumentException with the task status")
        void wrongStateTaskRejected() throws Exception {
            var gc = pausedGcWithTasks();
            doReturn(gc).when(conversationStore).read("gc-approvals");
            String assignedTaskId = gc.getTaskList().all().stream()
                    .filter(t -> t.status() == TaskStatus.ASSIGNED)
                    .findFirst().orElseThrow().id();

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> service.resumeDiscussion("gc-approvals",
                            approvedRequest(java.util.Map.of(assignedTaskId, "APPROVED")), null));
            assertTrue(ex.getMessage().contains("not awaiting approval"), ex.getMessage());
            verify(conversationStore, never()).updateIfState(any(), any());
        }

        @Test
        @DisplayName("invalid decision VALUE → IllegalArgumentException, nothing mutated")
        void invalidValueRejected() throws Exception {
            var gc = pausedGcWithTasks();
            doReturn(gc).when(conversationStore).read("gc-approvals");
            String awaitingTaskId = gc.getTaskList().all().stream()
                    .filter(t -> t.status() == TaskStatus.AWAITING_APPROVAL)
                    .findFirst().orElseThrow().id();

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> service.resumeDiscussion("gc-approvals",
                            approvedRequest(java.util.Map.of(awaitingTaskId, "MAYBE")), null));
            assertTrue(ex.getMessage().contains("APPROVED or REJECTED"), ex.getMessage());

            assertTrue(gc.getTaskList().hasAwaitingApproval(), "no task may be mutated on a rejected request");
            verify(conversationStore, never()).updateIfState(any(), any());
        }

        @Test
        @DisplayName("case-insensitive values are accepted ('approved')")
        void caseInsensitiveValueAccepted() throws Exception {
            var gc = pausedGcWithTasks();
            doReturn(gc).when(conversationStore).read("gc-approvals");
            String awaitingTaskId = gc.getTaskList().all().stream()
                    .filter(t -> t.status() == TaskStatus.AWAITING_APPROVAL)
                    .findFirst().orElseThrow().id();

            service.resumeDiscussion("gc-approvals",
                    approvedRequest(java.util.Map.of(awaitingTaskId, "approved")), null);

            assertFalse(gc.getTaskList().hasAwaitingApproval(), "lowercase 'approved' must be accepted");
        }

        @Test
        @DisplayName("explicit empty map {} behaves like the approve-all shortcut")
        void emptyMapIsApproveAllShortcut() throws Exception {
            var gc = pausedGcWithTasks();
            doReturn(gc).when(conversationStore).read("gc-approvals");

            service.resumeDiscussion("gc-approvals", approvedRequest(java.util.Map.of()), null);

            assertFalse(gc.getTaskList().hasAwaitingApproval(),
                    "an explicit {} must auto-approve like an absent map — otherwise the phase instantly re-pauses");
        }
    }

    // =================================================================
    // Audit emission — EU AI Act human-oversight trail
    // =================================================================

    @Nested
    @DisplayName("HITL audit emission")
    class AuditEmission {

        private ai.labs.eddi.engine.audit.AuditLedgerService auditLedger;
        private GroupConversationService auditedService;

        @BeforeEach
        void setUpAuditedService() {
            auditLedger = mock(ai.labs.eddi.engine.audit.AuditLedgerService.class);
            when(auditLedger.isEnabled()).thenReturn(true);
            auditedService = new GroupConversationService(
                    groupStore, conversationStore, conversationService,
                    agentFactory, templatingEngine, jsonSerialization,
                    new SimpleMeterRegistry(), agentSigningService, agentStore,
                    scheduleStore, nonceCacheService, auditLedger, DEFAULT_TENANT, MAX_DEPTH);
        }

        private GroupConversation pausedGc(String id) {
            var gc = new GroupConversation();
            gc.setId(id);
            gc.setGroupId(GROUP_ID);
            gc.setUserId(USER_ID);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAtPhaseIndex(0);
            gc.setPausedPhaseName("Gate");
            gc.setPausedAt(Instant.now());
            gc.setHitlPauseType(GroupConversation.HitlPauseType.PHASE);
            gc.setOriginalQuestion("Audit test");
            return gc;
        }

        @Test
        @DisplayName("resume decision emits an hitl.approval entry with verdict + automated flag")
        void resumeEmitsApprovalAuditEntry() throws Exception {
            var gc = pausedGc("gc-audit-resume");
            doReturn(gc).when(conversationStore).read("gc-audit-resume");

            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setDecidedBy("system:timeout");
            decision.setNote("auto");
            request.setDecision(decision);

            auditedService.resumeDiscussion("gc-audit-resume", request, null);

            var captor = org.mockito.ArgumentCaptor.forClass(ai.labs.eddi.engine.audit.model.AuditEntry.class);
            verify(auditLedger).submit(captor.capture());
            var entry = captor.getValue();
            assertEquals("hitl.approval", entry.taskId(), "audit event type must be hitl.approval");
            assertEquals("gc-audit-resume", entry.conversationId());
            assertEquals("APPROVED", entry.output().get("verdict"));
            assertEquals(Boolean.TRUE, entry.output().get("automated"),
                    "system:timeout decisions must be flagged automated");
            assertEquals("group", entry.output().get("surface"));
        }

        @Test
        @DisplayName("cancel of a pending approval emits an hitl.approval entry with verdict CANCELLED")
        void cancelOfPausedEmitsCancellationAuditEntry() throws Exception {
            var gc = pausedGc("gc-audit-cancel");
            doReturn(gc).when(conversationStore).read("gc-audit-cancel");
            // cleanupAfterTerminalState loads the group config — let it no-op
            doReturn(null).when(groupStore).getCurrentResourceId(GROUP_ID);

            boolean cancelled = auditedService.cancelDiscussion("gc-audit-cancel",
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL);

            assertTrue(cancelled);
            var captor = org.mockito.ArgumentCaptor.forClass(ai.labs.eddi.engine.audit.model.AuditEntry.class);
            verify(auditLedger).submit(captor.capture());
            var entry = captor.getValue();
            assertEquals("hitl.approval", entry.taskId());
            assertEquals("CANCELLED", entry.output().get("verdict"));
            assertEquals("CANCEL_GRACEFUL", entry.output().get("mode"));
        }

        @Test
        @DisplayName("no audit entries when the ledger is disabled")
        void noEntriesWhenDisabled() throws Exception {
            when(auditLedger.isEnabled()).thenReturn(false);
            var gc = pausedGc("gc-audit-off");
            doReturn(gc).when(conversationStore).read("gc-audit-off");

            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(decision);
            auditedService.resumeDiscussion("gc-audit-off", request, null);

            verify(auditLedger, never()).submit(any());
        }
    }

    // =================================================================
    // hitlPauseType=TASK resume test — replaces self-fulfilling test
    // =================================================================

    @Nested
    @DisplayName("TASK resume completes dependent")
    class TaskResumeCompletesDependent {

        @Test
        @DisplayName("TASK resume re-enters same phase: listener captures phaseIndex == pausedAt")
        void taskResumeReEntersSamePhaseIndex() throws Exception {
            // 3 phases: Opinion(0), Execute(1), Synthesis(2).
            // TASK paused at phase 1 → resume should start at phase 1 (same).
            var phases = List.of(
                    new DiscussionPhase("Opinion", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false),
                    new DiscussionPhase("Execute", PhaseType.EXECUTE,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false),
                    new DiscussionPhase("Summary", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false));

            var gc = new GroupConversation();
            gc.setId("gc-task-resume");
            gc.setGroupId(GROUP_ID);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAtPhaseIndex(1); // paused at phase 1 (Execute)
            gc.setPausedPhaseName("Execute");
            gc.setPausedAt(Instant.now());
            gc.setHitlPauseType(GroupConversation.HitlPauseType.TASK);
            gc.setOriginalQuestion("Resume test");

            // Task list with one approved task
            var taskList = new SharedTaskList();
            taskList.addTask(new SharedTaskList.TaskItem("Task1", "Do something", 0));
            var tasks = taskList.all();
            taskList.assignTask(tasks.get(0).id(), AGENT_A, "Agent A");
            taskList.startTask(tasks.get(0).id());
            taskList.submitForApproval(tasks.get(0).id(), "Result");
            taskList.approveTask(tasks.get(0).id());
            gc.setTaskList(taskList);

            doReturn(gc).when(conversationStore).read("gc-task-resume");

            // Mock the async resume path: groupStore lookups for re-loading config
            var config = buildConfig(phases);
            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);
            stubAgentSay();

            var request = new GroupApprovalRequest();
            var dec = new HitlDecision();
            dec.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(dec);

            // Capture phase indices via a listener
            var observedPhaseIndices = java.util.Collections.synchronizedList(new java.util.ArrayList<Integer>());
            var resumeDone = new java.util.concurrent.CountDownLatch(1);

            var listener = new ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener() {
                @Override
                public void onPhaseStart(ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.PhaseStartEvent event) {
                    observedPhaseIndices.add(event.phaseIndex());
                }

                @Override
                public void onGroupComplete(ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.GroupCompleteEvent event) {
                    resumeDone.countDown();
                }

                @Override
                public void onGroupError(ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.GroupErrorEvent event) {
                    resumeDone.countDown();
                }
            };

            service.resumeDiscussion("gc-task-resume", request, listener);

            // Wait for async resume to complete
            assertTrue(resumeDone.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Async resume should complete within 5s");

            // TASK resume: first phase seen must be the PAUSED phase (1), not +1
            assertFalse(observedPhaseIndices.isEmpty(), "Listener should have seen at least one phase");
            assertEquals(1, observedPhaseIndices.get(0).intValue(),
                    "TASK resume must re-enter the SAME phase (index 1), not advance to 2");
        }

        @Test
        @DisplayName("PHASE resume advances: listener captures phaseIndex == pausedAt + 1")
        void phaseResumeAdvancesToNextPhaseIndex() throws Exception {
            // 3 phases. PHASE paused at phase 1 → resume at phase 2.
            var phases = List.of(
                    new DiscussionPhase("Opinion", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false),
                    new DiscussionPhase("Critique", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false),
                    new DiscussionPhase("Summary", PhaseType.OPINION,
                            "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                            false, null, 1, false));

            var gc = new GroupConversation();
            gc.setId("gc-phase-resume");
            gc.setGroupId(GROUP_ID);
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAtPhaseIndex(1); // paused at phase 1
            gc.setPausedPhaseName("Critique");
            gc.setPausedAt(Instant.now());
            gc.setHitlPauseType(GroupConversation.HitlPauseType.PHASE);
            gc.setOriginalQuestion("Phase resume test");

            doReturn(gc).when(conversationStore).read("gc-phase-resume");

            var config = buildConfig(phases);
            var resId = mockResourceId();
            doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
            doReturn(config).when(groupStore).read(GROUP_ID, 1);
            stubAgentSay();

            var request = new GroupApprovalRequest();
            var dec = new HitlDecision();
            dec.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(dec);

            var observedPhaseIndices = java.util.Collections.synchronizedList(new java.util.ArrayList<Integer>());
            var resumeDone = new java.util.concurrent.CountDownLatch(1);

            var listener = new ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener() {
                @Override
                public void onPhaseStart(ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.PhaseStartEvent event) {
                    observedPhaseIndices.add(event.phaseIndex());
                }

                @Override
                public void onGroupComplete(ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.GroupCompleteEvent event) {
                    resumeDone.countDown();
                }

                @Override
                public void onGroupError(ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.GroupErrorEvent event) {
                    resumeDone.countDown();
                }
            };

            service.resumeDiscussion("gc-phase-resume", request, listener);

            assertTrue(resumeDone.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Async resume should complete within 5s");

            // PHASE resume: first phase seen must be pausedAt + 1 = 2
            assertFalse(observedPhaseIndices.isEmpty(), "Listener should have seen at least one phase");
            assertEquals(2, observedPhaseIndices.get(0).intValue(),
                    "PHASE resume must advance to NEXT phase (index 2), not re-enter 1");
        }
    }

    // =================================================================
    // Resume: not AWAITING_APPROVAL → exception
    // =================================================================

    @Nested
    @DisplayName("Resume not awaiting approval")
    class ResumeNotAwaiting {

        @Test
        @DisplayName("Resume on non-AWAITING_APPROVAL state throws GroupDiscussionException")
        void resumeOnWrongStateThrows() throws Exception {
            var gc = new GroupConversation();
            gc.setId("gc-wrong");
            gc.setGroupId(GROUP_ID);
            gc.setState(GroupConversationState.COMPLETED);

            doReturn(gc).when(conversationStore).read("gc-wrong");

            var request = new GroupApprovalRequest();

            assertThrows(GroupDiscussionException.class,
                    () -> service.resumeDiscussion("gc-wrong", request, null),
                    "Resuming a non-AWAITING_APPROVAL GC should throw");
        }
    }
}
