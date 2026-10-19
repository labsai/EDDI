/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.ConversationUpgradeService.UpgradeRefusedException;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationGenerationCandidate;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.GenerationUpgradeOutcome;
import ai.labs.eddi.engine.model.ConversationUpgradeReport;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.InMemoryConversationCoordinator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static ai.labs.eddi.engine.memory.model.ConversationState.AWAITING_HUMAN;
import static ai.labs.eddi.engine.memory.model.ConversationState.ENDED;
import static ai.labs.eddi.engine.memory.model.ConversationState.ERROR;
import static ai.labs.eddi.engine.memory.model.ConversationState.EXECUTION_INTERRUPTED;
import static ai.labs.eddi.engine.memory.model.ConversationState.IN_PROGRESS;
import static ai.labs.eddi.engine.memory.model.ConversationState.READY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConversationUpgradeService")
class ConversationUpgradeServiceTest {

    private static final String AGENT = "5aaf98e19f7dd421ac3c7de9";
    private static final int CURRENT_VERSION = 7;
    private static final int TARGET_GENERATION = 3;

    private IConversationMemoryStore store;
    private IAgentStore agentStore;
    private IAgentFactory agentFactory;
    private SimpleMeterRegistry meters;
    private final List<Callable<Void>> queued = new ArrayList<>();
    private final List<String> queuedConversations = new ArrayList<>();
    private boolean runInline = true;
    private RuntimeException submitFailure;

