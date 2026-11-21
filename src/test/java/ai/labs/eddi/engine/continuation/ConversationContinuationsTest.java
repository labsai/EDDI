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
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingContinuation;
import ai.labs.eddi.engine.memory.model.PendingContinuationCandidate;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.userevents.UserEvent;
import ai.labs.eddi.engine.userevents.UserEventAudience;
import ai.labs.eddi.engine.userevents.UserEventPublisher;
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
import java.util.UUID;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
        try {
            // Both real stores answer true when they wrote the row.
            doReturn(true).when(scheduleStore).createScheduleIfAbsent(any());
        } catch (IResourceStore.ResourceStoreException e) {
            throw new IllegalStateException(e);
        }
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
            verify(scheduleStore).createScheduleIfAbsent(captor.capture());
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
            verify(scheduleStore, never()).createScheduleIfAbsent(any());
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
            order.verify(scheduleStore).createScheduleIfAbsent(any());
        }

        @Test
        @DisplayName("a prune that fails is logged and the continuation is still armed")
        void pruneFailureStillArms() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).deleteUsedUpOneShotsByName(anyString());

            assertTrue(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            verify(scheduleStore).createScheduleIfAbsent(any());
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
            verify(scheduleStore, never()).createScheduleIfAbsent(any());
        }

        @Test
        @DisplayName("the last continuation turn of a job tells its owner the job finished, and why it stopped")
        void lastContinuationTurnAnnouncesFinished() {
            var publisher = mock(UserEventPublisher.class);
            continuations.userEventPublisher = publisher;
            var memory = memoryWith(new ContinuationPolicy(2, ""), IConversation.CONTINUE_CONVERSATION);
            ranBefore(memory, 1);
            memory.getCurrentStep().storeData(new Data<>(ConversationContinuations.STEP_NUMBER, 2));
            continuations.decide(memory); // refused: limit

            continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production);

            var event = ArgumentCaptor.forClass(UserEvent.class);
            var audience = ArgumentCaptor.forClass(UserEventAudience.class);
            verify(publisher).publish(event.capture(), audience.capture());
            assertEquals(UserEvent.KIND_JOB_FINISHED, event.getValue().kind());
            assertEquals(CONVERSATION_ID, event.getValue().conversationId());
            assertEquals(UserEvent.JOB_CONTINUATION, event.getValue().jobKind());
            assertEquals(ConversationContinuations.REASON_LIMIT, event.getValue().state());
            assertEquals(UserEventAudience.principal("user-1"), audience.getValue());
        }

        @Test
        @DisplayName("a turn that arms its successor, or is no continuation at all, announces nothing")
        void runningJobAnnouncesNothing() {
            var publisher = mock(UserEventPublisher.class);
            continuations.userEventPublisher = publisher;
            var armed = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(armed);
            continuations.armIfDecided(CONVERSATION_ID, armed, Environment.production);
            var plain = memoryWith(ContinuationPolicy.OFF);
            continuations.armIfDecided(CONVERSATION_ID, plain, Environment.production);

            verify(publisher, never()).publish(any(), any());
        }

        @Test
        @DisplayName("a schedule that cannot be written is counted and logged; it never throws into the committed turn")
        void storeFailure() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).createScheduleIfAbsent(any());

            assertFalse(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            assertEquals(1.0, refused(ConversationContinuations.REASON_SCHEDULE_FAILED));
            verify(scheduleStore, times(ConversationContinuations.ARM_ATTEMPTS)).createScheduleIfAbsent(any());
        }

        @Test
        @DisplayName("a schedule that cannot be written ends the job, and its owner hears so: job.finished with schedule_failed")
        void storeFailureAnnouncesFinished() throws Exception {
            var publisher = mock(UserEventPublisher.class);
            continuations.userEventPublisher = publisher;
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).createScheduleIfAbsent(any());

            assertFalse(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));

            var event = ArgumentCaptor.forClass(UserEvent.class);
            var audience = ArgumentCaptor.forClass(UserEventAudience.class);
            verify(publisher).publish(event.capture(), audience.capture());
            assertEquals(UserEvent.KIND_JOB_FINISHED, event.getValue().kind());
            assertEquals(ConversationContinuations.REASON_SCHEDULE_FAILED, event.getValue().state());
            assertEquals(UserEventAudience.principal("user-1"), audience.getValue());
        }

        @Test
        @DisplayName("with the recovery sweep on, a failed write is not the end of the job: no job.finished, the sweep arms it")
        void storeFailureLeftToTheSweep() throws Exception {
            var publisher = mock(UserEventPublisher.class);
            var withStore = new ConversationContinuations(scheduleStore, mock(IConversationMemoryStore.class), meters,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            withStore.userEventPublisher = publisher;
            withStore.recoveryEnabled = true;
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            withStore.decide(memory, Environment.production);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).createScheduleIfAbsent(any());

            assertFalse(withStore.armIfDecided(CONVERSATION_ID, memory, Environment.production));

            assertEquals(1.0, refused(ConversationContinuations.REASON_SCHEDULE_FAILED), "the failed write is still counted");
            assertNotNull(memory.getPendingContinuation(), "the decision the sweep arms from is still there");
            verify(publisher, never()).publish(any(), any());
        }

        @Test
        @DisplayName("with the recovery sweep switched off, a failed write retires the decision first, then ends the job and says so")
        void storeFailureWithRecoveryOff() throws Exception {
            var publisher = mock(UserEventPublisher.class);
            var memoryStore = mock(IConversationMemoryStore.class);
            var withStore = new ConversationContinuations(scheduleStore, memoryStore, meters, Clock.fixed(NOW, ZoneOffset.UTC));
            withStore.userEventPublisher = publisher;
            withStore.recoveryEnabled = false;
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            withStore.decide(memory, Environment.production);
            String decision = (String) stepValue(memory, ConversationContinuations.STEP_DECISION);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).createScheduleIfAbsent(any());

            withStore.armIfDecided(CONVERSATION_ID, memory, Environment.production);

            var event = ArgumentCaptor.forClass(UserEvent.class);
            var order = inOrder(memoryStore, publisher);
            // Retired before the owner is told the job ended: a sweep switched on later
            // must not arm a job its owner heard had finished.
            order.verify(memoryStore).clearPendingContinuation(CONVERSATION_ID, 1, decision);
            order.verify(publisher).publish(event.capture(), any());
            assertEquals(ConversationContinuations.REASON_SCHEDULE_FAILED, event.getValue().state());
        }

        @Test
        @DisplayName("with the sweep off, a decision that can neither be armed nor retired is not announced as ended")
        void storeFailureWithRecoveryOffAndAFailedRetire() throws Exception {
            var publisher = mock(UserEventPublisher.class);
            var memoryStore = mock(IConversationMemoryStore.class);
            var withStore = new ConversationContinuations(scheduleStore, memoryStore, meters, Clock.fixed(NOW, ZoneOffset.UTC));
            withStore.userEventPublisher = publisher;
            withStore.recoveryEnabled = false;
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            withStore.decide(memory, Environment.production);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).createScheduleIfAbsent(any());
            doThrow(new IResourceStore.ResourceStoreException("down")).when(memoryStore).clearPendingContinuation(anyString(), any(), any());

            assertFalse(withStore.armIfDecided(CONVERSATION_ID, memory, Environment.production));

            // Every retire attempt failed, so the decision is still armable by a sweep
            // switched on later: the owner must not hear that the job finished.
            verify(memoryStore, times(ConversationContinuations.ARM_ATTEMPTS)).clearPendingContinuation(anyString(), any(), any());
            verify(publisher, never()).publish(any(), any());
        }

        @Test
        @DisplayName("a write that fails for a moment is retried, and the continuation is armed")
        void transientStoreFailure() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doThrow(new IResourceStore.ResourceStoreException("blip")).doReturn(true).when(scheduleStore).createScheduleIfAbsent(any());

            assertTrue(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            assertEquals(0.0, refused(ConversationContinuations.REASON_SCHEDULE_FAILED));
            verify(scheduleStore, times(2)).createScheduleIfAbsent(any());
        }

        @Test
        @DisplayName("an interrupted retry pause gives up, keeps the interrupt and still reports schedule_failed")
        void interruptedBackoff() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).createScheduleIfAbsent(any());

            Thread.currentThread().interrupt();
            try {
                assertFalse(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt survives the retry pause");
            } finally {
                Thread.interrupted();
            }
            assertEquals(1.0, refused(ConversationContinuations.REASON_SCHEDULE_FAILED));
            verify(scheduleStore, times(1)).createScheduleIfAbsent(any());
        }

        @Test
        @DisplayName("a turn cancelled before arming queues no successor")
        void cancelledBeforeArming() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            memory.setCancelled(true);

            assertFalse(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            verify(scheduleStore, never()).createScheduleIfAbsent(any());
            assertEquals(1.0, refused(ConversationContinuations.REASON_INTERRUPTED));
        }

        @Test
        @DisplayName("a cancel that lands while the schedule is written withdraws it again")
        void cancelledWhileArming() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory);
            doAnswer(inv -> {
                memory.setCancelled(true);
                return true;
            }).when(scheduleStore).createScheduleIfAbsent(any());

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

    @Nested
    @DisplayName("outbox — the decision is stored with the turn (#1180)")
    class Outbox {

        @Test
        @DisplayName("an approved continuation leaves its outbox record on the memory: number, cap, input, environment, time")
        void decideRecordsThePendingContinuation() {
            var memory = memoryWith(new ContinuationPolicy(4, "next phase"), IConversation.CONTINUE_CONVERSATION);

            assertEquals(1, continuations.decide(memory, Environment.production));

            String decision = (String) stepValue(memory, ConversationContinuations.STEP_DECISION);
            assertNotNull(decision, "the step carries the id of its decision");
            assertEquals(new PendingContinuation(1, 4, "next phase", "production", NOW.toEpochMilli(), decision), memory.getPendingContinuation());
        }

        @Test
        @DisplayName("a refused or absent request records no outbox record, and clears one an earlier step left on the memory")
        void refusedRecordsNothing() {
            var memory = memoryWith(ContinuationPolicy.OFF, IConversation.CONTINUE_CONVERSATION);
            memory.setPendingContinuation(new PendingContinuation(7, 9, "", null, 1L));

            assertNull(continuations.decide(memory, Environment.production));
            assertNull(memory.getPendingContinuation());
        }

        @Test
        @DisplayName("a turn cancelled after its pipeline finished is refused before the store, so no record reaches the sweep")
        void cancelledBeforeTheStore() {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            memory.setCancelled(true);

            assertNull(continuations.decide(memory, Environment.production));
            assertEquals(ConversationContinuations.REASON_INTERRUPTED, stepValue(memory, ConversationContinuations.STEP_REFUSED));
            assertNull(memory.getPendingContinuation());
            assertNull(ConversationMemoryUtilities.convertConversationMemory(memory).getPendingContinuation());
        }

        @Test
        @DisplayName("the record round-trips through the snapshot, and the next committed step that does not continue drops it")
        void recordFollowsTheCurrentStep() {
            var memory = memoryWith(new ContinuationPolicy(4, "go"), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory, Environment.test);

            ConversationMemorySnapshot stored = ConversationMemoryUtilities.convertConversationMemory(memory);
            assertEquals(new PendingContinuation(1, 4, "go", "test", NOW.toEpochMilli(),
                    (String) stepValue(memory, ConversationContinuations.STEP_DECISION)), stored.getPendingContinuation());

            IConversationMemory loaded = ConversationMemoryUtilities.convertConversationMemorySnapshot(stored);
            assertEquals(stored.getPendingContinuation(), loaded.getPendingContinuation(), "a load keeps the record");
            assertEquals(stored.getPendingContinuation(), ConversationMemoryUtilities.convertConversationMemory(loaded).getPendingContinuation(),
                    "a re-store of the same step keeps it");

            ((ConversationMemory) loaded).startNextStep();
            assertNull(ConversationMemoryUtilities.convertConversationMemory(loaded).getPendingContinuation(),
                    "a later step that did not decide clears it by being written");
        }

        @Test
        @DisplayName("a record that was removed (cancel, end) stays removed when the same step is stored again, policy or not")
        void removedRecordIsNotRebuilt() {
            var memory = memoryWith(new ContinuationPolicy(4, "go"), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory, Environment.production);
            memory.setPendingContinuation(null);

            assertNull(ConversationContinuations.pendingOf(memory), "a stopped job must not be handed back to the sweep");
            assertNull(ConversationMemoryUtilities.convertConversationMemory(memory).getPendingContinuation());
        }

        @Test
        @DisplayName("a record that names another number than the step's decision is not carried, nor replaced by one built from the step")
        void staleRecordIsNotCarried() {
            var memory = memoryWith(new ContinuationPolicy(4, "go"), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory, Environment.production);
            memory.setPendingContinuation(new PendingContinuation(3, 4, "old", null, 5L));

            assertNull(ConversationContinuations.pendingOf(memory));
        }

        @Test
        @DisplayName("a record of another decision of the same number (an undo back to an earlier step) is not carried")
        void recordOfAnotherDecisionIsNotCarried() {
            var memory = memoryWith(new ContinuationPolicy(4, "go"), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory, Environment.production);
            PendingContinuation own = memory.getPendingContinuation();
            memory.setPendingContinuation(new PendingContinuation(1, 4, "go", "production", NOW.toEpochMilli(), "the-undone-turns-decision"));

            assertNull(ConversationContinuations.pendingOf(memory), "the undone turn's decision must not ride along with this step");
            memory.setPendingContinuation(own);
            assertEquals(own, ConversationContinuations.pendingOf(memory), "the step's own decision is carried");
        }

        @Test
        @DisplayName("the original arm writes under the derived id, and a row the sweep already wrote counts as armed")
        void armUsesTheDerivedId() throws Exception {
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(memory, Environment.production);
            doReturn(false).when(scheduleStore).createScheduleIfAbsent(any());

            assertTrue(continuations.armIfDecided(CONVERSATION_ID, memory, Environment.production));

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createScheduleIfAbsent(captor.capture());
            String decision = (String) stepValue(memory, ConversationContinuations.STEP_DECISION);
            assertEquals(ConversationContinuations.scheduleId(CONVERSATION_ID, 1, decision), captor.getValue().getId());
            assertEquals(decision, captor.getValue().getMetadata().get(ConversationContinuations.METADATA_DECISION_KEY));
            verify(scheduleStore, never()).createSchedule(any());
            verify(scheduleStore, never()).readSchedule(anyString());
        }

        @Test
        @DisplayName("a second decision of the same number writes a row of its own, never taking an earlier one's row for it")
        void reDecisionGetsItsOwnRow() throws Exception {
            var first = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(first, Environment.production);
            var second = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            continuations.decide(second, Environment.production);
            doReturn(true).when(scheduleStore).createScheduleIfAbsent(any());

            assertTrue(continuations.armIfDecided(CONVERSATION_ID, first, Environment.production));
            assertTrue(continuations.armIfDecided(CONVERSATION_ID, second, Environment.production));

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore, times(2)).createScheduleIfAbsent(captor.capture());
            assertNotEquals(captor.getAllValues().get(0).getId(), captor.getAllValues().get(1).getId(),
                    "a paused fire of the first decision must not count as the second one's schedule");
            verify(scheduleStore, never()).deleteSchedule(anyString());
        }

        @Test
        @DisplayName("the derived id is stable per continuation and differs between continuations and conversations")
        void derivedIds() {
            assertEquals(ConversationContinuations.scheduleId(CONVERSATION_ID, 2, "d"),
                    ConversationContinuations.scheduleId(CONVERSATION_ID, 2, "d"));
            assertNotEquals(ConversationContinuations.scheduleId(CONVERSATION_ID, 2, "d"),
                    ConversationContinuations.scheduleId(CONVERSATION_ID, 3, "d"));
            assertNotEquals(ConversationContinuations.scheduleId(CONVERSATION_ID, 2, "d"), ConversationContinuations.scheduleId("other", 2, "d"));
            assertNotEquals(ConversationContinuations.scheduleId(CONVERSATION_ID, 2, "d"),
                    ConversationContinuations.scheduleId(CONVERSATION_ID, 2, "e"),
                    "two decisions of one number are two schedules");
            assertDoesNotThrow(() -> UUID.fromString(ConversationContinuations.scheduleId(CONVERSATION_ID, 2, "d")));
        }

        @Test
        @DisplayName("a turn cancelled before arming removes its stored record, so the sweep cannot revive the job")
        void cancelledBeforeArmingClearsTheRecord() throws Exception {
            var memoryStore = mock(IConversationMemoryStore.class);
            var withStore = new ConversationContinuations(scheduleStore, memoryStore, meters, Clock.fixed(NOW, ZoneOffset.UTC));
            var memory = memoryWith(new ContinuationPolicy(4, ""), IConversation.CONTINUE_CONVERSATION);
            withStore.decide(memory, Environment.production);
            memory.setCancelled(true);

            assertFalse(withStore.armIfDecided(CONVERSATION_ID, memory, Environment.production));
            verify(memoryStore).clearPendingContinuation(CONVERSATION_ID, 1, (String) stepValue(memory, ConversationContinuations.STEP_DECISION));
        }

        @Test
        @DisplayName("disarm removes the record before it deletes the rows, and a lost arm's record counts as one withdrawn")
        void disarmClearsTheRecordFirst() throws Exception {
            var memoryStore = mock(IConversationMemoryStore.class);
            var withStore = new ConversationContinuations(scheduleStore, memoryStore, meters, Clock.fixed(NOW, ZoneOffset.UTC));
            String name = ConversationContinuations.scheduleName(CONVERSATION_ID);
            doReturn(true).when(memoryStore).clearPendingContinuation(CONVERSATION_ID, null, null);
            doReturn(0).when(scheduleStore).countPendingSchedulesByName(name);
            doReturn(0).when(scheduleStore).deleteSchedulesByName(name);

            assertEquals(1, withStore.disarm(CONVERSATION_ID), "the crashed job's continuation was pending all the same");

            var order = inOrder(memoryStore, scheduleStore);
            order.verify(memoryStore).clearPendingContinuation(CONVERSATION_ID, null, null);
            order.verify(scheduleStore).deleteSchedulesByName(name);
        }

        @Test
        @DisplayName("disarm of a conversation with neither a record nor a pending row withdraws nothing")
        void disarmWithNothingPending() throws Exception {
            var memoryStore = mock(IConversationMemoryStore.class);
            var withStore = new ConversationContinuations(scheduleStore, memoryStore, meters, Clock.fixed(NOW, ZoneOffset.UTC));
            doReturn(false).when(memoryStore).clearPendingContinuation(CONVERSATION_ID, null, null);

            assertEquals(0, withStore.disarm(CONVERSATION_ID));
        }

        @Test
        @DisplayName("a record that cannot be removed does not stop disarm from deleting the rows, but it reports the withdrawal failed")
        void disarmSurvivesARecordFailure() throws Exception {
            var memoryStore = mock(IConversationMemoryStore.class);
            var withStore = new ConversationContinuations(scheduleStore, memoryStore, meters, Clock.fixed(NOW, ZoneOffset.UTC));
            String name = ConversationContinuations.scheduleName(CONVERSATION_ID);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(memoryStore).clearPendingContinuation(CONVERSATION_ID, null, null);
            doReturn(1).when(scheduleStore).countPendingSchedulesByName(name);
            doReturn(1).when(scheduleStore).deleteSchedulesByName(name);

            assertEquals(ConversationContinuations.WITHDRAWAL_FAILED, withStore.disarm(CONVERSATION_ID),
                    "the stored decision is still there: a cancel must not be confirmed");
            verify(scheduleStore).deleteSchedulesByName(name);
        }

        @Test
        @DisplayName("a record removal that fails once is retried, so a cancel between two turns still takes the lost arm's record")
        void disarmRetriesTheRecordRemoval() throws Exception {
            var memoryStore = mock(IConversationMemoryStore.class);
            var withStore = new ConversationContinuations(scheduleStore, memoryStore, meters, Clock.fixed(NOW, ZoneOffset.UTC));
            String name = ConversationContinuations.scheduleName(CONVERSATION_ID);
            doThrow(new IResourceStore.ResourceStoreException("blip")).doReturn(true).when(memoryStore).clearPendingContinuation(CONVERSATION_ID,
                    null, null);
            doReturn(0).when(scheduleStore).countPendingSchedulesByName(name);
            doReturn(0).when(scheduleStore).deleteSchedulesByName(name);

            assertEquals(1, withStore.disarm(CONVERSATION_ID), "the retry removed the unhandled record");
            verify(memoryStore, times(2)).clearPendingContinuation(CONVERSATION_ID, null, null);
        }
    }

    @Nested
    @DisplayName("recover — arming a decided continuation the crash left unarmed (#1180)")
    class Recover {

        private static final String DECISION = "decision-2";
        private IConversationMemoryStore memoryStore;
        private ConversationContinuations recovering;

        @BeforeEach
        void setUpRecovery() {
            memoryStore = mock(IConversationMemoryStore.class);
            recovering = new ConversationContinuations(scheduleStore, memoryStore, meters, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private PendingContinuationCandidate candidate(ConversationState state) {
            return new PendingContinuationCandidate(CONVERSATION_ID, "agent-1", "user-1", state,
                    new PendingContinuation(2, 5, "go on", "production", NOW.minusSeconds(600).toEpochMilli(), DECISION));
        }

        private double recovered() {
            var counter = meters.find(ConversationContinuations.METRIC_RECOVERED).counter();
            return counter == null ? 0 : counter.count();
        }

        @Test
        @DisplayName("a missing schedule is written once under the derived id, for the owner, with the stored input and cap")
        void armsTheMissingSchedule() throws Exception {
            doReturn(true).when(scheduleStore).createScheduleIfAbsent(any());
            doReturn(true).when(memoryStore).markPendingContinuationHandled(CONVERSATION_ID, 2, DECISION);

            assertEquals(ConversationContinuations.Recovery.RECOVERED, recovering.recover(candidate(ConversationState.READY)));

            var captor = ArgumentCaptor.forClass(ScheduleConfiguration.class);
            verify(scheduleStore).createScheduleIfAbsent(captor.capture());
            ScheduleConfiguration schedule = captor.getValue();
            assertEquals(ConversationContinuations.scheduleId(CONVERSATION_ID, 2, DECISION), schedule.getId());
            assertEquals(ConversationContinuations.scheduleName(CONVERSATION_ID), schedule.getName());
            assertEquals("user-1", schedule.getUserId(), "the fire runs only for the conversation's owner");
            assertEquals("agent-1", schedule.getAgentId());
            assertEquals("production", schedule.getEnvironment());
            assertEquals("go on", schedule.getMessage());
            assertEquals(NOW, schedule.getNextFire());
            assertEquals(2, schedule.getMetadata().get(ConversationContinuations.METADATA_NUMBER_KEY));
            assertEquals(5, schedule.getMetadata().get(ConversationContinuations.METADATA_MAX_KEY));
            assertEquals(DECISION, schedule.getMetadata().get(ConversationContinuations.METADATA_DECISION_KEY));
            assertTrue(ConversationContinuations.isContinuationSchedule(schedule.getMetadata()));
            verify(scheduleStore, never()).deleteSchedule(anyString());
            assertEquals(1.0, recovered());
        }

        @Test
        @DisplayName("an armed continuation is left alone: nothing written, nothing deleted, the record marked handled")
        void alreadyArmed() throws Exception {
            doReturn(false).when(scheduleStore).createScheduleIfAbsent(any());
            doReturn(true).when(memoryStore).markPendingContinuationHandled(CONVERSATION_ID, 2, DECISION);

            assertEquals(ConversationContinuations.Recovery.ALREADY_ARMED, recovering.recover(candidate(ConversationState.READY)));

            verify(memoryStore).markPendingContinuationHandled(CONVERSATION_ID, 2, DECISION);
            verify(scheduleStore, never()).deleteSchedule(anyString());
            verify(scheduleStore, never()).deleteSchedulesByName(anyString());
            assertEquals(0.0, recovered());
        }

        @Test
        @DisplayName("an ended or paused conversation is not revived: no schedule, and its record is removed")
        void notReady() throws Exception {
            for (ConversationState state : List.of(ConversationState.ENDED, ConversationState.AWAITING_HUMAN,
                    ConversationState.EXECUTION_INTERRUPTED)) {
                assertEquals(ConversationContinuations.Recovery.NOT_READY, recovering.recover(candidate(state)), state.name());
            }
            verify(scheduleStore, never()).createScheduleIfAbsent(any());
            verify(memoryStore, times(3)).clearPendingContinuation(CONVERSATION_ID, 2, DECISION);
        }

        @Test
        @DisplayName("a record withdrawn or replaced while the sweep armed (cancel, end, newer turn) takes the sweep's row back")
        void superseded() throws Exception {
            doReturn(true).when(scheduleStore).createScheduleIfAbsent(any());
            doReturn(false).when(memoryStore).markPendingContinuationHandled(CONVERSATION_ID, 2, DECISION);

            assertEquals(ConversationContinuations.Recovery.SUPERSEDED, recovering.recover(candidate(ConversationState.READY)));

            verify(scheduleStore).deleteSchedule(ConversationContinuations.scheduleId(CONVERSATION_ID, 2, DECISION));
            assertEquals(0.0, recovered());
        }

        @Test
        @DisplayName("a row the original arm wrote is never withdrawn, even when the record is gone")
        void neverWithdrawsARowItDidNotWrite() throws Exception {
            doReturn(false).when(scheduleStore).createScheduleIfAbsent(any());
            doReturn(false).when(memoryStore).markPendingContinuationHandled(CONVERSATION_ID, 2, DECISION);

            assertEquals(ConversationContinuations.Recovery.ALREADY_ARMED, recovering.recover(candidate(ConversationState.READY)));
            verify(scheduleStore, never()).deleteSchedule(anyString());
        }

        @Test
        @DisplayName("two sweeps at once write one row between them and both keep it")
        void concurrentSweeps() throws Exception {
            // A schedule store whose insert-if-absent is atomic, as both real stores are,
            // and a memory store whose mark only ever marks — a record nobody withdrew.
            var rows = new ConcurrentHashMap<String, ScheduleConfiguration>();
            var deleted = new AtomicInteger();
            var start = new CyclicBarrier(2);
            doAnswer(inv -> {
                ScheduleConfiguration schedule = inv.getArgument(0);
                start.await(5, TimeUnit.SECONDS);
                return rows.putIfAbsent(schedule.getId(), schedule) == null;
            }).when(scheduleStore).createScheduleIfAbsent(any());
            doAnswer(inv -> {
                deleted.incrementAndGet();
                rows.remove((String) inv.getArgument(0));
                return null;
            }).when(scheduleStore).deleteSchedule(anyString());
            doReturn(true).when(memoryStore).markPendingContinuationHandled(CONVERSATION_ID, 2, DECISION);

            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> recovering.recover(candidate(ConversationState.READY)));
                var second = pool.submit(() -> recovering.recover(candidate(ConversationState.READY)));
                var outcomes = EnumSet.of(first.get(10, TimeUnit.SECONDS),
                        second.get(10, TimeUnit.SECONDS));

                assertEquals(EnumSet.of(ConversationContinuations.Recovery.RECOVERED, ConversationContinuations.Recovery.ALREADY_ARMED),
                        outcomes);
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, rows.size(), "one row for the continuation");
            assertEquals(0, deleted.get(), "neither sweep took the row back");
            assertEquals(1.0, recovered());
        }

        @Test
        @DisplayName("the mark and the removal name the decision, so a later decision of the same number is not touched")
        void matchesTheDecision() throws Exception {
            doReturn(false).when(scheduleStore).createScheduleIfAbsent(any());
            doReturn(true).when(memoryStore).markPendingContinuationHandled(anyString(), anyInt(), any());

            recovering.recover(candidate(ConversationState.READY));
            recovering.recover(candidate(ConversationState.ENDED));

            verify(memoryStore).markPendingContinuationHandled(CONVERSATION_ID, 2, DECISION);
            verify(memoryStore).clearPendingContinuation(CONVERSATION_ID, 2, DECISION);
            verify(scheduleStore, never()).readSchedule(anyString());
        }

        @Test
        @DisplayName("a schedule store failure propagates, so the record stays for the next sweep")
        void storeFailureKeepsTheRecord() throws Exception {
            doThrow(new IResourceStore.ResourceStoreException("down")).when(scheduleStore).createScheduleIfAbsent(any());

            assertThrows(IResourceStore.ResourceStoreException.class, () -> recovering.recover(candidate(ConversationState.READY)));
            verify(memoryStore, never()).markPendingContinuationHandled(anyString(), anyInt(), any());
            verify(memoryStore, never()).clearPendingContinuation(anyString(), any(), any());
        }
    }
}
