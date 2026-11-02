/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.continuation;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConversationContinuationsTest {

    private static final String CONVERSATION_ID = "0123456789abcdef89abcdef";
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private IScheduleStore scheduleStore;
    private SimpleMeterRegistry meters;
    private ConversationContinuations continuations;

    @BeforeEach
    void setUp() {
        scheduleStore = mock(IScheduleStore.class);
        meters = new SimpleMeterRegistry();
        continuations = new ConversationContinuations(scheduleStore, meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A settled turn on the current step that emitted the given actions. */
    private static ConversationMemory memoryWith(ContinuationPolicy policy, String... actions) {
        var memory = new ConversationMemory(CONVERSATION_ID, "agent-1", 1, "user-1");
        memory.setContinuationPolicy(policy);
        memory.setConversationState(ConversationState.READY);
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ACTIONS.key(), new ArrayList<>(List.of(actions))));
        return memory;
    }

    /**
     * Appends {@code count} earlier steps, each marked as a continuation that ran.
     */
    private static void ranBefore(ConversationMemory memory, int count) {
        // The current step becomes history; a fresh current step keeps the actions.
        var actions = memory.getCurrentStep().getLatestData(MemoryKeys.ACTIONS);
        for (int i = 1; i <= count; i++) {
            memory.getCurrentStep().storeData(new Data<>(ConversationContinuations.STEP_NUMBER, i));
            memory.startNextStep();
        }
        if (actions != null) {
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ACTIONS.key(), actions.getResult()));
        }
    }

    private double refused(String reason) {
        var counter = meters.find(ConversationContinuations.METRIC_REFUSED).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private static Object stepValue(IConversationMemory memory, String key) {
        var data = memory.getCurrentStep().getData(key);
        return data == null ? null : data.getResult();
    }

    @Nested
    @DisplayName("decide — before the turn is persisted")
    class Decide {

        @Test
        @DisplayName("a turn that did not ask to continue decides nothing and records nothing")
        void notRequested() {
            var memory = memoryWith(new ContinuationPolicy(5, ""), "greet");

            assertNull(continuations.decide(memory));
            assertNull(stepValue(memory, ConversationContinuations.STEP_NEXT));
            assertNull(stepValue(memory, ConversationContinuations.STEP_REFUSED));
        }

        @Test
        @DisplayName("default off: maxContinuations unset ignores the action, with the reason on the step and a refused count")
        void defaultOff() {
            var memory = memoryWith(ContinuationPolicy.OFF, IConversation.CONTINUE_CONVERSATION);

            assertNull(continuations.decide(memory));
            assertEquals(ConversationContinuations.REASON_DISABLED, stepValue(memory, ConversationContinuations.STEP_REFUSED));
            assertNull(stepValue(memory, ConversationContinuations.STEP_NEXT));
            assertEquals(1.0, refused(ConversationContinuations.REASON_DISABLED));
        }

        @Test
        @DisplayName("the first continuation is number 1")
        void firstContinuation() {
            var memory = memoryWith(new ContinuationPolicy(3, ""), IConversation.CONTINUE_CONVERSATION);

            assertEquals(1, continuations.decide(memory));
            assertEquals(1, stepValue(memory, ConversationContinuations.STEP_NEXT));
        }

        @Test
        @DisplayName("maxContinuations bounds the conversation: the last allowed one is scheduled, the next is refused")
        void boundIsInclusive() {
            var belowLimit = memoryWith(new ContinuationPolicy(3, ""), IConversation.CONTINUE_CONVERSATION);
            ranBefore(belowLimit, 2);
            assertEquals(3, continuations.decide(belowLimit), "two ran, the third of three may still be scheduled");

            var atLimit = memoryWith(new ContinuationPolicy(3, ""), IConversation.CONTINUE_CONVERSATION);
            ranBefore(atLimit, 3);
            assertNull(continuations.decide(atLimit), "three of three ran: no fourth");
            assertEquals(ConversationContinuations.REASON_LIMIT, stepValue(atLimit, ConversationContinuations.STEP_REFUSED));
            assertEquals(1.0, refused(ConversationContinuations.REASON_LIMIT));
        }

        @Test
        @DisplayName("the count is the conversation's, across user turns in between")
        void countsAcrossTheConversation() {
            var memory = memoryWith(new ContinuationPolicy(2, ""), IConversation.CONTINUE_CONVERSATION);
            memory.getCurrentStep().storeData(new Data<>(ConversationContinuations.STEP_NUMBER, 1));
            memory.startNextStep(); // a user turn: no marker
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>(ConversationContinuations.STEP_NUMBER, 2));
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ACTIONS.key(), List.of(IConversation.CONTINUE_CONVERSATION)));

            assertEquals(2, ConversationContinuations.usedContinuations(memory));
            assertNull(continuations.decide(memory));
        }

        @Test
        @DisplayName("the cost ceiling refuses before anything is armed once the conversation's spend reached it")
        void costCeiling() {
            var memory = memoryWith(new ContinuationPolicy(10, ""), IConversation.CONTINUE_CONVERSATION);
            memory.setConversationCostCeilingUsd(1.0);
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.6));
            ranBefore(memory, 1);
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.4));

            assertNull(continuations.decide(memory), "0.6 + 0.4 across two turns reaches the 1.0 ceiling");
            assertEquals(ConversationContinuations.REASON_COST_CEILING, stepValue(memory, ConversationContinuations.STEP_REFUSED));
        }

        @Test
        @DisplayName("below the cost ceiling the continuation is scheduled")
        void belowCostCeiling() {
            var memory = memoryWith(new ContinuationPolicy(10, ""), IConversation.CONTINUE_CONVERSATION);
            memory.setConversationCostCeilingUsd(1.0);
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.99));

            assertEquals(1, continuations.decide(memory));
        }

        @Test
        @DisplayName("a turn that did not settle READY never continues (failed, ended, paused)")
        void notSettled() {
            for (ConversationState state : List.of(ConversationState.ERROR, ConversationState.ENDED, ConversationState.AWAITING_HUMAN,
                    ConversationState.EXECUTION_INTERRUPTED)) {
                var memory = memoryWith(new ContinuationPolicy(5, ""), IConversation.CONTINUE_CONVERSATION);
                memory.setConversationState(state);
                assertNull(continuations.decide(memory), state.name());
                assertEquals(ConversationContinuations.REASON_NOT_SETTLED, stepValue(memory, ConversationContinuations.STEP_REFUSED));
            }
        }

        @Test
        @DisplayName("a group member's turn does not continue itself: the group orchestrates its members")
        void groupMember() {
            var memory = memoryWith(new ContinuationPolicy(5, ""), IConversation.CONTINUE_CONVERSATION);
            memory.getCurrentStep().storeData(new Data<>("context:groupConversationId", new Context(Context.ContextType.string, "gc-1")));

            assertNull(continuations.decide(memory));
            assertEquals(ConversationContinuations.REASON_GROUP_MEMBER, stepValue(memory, ConversationContinuations.STEP_REFUSED));
        }
    }

    @Nested
    @DisplayName("armIfDecided — after the turn was committed")
    class Arm {

        @Test
        @DisplayName("arms one one-shot schedule due now, carrying the conversation, the number, the input and the owner")
        void armsTheDecidedContinuation() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, "next phase"), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);

            assertTrue(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createSchedule(captor.capture());
            ScheduleConfiguration schedule = captor.getValue();
            assertEquals(ConversationContinuations.scheduleName(CONVERSATION_ID), schedule.getName());
            assertEquals(NOW, schedule.getNextFire());
            assertEquals(NOW.toString(), schedule.getOneTimeAt());
            assertEquals("agent-1", schedule.getAgentId());
            assertEquals("user-1", schedule.getUserId(), "erasure deletes a user's schedules by userId");
            assertEquals("production", schedule.getEnvironment());
            assertEquals("next phase", schedule.getMessage());
            assertTrue(ConversationContinuations.isContinuationSchedule(schedule.getMetadata()));
            assertEquals(CONVERSATION_ID, schedule.getMetadata().get(ConversationContinuations.METADATA_CONVERSATION_ID_KEY));
            assertEquals(1, schedule.getMetadata().get(ConversationContinuations.METADATA_NUMBER_KEY));
            assertEquals(4, schedule.getMetadata().get(ConversationContinuations.METADATA_MAX_KEY));
            assertEquals(1.0, meters.find(ConversationContinuations.METRIC_SCHEDULED).counter().count());
        }

        @Test
        @DisplayName("nothing is armed for a turn that did not decide to continue")
        void nothingDecided() throws Exception {
            var memory = memoryWith(ContinuationPolicy.OFF, IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);

            assertFalse(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            verify(scheduleStore, never()).createSchedule(any());
            verify(scheduleStore, never()).deleteUsedUpOneShotsByName(anyString());
        }

        @Test
        @DisplayName("arming prunes the conversation's used-up continuations before it writes the next one")
        void armPrunesBeforeItWrites() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);

            assertTrue(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));

            var order = inOrder(scheduleStore);
            order.verify(scheduleStore).deleteUsedUpOneShotsByName(ConversationContinuations.scheduleName(CONVERSATION_ID));
            order.verify(scheduleStore).createSchedule(any());
        }

        @Test
        @DisplayName("a prune that fails is logged and the continuation is still armed")
        void pruneFailureStillArms() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).deleteUsedUpOneShotsByName(anyString());

            assertTrue(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            verify(scheduleStore).createSchedule(any());
            assertEquals(1.0, meters.find(ConversationContinuations.METRIC_SCHEDULED).counter().count());
        }

        @Test
        @DisplayName("the last continuation turn of a job arms nothing but still prunes, so the one before it does not stay behind")
        void lastContinuationTurnPrunes() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(2, ""), IConversation.CONTINUE_CONVERSATION);
            ranBefore(memory, 1);
            memory.getCurrentStep().storeData(new Data<>(ConversationContinuations.STEP_NUMBER, 2));
            continuations.decide(memory); // refused: limit

            assertFalse(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            verify(scheduleStore).deleteUsedUpOneShotsByName(ConversationContinuations.scheduleName(CONVERSATION_ID));
            verify(scheduleStore, never()).createSchedule(any());
        }

        @Test
        @DisplayName("a schedule that cannot be written is counted and logged; it never throws into the committed turn")
        void storeFailure() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).createSchedule(any());

            assertFalse(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            assertEquals(1.0, refused(ConversationContinuations.REASON_SCHEDULE_FAILED));
            verify(scheduleStore, times(ConversationContinuations.ARM_ATTEMPTS)).createSchedule(any());
        }

        @Test
        @DisplayName("a write that fails for a moment is retried, and the continuation is armed")
        void transientStoreFailure() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doThrow(new IResourceStore.ResourceStoreException("blip")).doReturn("s1").when(scheduleStore).createSchedule(any());

            assertTrue(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            assertEquals(0.0, refused(ConversationContinuations.REASON_SCHEDULE_FAILED));
            verify(scheduleStore, times(2)).createSchedule(any());
        }

        @Test
        @DisplayName("an interrupted retry pause gives up, keeps the interrupt and still reports schedule_failed")
        void interruptedBackoff() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).createSchedule(any());

            Thread.currentThread().interrupt();
            try {
                assertFalse(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt survives the retry pause");
            } finally {
                Thread.interrupted();
            }
            assertEquals(1.0, refused(ConversationContinuations.REASON_SCHEDULE_FAILED));
            verify(scheduleStore, times(1)).createSchedule(any());
        }

        @Test
        @DisplayName("a turn cancelled before arming queues no successor")
        void cancelledBeforeArming() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            memory.setCancelled(true);

            assertFalse(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            verify(scheduleStore, never()).createSchedule(any());
            assertEquals(1.0, refused(ConversationContinuations.REASON_INTERRUPTED));
        }

        @Test
        @DisplayName("a cancel that lands while the schedule is written withdraws it again")
        void cancelledWhileArming() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doAnswer(inv -> {
                memory.setCancelled(true);
                return "s1";
            }).when(scheduleStore).createSchedule(any());

            assertFalse(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            verify(scheduleStore).deleteSchedulesByName(ConversationContinuations.scheduleName(CONVERSATION_ID));
            assertEquals(1.0, refused(ConversationContinuations.REASON_INTERRUPTED));
        }

        @Test
        @DisplayName("disarm withdraws every pending continuation of the conversation, by name, and never throws")
        void disarm() throws Exception {
            doReturn(1).when(scheduleStore).countPendingSchedulesByName(ConversationContinuations.scheduleName(CONVERSATION_ID));
            doReturn(1).when(scheduleStore).deleteSchedulesByName(ConversationContinuations.scheduleName(CONVERSATION_ID));
            assertEquals(1, continuations.disarm(CONVERSATION_ID));

            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).deleteSchedulesByName(anyString());
            assertEquals(0, continuations.disarm(CONVERSATION_ID));
        }

        @Test
        @DisplayName("disarm deletes the spent continuations of a finished job but reports nothing withdrawn (found by ConversationContinuationIT)")
        void disarmOfAFinishedJobWithdrawsNothing() throws Exception {
            // A fired one-shot is disabled, not deleted: a job that ran three
            // continuations leaves three disabled rows and nothing pending. Counting the
            // deleted rows made a cancel of the finished job answer CANCELLED, not 409.
            String name = ConversationContinuations.scheduleName(CONVERSATION_ID);
            doReturn(0).when(scheduleStore).countPendingSchedulesByName(name);
            doReturn(3).when(scheduleStore).deleteSchedulesByName(name);

            assertEquals(0, continuations.disarm(CONVERSATION_ID));
            verify(scheduleStore).deleteSchedulesByName(name);
        }

        @Test
        @DisplayName("disarm still deletes when the pending count fails, and reports nothing withdrawn")
        void disarmDeletesWhenTheCountFails() throws Exception {
            String name = ConversationContinuations.scheduleName(CONVERSATION_ID);
            doThrow(new IResourceStore.ResourceStoreException("count down")).when(scheduleStore).countPendingSchedulesByName(name);
            doReturn(2).when(scheduleStore).deleteSchedulesByName(name);

            assertEquals(0, continuations.disarm(CONVERSATION_ID));
            verify(scheduleStore).deleteSchedulesByName(name);
        }
    }

    @Nested
    @DisplayName("the once-only marker, through the persistence boundary")
    class Marker {

        @Test
        @DisplayName("a continuation turn's marker survives the conversion to the stored document and is found there")
        void markerRoundTrips() {
            var memory = new ConversationMemory(CONVERSATION_ID, "agent-1", 1, "user-1");
            var input = new InputData("", new HashMap<>());
            input.setContinuationNumber(7);
            ConversationContinuations.recordContinuationTurn(memory.getCurrentStep(), input);

            ConversationMemorySnapshot stored = ConversationMemoryUtilities.convertConversationMemory(memory);
            assertTrue(ConversationContinuations.ranIn(stored, 7));
            assertFalse(ConversationContinuations.ranIn(stored, 8));

            // ...and is counted again once loaded back.
            IConversationMemory loaded = ConversationMemoryUtilities.convertConversationMemorySnapshot(stored);
            assertEquals(1, ConversationContinuations.usedContinuations(loaded));
        }

        @Test
        @DisplayName("an ordinary turn is never marked, whatever its client sent")
        void ordinaryTurnNotMarked() {
            var memory = new ConversationMemory(CONVERSATION_ID, "agent-1", 1, "user-1");
            ConversationContinuations.recordContinuationTurn(memory.getCurrentStep(), new InputData("hi", new HashMap<>()));

            assertEquals(0, ConversationContinuations.usedContinuations(memory));
            assertFalse(ConversationContinuations.ranIn(ConversationMemoryUtilities.convertConversationMemory(memory), 1));
        }

        @Test
        @DisplayName("the schedule marker is recognised only by its own type")
        void scheduleMarker() {
            assertTrue(ConversationContinuations.isContinuationSchedule(ConversationContinuations.metadata(CONVERSATION_ID, 1, 2)));
            assertFalse(ConversationContinuations.isContinuationSchedule(null));
            var other = new HashMap<String, Object>();
            other.put(ConversationContinuations.METADATA_TYPE_KEY, "something_else");
            assertFalse(ConversationContinuations.isContinuationSchedule(other));
        }
    }
}