    /**
     * A coordinator that runs the task on the spot (or holds it, or refuses it).
     */
    private final IConversationCoordinator coordinator = new IConversationCoordinator() {
        @Override
        public void submitInOrder(String conversationId, Callable<Void> callable) {
            if (submitFailure != null) {
                throw submitFailure;
            }
            queuedConversations.add(conversationId);
            queued.add(callable);
            if (runInline) {
                try {
                    callable.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        store = mock(IConversationMemoryStore.class);
        agentStore = mock(IAgentStore.class);
        agentFactory = mock(IAgentFactory.class);
        meters = new SimpleMeterRegistry();

        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getVersion()).thenReturn(CURRENT_VERSION);
        when(agentStore.getCurrentResourceId(AGENT)).thenReturn(resourceId);
        when(agentStore.read(AGENT, CURRENT_VERSION)).thenReturn(configWithGeneration(TARGET_GENERATION));

        var serving = mock(IAgent.class);
        when(serving.getAgentVersion()).thenReturn(CURRENT_VERSION);
        when(agentFactory.getLatestReadyAgentOfGeneration(Environment.production, AGENT, TARGET_GENERATION)).thenReturn(serving);

        when(store.upgradeCompatibilityGeneration(anyString(), eq(AGENT), eq(TARGET_GENERATION))).thenReturn(GenerationUpgradeOutcome.UPGRADED);
        when(store.isValidConversationId(anyString())).thenReturn(true);
    }

    private ConversationUpgradeService service() {
        return service(5_000);
    }

    private ConversationUpgradeService service(long turnWaitMillis) {
        return new ConversationUpgradeService(store, coordinator, agentStore, agentFactory, meters, turnWaitMillis);
    }

    private static AgentConfiguration configWithGeneration(Integer generation) {
        var configuration = new AgentConfiguration();
        configuration.setCompatibilityGeneration(generation);
        return configuration;
    }

    private void candidates(ConversationGenerationCandidate... candidates) throws Exception {
        when(store.loadGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION), isNull(), anyInt())).thenReturn(List.of(candidates));
        when(store.loadGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION), anyString(), anyInt())).thenReturn(List.of());
    }

    private static ConversationGenerationCandidate candidate(String id, int version, ConversationState state) {
        return new ConversationGenerationCandidate(id, state, version, version < 6 ? null : 2);
    }

    private static ConversationUpgradeReport.VersionCounts version(ConversationUpgradeReport report, int version) {
        return report.versions().stream().filter(v -> v.fromVersion() == version).findFirst().orElseThrow();
    }

    // ───────────────────────────── selection and skips
    // ─────────────────────────────

    @Test
    @DisplayName("a dry run counts per source version and writes nothing — it never even queues a write")
    void dryRunWritesNothing() throws Exception {
        candidates(candidate("c1", 5, READY), candidate("c2", 5, READY), candidate("c3", 5, ENDED), candidate("c4", 5, AWAITING_HUMAN),
                candidate("c5", 6, ERROR), candidate("c6", 6, IN_PROGRESS), candidate("c7", 6, EXECUTION_INTERRUPTED));

        var report = service().upgrade(Environment.production, AGENT, null, true, null, null);

        verify(store, never()).upgradeCompatibilityGeneration(anyString(), anyString(), anyInt());
        assertTrue(queued.isEmpty(), "a dry run must not touch the coordinator");
        assertTrue(report.dryRun());
        assertEquals(CURRENT_VERSION, report.currentVersion());
        assertEquals(TARGET_GENERATION, report.targetGeneration());
        assertEquals(CURRENT_VERSION, report.servingVersion());
        var five = version(report, 5);
        assertEquals(4, five.matched());
        assertEquals(2, five.eligible());
        assertEquals(0, five.upgraded());
        assertEquals(Map.of("ended", 1L, "awaitingHuman", 1L), five.skipped());
        var six = version(report, 6);
        assertEquals(3, six.matched());
        assertEquals(2, six.eligible(), "ERROR and EXECUTION_INTERRUPTED conversations can take a turn, so they move");
        assertEquals(Map.of("inProgress", 1L), six.skipped());
        assertEquals(7, report.totals().matched());
        assertEquals(4, report.totals().eligible());
        assertEquals(0, report.totals().upgraded());
        assertEquals(0.0, meters.counter("eddi_conversation_generation_upgrade_count").count());
    }

    @Test
    @DisplayName("a real run moves READY, ERROR and EXECUTION_INTERRUPTED conversations and skips ended, paused and in-progress ones")
    void realRunMovesOnlyEligible() throws Exception {
        candidates(candidate("c1", 5, READY), candidate("c2", 5, ENDED), candidate("c3", 5, AWAITING_HUMAN), candidate("c4", 6, ERROR),
                candidate("c5", 6, IN_PROGRESS), candidate("c6", 6, EXECUTION_INTERRUPTED));

        var report = service().upgrade(Environment.production, AGENT, null, false, null, null);

        assertFalse(report.dryRun());
        assertEquals(List.of("c1", "c4", "c6"), queuedConversations, "each write goes through the conversation's own queue");
        verify(store).upgradeCompatibilityGeneration("c1", AGENT, TARGET_GENERATION);
        verify(store).upgradeCompatibilityGeneration("c4", AGENT, TARGET_GENERATION);
        verify(store).upgradeCompatibilityGeneration("c6", AGENT, TARGET_GENERATION);
        verify(store, never()).upgradeCompatibilityGeneration(eq("c2"), anyString(), anyInt());
        verify(store, never()).upgradeCompatibilityGeneration(eq("c3"), anyString(), anyInt());
        verify(store, never()).upgradeCompatibilityGeneration(eq("c5"), anyString(), anyInt());
        assertEquals(1, version(report, 5).upgraded());
        assertEquals(2, version(report, 6).upgraded());
        assertEquals(3, report.totals().upgraded());
        assertEquals(Map.of("ended", 1L, "awaitingHuman", 1L, "inProgress", 1L), report.totals().skipped());
        assertEquals(3.0, meters.counter("eddi_conversation_generation_upgrade_count").count());
    }

    @Test
    @DisplayName("the target is the generation of the current version, and it is what the store is asked to write — never a lower one")
    void targetIsTheCurrentGenerationAndNeverLowered() throws Exception {
        candidates(candidate("c1", 5, READY), candidate("c2", 5, READY));
        when(store.upgradeCompatibilityGeneration(eq("c2"), eq(AGENT), anyInt())).thenReturn(GenerationUpgradeOutcome.ALREADY_CURRENT);

        var report = service().upgrade(Environment.production, AGENT, null, false, null, null);

        var generation = ArgumentCaptor.forClass(Integer.class);
        verify(store, times(2)).upgradeCompatibilityGeneration(anyString(), eq(AGENT), generation.capture());
        assertEquals(List.of(TARGET_GENERATION, TARGET_GENERATION), generation.getAllValues());
        // The candidate query itself only selects lower or absent generations.
        verify(store, atLeastOnce()).loadGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION), any(), anyInt());
        assertEquals(1, report.totals().upgraded());
        assertEquals(Map.of("alreadyCurrent", 1L), report.totals().skipped());
        assertEquals(1.0, meters.counter("eddi_conversation_generation_upgrade_count").count());
    }

    @Test
    @DisplayName("what the store reports about a conversation that changed since it was listed is counted, not overwritten")
    void outcomesOfTheConditionalWriteAreCounted() throws Exception {
        candidates(candidate("c1", 5, READY), candidate("c2", 5, READY), candidate("c3", 5, READY), candidate("c4", 5, READY),
                candidate("c5", 5, READY));
        when(store.upgradeCompatibilityGeneration(eq("c1"), eq(AGENT), anyInt())).thenReturn(GenerationUpgradeOutcome.ENDED);
        when(store.upgradeCompatibilityGeneration(eq("c2"), eq(AGENT), anyInt())).thenReturn(GenerationUpgradeOutcome.AWAITING_HUMAN);
        when(store.upgradeCompatibilityGeneration(eq("c3"), eq(AGENT), anyInt())).thenReturn(GenerationUpgradeOutcome.NOT_FOUND);
        when(store.upgradeCompatibilityGeneration(eq("c4"), eq(AGENT), anyInt())).thenReturn(GenerationUpgradeOutcome.CHANGED_CONCURRENTLY);
        when(store.upgradeCompatibilityGeneration(eq("c5"), eq(AGENT), anyInt())).thenThrow(new IResourceStore.ResourceStoreException("down"));

        var report = service().upgrade(Environment.production, AGENT, null, false, null, null);

        assertEquals(0, report.totals().upgraded());
        assertEquals(Map.of("ended", 1L, "awaitingHuman", 1L, "notFound", 1L, "changedConcurrently", 1L, "failed", 1L),
                report.totals().skipped());
    }

    @Test
    @DisplayName("fromVersion narrows the selection, and a requested version with nothing to move still appears")
    void fromVersionNarrowsAndIsReported() throws Exception {
        when(store.loadGenerationUpgradeCandidates(eq(AGENT), eq(Set.of(4, 5)), eq(TARGET_GENERATION), any(), anyInt()))
                .thenReturn(List.of(candidate("c1", 5, READY))).thenReturn(List.of());

        var report = service().upgrade(Environment.production, AGENT, List.of(5, 4), true, null, null);

        assertEquals(List.of(4, 5), report.versions().stream().map(ConversationUpgradeReport.VersionCounts::fromVersion).toList());
        assertEquals(0, version(report, 4).matched());
        assertEquals(1, version(report, 5).matched());
    }

    // ───────────────────────────── paging and limit ─────────────────────────────

    @Test
    @DisplayName("candidates are read a page at a time, keyset-paged, never all at once")
    void pagesByKeyset() throws Exception {
        List<ConversationGenerationCandidate> first = IntStream.range(0, ConversationUpgradeService.PAGE_SIZE)
                .mapToObj(i -> candidate("a" + i, 5, READY)).toList();
        when(store.loadGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION), isNull(), anyInt())).thenReturn(first);
        when(store.loadGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION), eq("a" + (ConversationUpgradeService.PAGE_SIZE - 1)),
                anyInt())).thenReturn(List.of(candidate("b0", 5, READY)));
        when(store.loadGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION), eq("b0"), anyInt())).thenReturn(List.of());

        var report = service().upgrade(Environment.production, AGENT, null, false, null, null);

        assertEquals(ConversationUpgradeService.PAGE_SIZE + 1, report.totals().upgraded());
        assertFalse(report.truncated());
        var pageSize = ArgumentCaptor.forClass(Integer.class);
        verify(store, atLeast(3)).loadGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION), any(),
                pageSize.capture());
        assertTrue(pageSize.getAllValues().stream().allMatch(size -> size <= ConversationUpgradeService.PAGE_SIZE));
    }

    @Test
    @DisplayName("the limit stops the scan and the report says there is more")
    void limitTruncates() throws Exception {
        when(store.loadGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION), isNull(), eq(2)))
                .thenReturn(List.of(candidate("c1", 5, READY), candidate("c2", 5, READY)));
        when(store.loadGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION), eq("c2"), eq(1)))
                .thenReturn(List.of(candidate("c3", 5, READY)));

        var report = service().upgrade(Environment.production, AGENT, null, false, 2, null);

        assertEquals(2, report.totals().upgraded());
        assertTrue(report.truncated());
        assertEquals("c2", report.nextAfter(), "the cursor to continue from");
        verify(store, never()).upgradeCompatibilityGeneration(eq("c3"), anyString(), anyInt());
    }

    @Test
    @DisplayName("ended conversations are counted per version by one query, not scanned")
    void endedAreCountedNotScanned() throws Exception {
        candidates(candidate("c1", 5, READY));
        when(store.countEndedGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION))).thenReturn(Map.of(5, 3L, 6, 2L));

        var report = service().upgrade(Environment.production, AGENT, null, true, null, null);

        var five = version(report, 5);
        assertEquals(4, five.matched());
        assertEquals(1, five.eligible());
        assertEquals(Map.of("ended", 3L), five.skipped());
        assertEquals(Map.of("ended", 2L), version(report, 6).skipped());
        assertEquals(5, report.totals().skipped().get("ended"));
        assertEquals(6, report.totals().matched());
    }

    @Test
    @DisplayName("progress across calls: permanently skipped candidates beyond the limit do not stop a call with the cursor from moving the rest")
    void cursorMakesProgressPastSkippedCandidates() throws Exception {
        // Five paused conversations ahead in id order, then two that can move; limit 3.
        var all = new ArrayList<ConversationGenerationCandidate>();
        for (int i = 1; i <= 5; i++) {
            all.add(candidate("p" + i, 5, AWAITING_HUMAN));
        }
        all.add(candidate("r1", 5, READY));
        all.add(candidate("r2", 5, READY));
        when(store.loadGenerationUpgradeCandidates(eq(AGENT), any(), eq(TARGET_GENERATION), any(), anyInt())).thenAnswer(invocation -> {
            String after = invocation.getArgument(3);
            int max = invocation.getArgument(4);
            return all.stream().filter(c -> after == null || c.conversationId().compareTo(after) > 0).limit(max).toList();
        });

        var first = service().upgrade(Environment.production, AGENT, null, false, 3, null);
        assertTrue(first.truncated());
        assertEquals("p3", first.nextAfter());
        assertEquals(0, first.totals().upgraded());

        var second = service().upgrade(Environment.production, AGENT, null, false, 3, first.nextAfter());
        assertTrue(second.truncated());
        assertEquals("r1", second.nextAfter());
        assertEquals(1, second.totals().upgraded());

        var third = service().upgrade(Environment.production, AGENT, null, false, 3, second.nextAfter());
        assertFalse(third.truncated());
        assertEquals(null, third.nextAfter());
        assertEquals(1, third.totals().upgraded());
        verify(store).upgradeCompatibilityGeneration("r1", AGENT, TARGET_GENERATION);
        verify(store).upgradeCompatibilityGeneration("r2", AGENT, TARGET_GENERATION);
    }

    @Test
    @DisplayName("a malformed after cursor is a bad request, refused before anything is listed or written")
    void malformedAfterIsRefused() throws Exception {
        when(store.isValidConversationId("garbage")).thenReturn(false);

        var e = assertThrows(UpgradeRefusedException.class, () -> service().upgrade(Environment.production, AGENT, null, false, null, "garbage"));

        assertEquals(UpgradeRefusedException.Kind.INVALID, e.kind());
        assertTrue(e.getMessage().contains("after"), e.getMessage());
        verify(store, never()).loadGenerationUpgradeCandidates(anyString(), any(), anyInt(), any(), anyInt());
        verify(store, never()).upgradeCompatibilityGeneration(anyString(), anyString(), anyInt());
        assertTrue(queued.isEmpty());
    }

    @Test
    @DisplayName("a limit outside 1..MAX is refused")
    void limitIsBounded() {
        for (int bad : new int[]{0, -1, ConversationUpgradeService.MAX_LIMIT + 1}) {
            var e = assertThrows(UpgradeRefusedException.class, () -> service().upgrade(Environment.production, AGENT, null, true, bad, null));
            assertEquals(UpgradeRefusedException.Kind.INVALID, e.kind());
        }
    }

    // ───────────────────────────── target validation ─────────────────────────────

    @Test
    @DisplayName("refused with a conflict when no version of the target generation is READY — nothing is listed or written")
    void refusesWhenTargetNotReady() throws Exception {
        when(agentFactory.getLatestReadyAgentOfGeneration(Environment.production, AGENT, TARGET_GENERATION)).thenReturn(null);

        var e = assertThrows(UpgradeRefusedException.class, () -> service().upgrade(Environment.production, AGENT, null, false, null, null));

        assertEquals(UpgradeRefusedException.Kind.CONFLICT, e.kind());
        assertTrue(e.getMessage().contains("READY") && e.getMessage().contains("generation " + TARGET_GENERATION), e.getMessage());
        verify(store, never()).loadGenerationUpgradeCandidates(anyString(), any(), anyInt(), any(), anyInt());
        verify(store, never()).upgradeCompatibilityGeneration(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("refused with a conflict when the current version has no generation to move onto")
    void refusesWhenCurrentVersionHasNoGeneration() throws Exception {
        when(agentStore.read(AGENT, CURRENT_VERSION)).thenReturn(configWithGeneration(null));

        var e = assertThrows(UpgradeRefusedException.class, () -> service().upgrade(Environment.production, AGENT, null, true, null, null));

        assertEquals(UpgradeRefusedException.Kind.CONFLICT, e.kind());
        verify(store, never()).loadGenerationUpgradeCandidates(anyString(), any(), anyInt(), any(), anyInt());
    }

    @Test
    @DisplayName("an unknown agent is a not-found refusal")
    void unknownAgent() throws Exception {
        when(agentStore.getCurrentResourceId("nope")).thenThrow(new IResourceStore.ResourceNotFoundException("nope"));

        var e = assertThrows(UpgradeRefusedException.class, () -> service().upgrade(Environment.production, "nope", null, true, null, null));

        assertEquals(UpgradeRefusedException.Kind.NOT_FOUND, e.kind());
    }

    @Test
    @DisplayName("a fromVersion that is not a version of the agent is a bad request")
    void invalidFromVersion() {
        for (int bad : new int[]{0, -3, CURRENT_VERSION + 1}) {
            var e = assertThrows(UpgradeRefusedException.class,
                    () -> service().upgrade(Environment.production, AGENT, List.of(bad), true, null, null));
            assertEquals(UpgradeRefusedException.Kind.INVALID, e.kind());
        }
    }

    // ───────────────────────────── the conversation's queue
    // ─────────────────────────────

    @Test
    @DisplayName("a conversation whose queue does not free in time is reported busy, and its task is withdrawn so it can never run later")
    void timedOutTaskIsWithdrawn() throws Exception {
        runInline = false;
        candidates(candidate("c1", 5, READY));

        var report = service(50).upgrade(Environment.production, AGENT, null, false, null, null);

        assertEquals(0, report.totals().upgraded());
        assertEquals(Map.of("busy", 1L), report.totals().skipped());
        assertEquals(1, queued.size());
        // The turn ahead finally finishes and the queue reaches our task: it must do
        // nothing,
        // or a conversation the report counted as skipped would be moved after all.
        queued.getFirst().call();
        verify(store, never()).upgradeCompatibilityGeneration(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("a task the coordinator drops is reported busy at once, without waiting out the timeout")
    void discardedTaskIsBusy() throws Exception {
        runInline = false;
        candidates(candidate("c1", 5, READY));
        var report = new AtomicReference<ConversationUpgradeReport>();
        var done = new CountDownLatch(1);
        Thread runner = Thread.ofVirtual().start(() -> {
            report.set(service(60_000).upgrade(Environment.production, AGENT, null, false, null, null));
            done.countDown();
        });

        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (queued.isEmpty() && System.nanoTime() < giveUp) {
            Thread.sleep(5);
        }
        assertFalse(queued.isEmpty(), "the write must be queued on the conversation's coordinator");
        ((IDiscardableTask) queued.getFirst()).onDiscarded(new RejectedExecutionException("pool saturated"));

        assertTrue(done.await(10, TimeUnit.SECONDS), "must not wait the 60 s");
        runner.join();
        assertEquals(Map.of("busy", 1L), report.get().totals().skipped());
    }

    @Test
    @DisplayName("a coordinator at capacity (submission rejected) is reported busy")
    void rejectedSubmissionIsBusy() throws Exception {
        submitFailure = new RejectedExecutionException("Coordinator capacity exceeded");
        candidates(candidate("c1", 5, READY));

        var report = service().upgrade(Environment.production, AGENT, null, false, null, null);

        assertEquals(Map.of("busy", 1L), report.totals().skipped());
        verify(store, never()).upgradeCompatibilityGeneration(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("the write waits for a turn of the same conversation that is running — it never overlaps it")
    void writeWaitsForTheRunningTurn() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            var runtime = mock(IRuntime.class);
            when(runtime.submitCallable(any(), any(IRuntime.IFinishedExecution.class), any())).thenAnswer(invocation -> {
                Callable<Object> task = invocation.getArgument(0);
                IRuntime.IFinishedExecution<Object> callback = invocation.getArgument(1);
                Future<Object> future = pool.submit(() -> {
                    try {
                        Object result = task.call();
                        callback.onComplete(result);
                        return result;
                    } catch (Throwable t) {
                        callback.onFailure(t);
                        return null;
                    }
                });
                return future;
            });
            var realCoordinator = new InMemoryConversationCoordinator(runtime, new SimpleMeterRegistry(), 1000, 100);
            candidates(candidate("c1", 5, READY));

            var turnRunning = new CountDownLatch(1);
            var finishTurn = new CountDownLatch(1);
            // A turn of the conversation, already running: it holds the conversation's
            // queue.
            realCoordinator.submitInOrder("c1", () -> {
                turnRunning.countDown();
                finishTurn.await();
                return null;
            });
            assertTrue(turnRunning.await(5, TimeUnit.SECONDS));

            var service = new ConversationUpgradeService(store, realCoordinator, agentStore, agentFactory, meters, 20_000);
            var report = new AtomicReference<ConversationUpgradeReport>();
            var upgrading = pool.submit(() -> report.set(service.upgrade(Environment.production, AGENT, null, false, null, null)));

            Thread.sleep(300);
            verify(store, never()).upgradeCompatibilityGeneration(anyString(), anyString(), anyInt());
            assertFalse(upgrading.isDone(), "the upgrade is waiting behind the running turn");

            finishTurn.countDown();
            upgrading.get(10, TimeUnit.SECONDS);
            verify(store, timeout(5_000)).upgradeCompatibilityGeneration("c1", AGENT, TARGET_GENERATION);
            assertNotNull(report.get());
            assertEquals(1, report.get().totals().upgraded());
        } finally {
            pool.shutdownNow();
        }
    }
}
