package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLedgerServiceTest {

    @Mock
    private IAuditStore auditStore;

    private MeterRegistry meterRegistry;
    private AuditLedgerService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        // "The store holds no chain position for this conversation." A bare Mockito
        // mock would answer 0 for a long-returning method, which the seed reads as
        // "position 0 is taken" and turns into a first sequence of 1. Tests that
        // care about the seed override this.
        when(auditStore.maxSequence(anyString())).thenReturn(AuditEntry.UNSEQUENCED);
    }

    /**
     * A store that is genuinely unavailable refuses the batch <em>and</em> the
     * per-entry retry the ledger falls back to. Stubbing only {@code appendBatch}
     * would exercise the recovery path instead of the outage.
     */
    private void storeIsDown() {
        doThrow(new RuntimeException("db error")).when(auditStore).appendBatch(anyList());
        doThrow(new RuntimeException("db error")).when(auditStore).appendEntry(any());
    }

    /** Undo {@link #storeIsDown()} — both write paths accept again. */
    private void storeIsUp() {
        doNothing().when(auditStore).appendBatch(anyList());
        doNothing().when(auditStore).appendEntry(any());
    }

    private AuditLedgerService createService(boolean enabled, String masterKey) {
        var svc = AuditLedgerService.createForTesting(auditStore, enabled, 60, masterKey, meterRegistry);
        svc.init();
        return svc;
    }

    private AuditEntry entry(String id, String convId, String agentId) {
        return new AuditEntry(id, convId, agentId, 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("text", "hello"), Map.of("text", "response"),
                null, null, List.of("action1"), 0.0, Instant.now(), null, null);
    }

    // ==================== isEnabled ====================

    @Test
    @DisplayName("isEnabled — returns true when enabled")
    void isEnabledTrue() {
        service = createService(true, null);
        assertTrue(service.isEnabled());
    }

    @Test
    @DisplayName("isEnabled — returns false when disabled")
    void isEnabledFalse() {
        service = createService(false, null);
        assertFalse(service.isEnabled());
    }

    // ==================== submit ====================

    @Test
    @DisplayName("submit — queues entry when enabled")
    void submitQueuesEntry() {
        service = createService(true, null);
        service.submit(entry("1", "conv1", "agent1"));
        assertEquals(1, service.getQueueSize());
    }

    @Test
    @DisplayName("submit — ignores null entry")
    void submitIgnoresNull() {
        service = createService(true, null);
        service.submit(null);
        assertEquals(0, service.getQueueSize());
    }

    @Test
    @DisplayName("submit — does nothing when disabled")
    void submitWhenDisabled() {
        service = createService(false, null);
        service.submit(entry("1", "conv1", "agent1"));
        assertEquals(0, service.getQueueSize());
    }

    @Test
    @DisplayName("submit — with HMAC key computes hmac")
    void submitWithHmacKey() {
        service = createService(true, "my-secret-key-for-testing");
        assertNotNull(service.getHmacKey());
        service.submit(entry("1", "conv1", "agent1"));
        assertEquals(1, service.getQueueSize());
    }

    // ==================== flush ====================

    @Test
    @DisplayName("flush — persists queued entries")
    void flushPersists() {
        service = createService(true, null);
        service.submit(entry("1", "conv1", "agent1"));
        service.submit(entry("2", "conv2", "agent2"));

        service.flush();

        verify(auditStore).appendBatch(argThat(batch -> batch.size() == 2));
        assertEquals(0, service.getQueueSize());
    }

    /**
     * A null timestamp must be stamped before signing, not by the store. v4 signs
     * the empty string for a null timestamp, but PostgresAuditStore substitutes
     * now() on write — so a null-timestamped entry would read back carrying a
     * timestamp the signature never covered and report INVALID forever, on that
     * backend only. Stamping in the service makes the stored row the signed row.
     */
    @Test
    @DisplayName("flush — a null timestamp is stamped before signing, and verifies")
    @SuppressWarnings("unchecked")
    void nullTimestampIsStampedBeforeSigning() {
        service = createService(true, "master-key");
        service.submit(new AuditEntry("nts-1", "conv1", "agent1", 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("text", "hello"), Map.of("text", "response"),
                null, null, List.of("action1"), 0.0, null, null, null));

        service.flush();

        var persisted = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(persisted.capture());
        AuditEntry stored = ((List<AuditEntry>) persisted.getValue()).getFirst();

        assertNotNull(stored.timestamp(), "the store's now()-fallback must never be what stamps a signed entry");
        assertEquals(0, stored.timestamp().getNano() % 1_000_000, "stamped at the signed (millisecond) precision");
        assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(stored),
                "what was signed is what is stored, so it must verify as-is");
    }

    /**
     * The submit path must floor the timestamp BEFORE signing — deterministically
     * pinned with a nano-precise input, so this does not depend on the test host's
     * clock resolution. Without the flooring, PostgreSQL's microsecond rounding can
     * move a stored timestamp across the millisecond the v4 signature covers, and
     * roughly one row in two thousand reports tampered for no reason.
     */
    @Test
    @DisplayName("flush — a nano-precise timestamp is floored before signing, and verifies")
    @SuppressWarnings("unchecked")
    void nanoPreciseTimestampIsFlooredBeforeSigning() {
        service = createService(true, "master-key");
        Instant nanoPrecise = Instant.parse("2026-08-20T10:15:30Z").plusNanos(123_999_600L);
        service.submit(new AuditEntry("floor-1", "conv1", "agent1", 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("text", "hello"), Map.of("text", "response"),
                null, null, List.of("action1"), 0.0, nanoPrecise, null, null));

        service.flush();

        var persisted = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(persisted.capture());
        AuditEntry stored = ((List<AuditEntry>) persisted.getValue()).getFirst();

        assertEquals(0, stored.timestamp().getNano() % 1_000_000,
                "the stored row must carry the millisecond value the signature covers — nothing left to round");
        assertEquals(nanoPrecise.truncatedTo(ChronoUnit.MILLIS), stored.timestamp());
        assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(stored));
    }

    // ==================== sequence-table eviction ====================

    /**
     * The table used to be {@code clear()}ed on overflow, on the reasoning that
     * re-seeding from {@code countByConversation} was "correct, only slower". It is
     * not: an entry that is still queued has consumed a position the store cannot
     * see yet, so the re-seed hands the same number out twice — and the verifier
     * grades a duplicate as {@code BROKEN}, i.e. the ledger reporting the
     * deployment as tampered because its own bookkeeping wrapped around.
     */
    @Test
    @DisplayName("sequence eviction — a conversation with queued entries is never re-seeded into a duplicate")
    void sequenceEvictionKeepsQueuedConversationsUnique() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, null);

        // Consume position 0 for "live" and leave it sitting in the queue.
        service.submit(entry("live-1", "live", "agent1"));

        // Overflow the table. Nothing is flushed, so "live" is still queued when
        // eviction runs — exactly the window the old clear() got wrong.
        for (int i = 0; i < AuditLedgerService.MAX_TRACKED_CONVERSATIONS + 5; i++) {
            service.submit(entry("fill-" + i, "filler-" + i, "agent1"));
        }

        service.submit(entry("live-2", "live", "agent1"));
        service.flush();

        var persisted = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(persisted.capture());
        @SuppressWarnings("unchecked")
        List<Long> liveSequences = ((List<AuditEntry>) persisted.getValue()).stream()
                .filter(e -> "live".equals(e.conversationId()))
                .map(AuditEntry::sequence)
                .toList();

        assertEquals(List.of(0L, 1L), liveSequences,
                "the second entry must continue the chain, not restart it at a position already taken");
    }

    /**
     * The other half of the contract: once the queue has drained, those counters
     * ARE safe to drop, so the table must actually shrink. Without this the fix
     * could "pass" by simply never evicting, which would strand every later
     * conversation on UNSEQUENCED.
     */
    @Test
    @DisplayName("sequence eviction — fully-persisted conversations are evicted so new ones still chain")
    void sequenceEvictionReclaimsPersistedConversations() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, null);

        for (int i = 0; i < AuditLedgerService.MAX_TRACKED_CONVERSATIONS + 5; i++) {
            service.submit(entry("fill-" + i, "filler-" + i, "agent1"));
        }
        service.flush(); // everything is now durably in the store

        service.submit(entry("fresh-1", "fresh", "agent1"));
        service.flush();

        var persisted = ArgumentCaptor.forClass(List.class);
        verify(auditStore, times(2)).appendBatch(persisted.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> lastBatch = (List<AuditEntry>) persisted.getValue();
        var fresh = lastBatch.stream().filter(e -> "fresh".equals(e.conversationId())).findFirst().orElseThrow();

        assertEquals(0L, fresh.sequence(),
                "with the queue drained the table must have room again, so this chains normally");
        assertNotEquals(AuditEntry.UNSEQUENCED, fresh.sequence());
    }

    /**
     * The third place a consumed chain position can live. Once
     * {@link AuditLedgerService#flush()} has polled a batch off the queue, those
     * positions are in neither the queue nor the store until the append returns —
     * so an eviction landing in that window would read their conversations as fully
     * persisted and re-seed them straight into a duplicate.
     * <p>
     * Driven from inside the mocked {@code appendBatch}, which is precisely when
     * the batch is in flight.
     */
    @Test
    @DisplayName("sequence eviction — a conversation in the in-flight flush batch is not re-seeded")
    void sequenceEvictionRetainsTheInFlightBatch() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, null);

        // Position 0 for "live", then force it out of the queue and into the
        // in-flight batch by flushing with an append that overflows the table
        // while it is still executing.
        service.submit(entry("live-1", "live", "agent1"));
        doAnswer(invocation -> {
            for (int i = 0; i < AuditLedgerService.MAX_TRACKED_CONVERSATIONS + 5; i++) {
                service.submit(entry("fill-" + i, "filler-" + i, "agent1"));
            }
            return null;
        }).when(auditStore).appendBatch(any());

        service.flush();

        // The append has returned, so "live" is genuinely persisted now; what
        // matters is that eviction did not drop its counter mid-flight.
        doAnswer(invocation -> null).when(auditStore).appendBatch(any());
        service.submit(entry("live-2", "live", "agent1"));

        var persisted = ArgumentCaptor.forClass(List.class);
        service.flush();
        verify(auditStore, atLeastOnce()).appendBatch(persisted.capture());
        @SuppressWarnings("unchecked")
        var live2 = ((List<AuditEntry>) persisted.getValue()).stream()
                .filter(e -> "live".equals(e.conversationId()))
                .findFirst().orElseThrow();

        assertEquals(1L, live2.sequence(),
                "the counter must have survived an eviction that ran while its entry was in the flush batch");
    }

    /**
     * When every tracked conversation is genuinely live, a scan evicts nothing —
     * and without a barrier the next submit for an unseen conversation would take
     * the write lock and traverse the whole queue (bounded at 100k) to reach the
     * same conclusion, with every other submitter blocked behind it on the read
     * lock. Only a flush can change the answer, so only a flush lifts the barrier.
     */
    @Test
    @DisplayName("sequence eviction — a futile scan is not repeated until a flush could change the answer")
    void futileEvictionScanIsNotRepeatedUntilFlush() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, null);

        // Fill the table with conversations that are all still queued, so nothing
        // is evictable.
        for (int i = 0; i < AuditLedgerService.MAX_TRACKED_CONVERSATIONS + 5; i++) {
            service.submit(entry("fill-" + i, "filler-" + i, "agent1"));
        }
        int trackedAfterFill = service.getTrackedConversationCount();

        // Further unseen conversations must not each re-scan; they degrade to
        // UNSEQUENCED, which is the honest verdict rather than a duplicate.
        for (int i = 0; i < 50; i++) {
            service.submit(entry("late-" + i, "late-conv-" + i, "agent1"));
        }
        assertEquals(trackedAfterFill, service.getTrackedConversationCount(),
                "a barred scan must not grow the table either");
        assertEquals(1, service.getFutileEvictionScans(),
                "the scan should have run once and then been barred, not once per submit");

        // A flush moves entries out of the queue, so the next scan is worthwhile
        // again — and this time it can actually evict.
        service.flush();
        service.submit(entry("after-1", "after-flush", "agent1"));

        assertTrue(service.getTrackedConversationCount() < trackedAfterFill,
                "once the queue drained, the barrier must lift and eviction reclaim the table");
    }

    /**
     * A conversation with a dead-lettered position can never be re-seeded — the
     * store count is smaller than the next free position once there is a gap, so
     * re-seeding would hand the same numbers out twice. Those counters are
     * therefore pinned for the process lifetime, which is only safe because they
     * cannot fill the table: the undelivered cap counts <em>sequences</em>, so the
     * worst case is one pinned conversation per tracked sequence.
     * <p>
     * The failure this guards is a plausible future edit — raising
     * {@code MAX_TRACKED_UNDELIVERED} to or past {@code MAX_TRACKED_CONVERSATIONS}
     * — after which a long store outage could pin every slot and strand every later
     * conversation on {@code UNSEQUENCED} until restart, with nothing failing to
     * say so.
     */
    @Test
    @DisplayName("sequence eviction — pinned undelivered conversations do not block reclamation")
    void undeliveredPinCannotExhaustTheTable() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, null);

        // A store outage dead-letters this conversation, so position 0 is
        // consumed but never persisted. Its counter is pinned from here on: the
        // store count can no longer tell us where the chain resumes.
        storeIsDown();
        service.submit(entry("pinned-1", "pinned", "agent1"));
        service.flush();
        service.flush();
        service.flush(); // dead-lettered
        assertEquals(Set.of(0L), service.undeliveredSequences("pinned"),
                "precondition: the outage left an unattributed position for this conversation");

        // Store recovers. Fill the table with conversations that DO persist.
        storeIsUp();
        for (int i = 0; i < AuditLedgerService.MAX_TRACKED_CONVERSATIONS + 5; i++) {
            service.submit(entry("fill-" + i, "filler-" + i, "agent1"));
        }
        service.flush();

        // The pinned conversation is retained while the persisted ones are
        // reclaimed, so a new conversation still gets a real chain position
        // rather than being stranded on UNSEQUENCED.
        service.submit(entry("fresh-1", "fresh", "agent1"));
        service.flush();

        var persisted = ArgumentCaptor.forClass(List.class);
        verify(auditStore, atLeastOnce()).appendBatch(persisted.capture());
        @SuppressWarnings("unchecked")
        var fresh = ((List<AuditEntry>) persisted.getValue()).stream()
                .filter(e -> "fresh".equals(e.conversationId()))
                .findFirst().orElseThrow();
        assertNotEquals(AuditEntry.UNSEQUENCED, fresh.sequence(),
                "a pinned conversation must not cost later conversations their chain position");
        assertEquals(0L, fresh.sequence());

        // And the pin did its job: the dead-lettered position is not handed out
        // a second time.
        service.submit(entry("pinned-2", "pinned", "agent1"));
        service.flush();
        verify(auditStore, atLeastOnce()).appendBatch(persisted.capture());
        @SuppressWarnings("unchecked")
        var pinnedSecond = ((List<AuditEntry>) persisted.getValue()).stream()
                .filter(e -> "pinned".equals(e.conversationId()))
                .findFirst().orElseThrow();
        assertEquals(1L, pinnedSecond.sequence(),
                "the counter survived, so the chain resumes past the dead-lettered 0 rather than reusing it");
    }

    @Test
    @DisplayName("flush — does nothing when queue is empty")
    void flushEmptyQueue() {
        service = createService(true, null);
        service.flush();
        verify(auditStore, never()).appendBatch(any());
    }

    @Test
    @DisplayName("flush — retries on failure (less than MAX_FLUSH_RETRIES)")
    void flushRetriesOnFailure() {
        service = createService(true, null);
        storeIsDown();

        service.submit(entry("1", "conv1", "agent1"));
        service.flush(); // First failure — requeues

        assertEquals(1, service.getQueueSize()); // Still in queue
    }

    @Test
    @DisplayName("flush — drops entries after MAX_FLUSH_RETRIES consecutive failures")
    void flushDropsAfterMaxRetries() {
        service = createService(true, null);
        storeIsDown();

        service.submit(entry("1", "conv1", "agent1"));
        service.flush(); // Failure 1 — requeue
        assertEquals(1, service.getQueueSize());
        service.flush(); // Failure 2 — requeue
        assertEquals(1, service.getQueueSize());
        service.flush(); // Failure 3 — drop + dead letter

        assertEquals(0, service.getQueueSize()); // Dropped
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count());
    }

    /**
     * The point of the per-entry fallback (finding 02). A bulk write is atomic in
     * neither backend, so re-offering the whole batch punished every entry for one
     * bad row: the rows that HAD landed were carried back into the store as
     * duplicate keys, nothing new was written, and after three flush windows every
     * record in them — from unrelated conversations — was dead-lettered together.
     */
    @Test
    @DisplayName("one unstorable entry does not take its whole batch down with it")
    void aPoisonEntryOnlyCostsItself() {
        service = createService(true, null);
        // The batch write always fails; the per-entry retry accepts everything
        // except the poison row. That is exactly the shape of a NOT NULL violation
        // on one entry inside a JDBC batch.
        doThrow(new RuntimeException("batch aborted")).when(auditStore).appendBatch(anyList());
        doAnswer(invocation -> {
            AuditEntry e = invocation.getArgument(0);
            if ("poison".equals(e.id())) {
                throw new RuntimeException("null value in column violates not-null constraint");
            }
            return null;
        }).when(auditStore).appendEntry(any());

        service.submit(entry("good-1", "conv-a", "agent-1"));
        service.submit(entry("poison", "conv-b", "agent-1"));
        service.submit(entry("good-2", "conv-c", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditStore, times(3)).appendEntry(captor.capture());
        assertEquals(List.of("good-1", "poison", "good-2"), captor.getAllValues().stream().map(AuditEntry::id).toList(),
                "every entry of the failed batch must be retried on its own");

        assertEquals(1, service.getQueueSize(), "only the entry that genuinely could not be stored comes back");
        assertEquals(0.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the good entries landed, so nothing is dropped on the first failure");
    }

    /**
     * Finding 06: the final flush has no next attempt, so re-queuing loses the
     * entries silently — no dead-letter record, no dropped-counter increment, and
     * the queue is never drained again.
     */
    @Test
    @DisplayName("shutdown — a failing final flush dead-letters instead of re-queuing")
    void shutdownDeadLettersInsteadOfRequeuing() {
        service = createService(true, null);
        storeIsDown();

        service.submit(entry("1", "conv1", "agent1"));
        service.shutdown();

        assertEquals(0, service.getQueueSize(), "nothing may be left in a queue that will never be drained");
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "an abandoned entry must be counted, not lost between a re-queue and the JVM exiting");
    }

    /**
     * Finding 07. The re-queue loop ran backwards under a comment claiming it put
     * entries "at the front" — which a {@code ConcurrentLinkedQueue} has no way to
     * do, so all it achieved was reversing the batch. The next flush then persisted
     * the retried entries in reverse submission order.
     */
    @Test
    @DisplayName("a re-queued batch keeps its submission order")
    void requeuedEntriesKeepTheirSubmissionOrder() {
        service = createService(true, null);
        storeIsDown();

        service.submit(entry("1", "conv1", "agent1"));
        service.submit(entry("2", "conv1", "agent1"));
        service.submit(entry("3", "conv1", "agent1"));
        service.flush(); // fails on both paths — all three come back

        storeIsUp();
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore, times(2)).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> retried = (List<AuditEntry>) captor.getAllValues().get(1);
        assertEquals(List.of("1", "2", "3"), retried.stream().map(AuditEntry::id).toList(),
                "the retry must not reverse the batch");
    }

    /**
     * The per-entry fallback must not turn an outage into an unbounded blocking
     * loop. It runs on the writer thread inside {@code synchronized flush()} — the
     * monitor the {@code @PreDestroy} final flush also waits on — so an uncapped
     * pass over a queue grown toward the 100k bound, against a PostgreSQL whose
     * connect timeout is measured in tens of seconds, would hold both for hours,
     * where the old whole-batch retry failed once per flush window.
     */
    @Test
    @DisplayName("the per-entry retry stops calling a store that has refused N entries in a row")
    void perEntryRetryGivesUpOnAStoreThatIsSimplyDown() {
        service = createService(true, null);
        storeIsDown();

        int batchSize = AuditLedgerService.MAX_CONSECUTIVE_ENTRY_FAILURES + 5;
        for (int i = 0; i < batchSize; i++) {
            service.submit(entry("id-" + i, "conv-" + i, "agent-1"));
        }
        service.flush();

        verify(auditStore, times(AuditLedgerService.MAX_CONSECUTIVE_ENTRY_FAILURES)).appendEntry(any());
        assertEquals(batchSize, service.getQueueSize(),
                "the entries the pass skipped must still come back for the next flush, not be lost");
    }

    /**
     * An entry submitted while the final flush is draining the queue is still
     * perfectly storable, but nothing scheduled will ever pick it up. Recording it
     * as dropped without offering it to a healthy store once more turns a clean
     * shutdown into avoidable audit loss.
     */
    @Test
    @DisplayName("shutdown — an entry submitted during the final flush is stored, not dead-lettered")
    void shutdownStoresEntriesSubmittedDuringTheFinalFlush() {
        service = createService(true, null);
        var lateSubmissionDone = new AtomicBoolean(false);
        doAnswer(invocation -> {
            if (lateSubmissionDone.compareAndSet(false, true)) {
                // Arrives after the final flush has already drained the queue.
                service.submit(entry("late", "conv1", "agent1"));
            }
            return null;
        }).when(auditStore).appendBatch(anyList());

        service.submit(entry("1", "conv1", "agent1"));
        service.shutdown();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore, times(2)).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> lastBatch = (List<AuditEntry>) captor.getAllValues().get(1);
        assertEquals(List.of("late"), lastBatch.stream().map(AuditEntry::id).toList(),
                "the late entry must be offered to the store, not written straight to the dead-letter sink");
        assertEquals(0, service.getQueueSize());
        assertEquals(0.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "a healthy store means nothing was dropped");
    }

    /**
     * The window {@code drainQueueToDeadLetter} actually exists for, and the one no
     * test reached. {@link #shutdownStoresEntriesSubmittedDuringTheFinalFlush}
     * covers an entry that arrives during the FIRST final flush — the second flush
     * stores it and the drain finds nothing, which is why that test asserts
     * {@code dropped == 0}. An entry that arrives during the SECOND flush has no
     * flush left: the executor is already down and the scheduled task will never
     * run again, so the drain is the only thing standing between it and silent
     * loss.
     * <p>
     * Both halves of the drain are pinned, because either could be deleted without
     * any existing test noticing: the {@code droppedCounter} increment (an operator
     * has to be able to see that the ledger abandoned something) and the sink write
     * — asserted through {@code undeliveredSequences}, which only
     * {@code writeToDeadLetter} populates, so a drain that counted the entry and
     * threw it away would still fail here.
     */
    @Test
    @DisplayName("shutdown — an entry submitted during the SECOND final flush is drained to the dead-letter sink")
    void shutdownDrainsEntriesSubmittedDuringTheSecondFinalFlush() {
        // A store that assigns chain positions, so the abandoned entry has a sequence
        // for undeliveredSequences to record — an UNSEQUENCED entry is skipped there
        // by design and the sink assertion below could not tell that apart from a
        // drain that never wrote.
        when(auditStore.supportsSequence()).thenReturn(true);
        service = createService(true, null);
        var flushes = new AtomicInteger();
        doAnswer(invocation -> {
            switch (flushes.incrementAndGet()) {
                // Arrives after the first final flush drained the queue; the second
                // flush is still to come, so this one is storable.
                case 1 -> service.submit(entry("late", "conv1", "agent1"));
                // Arrives after the SECOND flush drained the queue. Nothing will ever
                // flush again.
                case 2 -> service.submit(entry("very-late", "conv1", "agent1"));
                default -> {
                }
            }
            return null;
        }).when(auditStore).appendBatch(anyList());

        service.submit(entry("1", "conv1", "agent1"));
        service.shutdown();

        verify(auditStore, times(2)).appendBatch(anyList());
        assertEquals(0, service.getQueueSize(),
                "nothing may be left in a queue that will never be drained again");
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the entry the ledger abandoned must be counted, not lost between the last flush and the JVM exiting");
        assertEquals(1, service.undeliveredSequences("conv1").size(),
                "and it must actually reach the dead-letter sink — undelivered is only populated by writeToDeadLetter");
    }

    /**
     * Finding 24: an operator setting 0 in the hope of "flush immediately" used to
     * abort startup with {@code IllegalArgumentException("period <= 0")} thrown
     * from inside the executor API — a stack trace that never names the property.
     */
    @Test
    @DisplayName("a non-positive flush interval falls back to the default instead of failing startup")
    void nonPositiveFlushIntervalFallsBackToTheDefault() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 0, null, meterRegistry);
        assertDoesNotThrow(svc::init);
        assertEquals(AuditLedgerService.DEFAULT_FLUSH_INTERVAL_SECONDS, svc.getFlushIntervalSeconds());
        svc.shutdown();
    }

    // ==================== scrub secrets ====================

    @Test
    @DisplayName("submit — scrubs secrets from input/output maps")
    void submitScrubsSecrets() {
        service = createService(true, null);
        var entryWithSecret = new AuditEntry("1", "conv1", "agent1", 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("apiKey", "sk-1234567890abcdef"), Map.of("text", "safe"),
                null, null, List.of(), 0.0, Instant.now(), null, null);

        service.submit(entryWithSecret);
        service.flush();

        verify(auditStore).appendBatch(any());
    }

    // ==================== serializeDeadLetterEntry ====================

    @Test
    @DisplayName("serializeDeadLetterEntry — includes type field when provided")
    void serializeWithType() {
        service = createService(true, null);
        var e = entry("1", "conv1", "agent1");
        String json = service.serializeDeadLetterEntry(e, "audit_dead_letter");
        assertTrue(json.contains("\"type\":\"audit_dead_letter\""));
        assertTrue(json.contains("\"conversationId\":\"conv1\""));
    }

    /**
     * Finding 05. The record used to be five fields — timestamp of the drop,
     * conversationId, agentId, taskId, taskType. With no {@code sequence} in it an
     * operator who restarted after a dead-letter event could not prove which
     * positions the ledger itself had abandoned, so every self-inflicted gap read
     * as {@code BROKEN} forever; and the audit content, the HMAC and the agent
     * signature were gone outright, which is not the "durable evidence"
     * {@code undeliveredSequences} rests its verdict on.
     */
    @Test
    @DisplayName("serializeDeadLetterEntry — carries the whole entry, not a five-field summary")
    void deadLetterRecordIsReplayable() {
        service = createService(true, null);
        Instant written = Instant.parse("2026-01-02T03:04:05Z");
        var e = new AuditEntry("dl-1", "conv1", "agent1", 1, "user1", "production",
                2, "taskId", "LlmTask", 3, 100L,
                Map.of("text", "hello"), Map.of("text", "response"),
                Map.of("model", "gpt"), Map.of("tool", "calc"), List.of("action1"), 0.25, written,
                "v4:abcdef", "sig-1", 42L);

        String json = service.serializeDeadLetterEntry(e, "audit_dead_letter");

        assertTrue(json.contains("\"id\":\"dl-1\""), json);
        assertTrue(json.contains("\"sequence\":42"), json);
        assertTrue(json.contains("\"hmac\":\"v4:abcdef\""), json);
        assertTrue(json.contains("\"agentSignature\":\"sig-1\""), json);
        assertTrue(json.contains("\"entryTimestamp\":\"2026-01-02T03:04:05Z\""), json);
        assertTrue(json.contains("\"llmDetail\""), json);
        assertTrue(json.contains("\"toolCalls\""), json);
        // The wrapper fields existing consumers already read are unchanged.
        assertTrue(json.contains("\"conversationId\":\"conv1\""), json);
        assertTrue(json.contains("\"type\":\"audit_dead_letter\""), json);
    }

    /**
     * The price of making the record replayable, pinned so it stays a deliberate
     * decision. Widening the record from five metadata fields to the whole entry
     * put the user id and the verbatim prompts and responses into a plaintext JSONL
     * file on disk — content the old record did not contain — and the GDPR erasure
     * cascade pseudonymises the ledger and the database logs but touches nothing
     * under {@code eddi.audit.dead-letter-path}. So an Art. 17 erasure now leaves
     * user content behind unless the operator handles that file, which is why
     * {@code docs/audit-ledger.md} and {@code docs/gdpr-compliance.md} both say so.
     * <p>
     * Assert it explicitly rather than leaving it implied by "the whole entry":
     * whoever next changes what goes into this record should have to change a test
     * that names the retention obligation.
     */
    @Test
    @DisplayName("serializeDeadLetterEntry — the sink holds personal data, which erasure does not reach")
    void deadLetterRecordCarriesPersonalDataAndIsOutsideTheErasureCascade() {
        service = createService(true, null);
        var e = new AuditEntry("dl-2", "conv1", "agent1", 1, "subject-42", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("text", "my name is Alice"), Map.of("text", "hello Alice"),
                null, null, List.of(), 0.0, Instant.now(), null, null, 7L);

        String json = service.serializeDeadLetterEntry(e, null);

        assertTrue(json.contains("\"userId\":\"subject-42\""),
                "the sink identifies the data subject, so it is in scope for Art. 17: " + json);
        assertTrue(json.contains("my name is Alice"), "and carries their verbatim prompt: " + json);
        assertTrue(json.contains("hello Alice"), "and the verbatim response: " + json);
    }

    @Test
    @DisplayName("serializeDeadLetterEntry — omits type when null")
    void serializeWithoutType() {
        service = createService(true, null);
        var e = entry("1", "conv1", "agent1");
        String json = service.serializeDeadLetterEntry(e, null);
        assertFalse(json.contains("\"type\""));
        assertTrue(json.contains("\"agentId\":\"agent1\""));
    }

    // ==================== shutdown ====================

    @Test
    @DisplayName("shutdown — flushes remaining entries")
    void shutdownFlushes() {
        service = createService(true, null);
        service.submit(entry("1", "conv1", "agent1"));
        service.shutdown();
        verify(auditStore).appendBatch(any());
    }

    @Test
    @DisplayName("shutdown — handles null executor when disabled")
    void shutdownWhenDisabled() {
        service = createService(false, null);
        assertDoesNotThrow(() -> service.shutdown());
    }

    // ==================== G20: bounded queue ====================

    /**
     * The queue was a {@code ConcurrentLinkedQueue} with no bound, and a failed
     * flush re-offered its whole batch back into it — so a store that stopped
     * accepting writes grew the heap without limit and fed its own failure. Entries
     * past the bound are now dropped and counted instead.
     * <p>
     * This covers the submit side only; the retry/dead-letter side is driven by
     * {@link #failedFlushDeadLettersWhatNoLongerFits()}. (It used to stub a
     * throwing {@code appendBatch} here, which was dead weight: nothing in this
     * test ever calls {@code flush()}.)
     */
    @Test
    @DisplayName("a stalled store cannot grow the queue past its bound")
    void stalledStoreDoesNotGrowTheQueueWithoutLimit() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 5);
        svc.init();

        for (int i = 0; i < 200; i++) {
            svc.submit(entry("id-" + i, "conv-1", "agent-1"));
        }

        assertEquals(5, svc.getQueueSize(), "the queue must stay at its bound instead of accumulating every entry");
        assertEquals(195.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "dropped entries must be counted, not silently lost");
        verify(auditStore, never()).appendBatch(anyList());
    }

    // ==================== G18 x G20: drops must not forge a tamper verdict ====

    /**
     * The collision the two guarantees used to create: G18 puts a signed, gap-free
     * per-conversation sequence on every entry, G20 drops entries once the queue is
     * full. Consuming the sequence first meant a dropped entry burned a chain
     * position, so {@code /auditstore/verify} reported {@code ChainStatus.BROKEN} —
     * the ledger accusing the deployment of deleting a record the ledger itself had
     * thrown away.
     */
    @Test
    @DisplayName("an entry the ledger drops never consumes a chain position")
    @SuppressWarnings("unchecked")
    void droppedEntryDoesNotConsumeAChainPosition() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-1")).thenReturn(0L);
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, "master-key-1234567890", meterRegistry, 1);
        svc.init();

        svc.submit(entry("id-1", "conv-1", "agent-1")); // queued → sequence 0
        svc.submit(entry("id-2", "conv-1", "agent-1")); // queue full → dropped
        svc.flush(); // store accepts, queue drains
        svc.submit(entry("id-3", "conv-1", "agent-1")); // queued → must be sequence 1
        svc.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore, times(2)).appendBatch(captor.capture());
        List<Long> writtenSequences = ((List<List<AuditEntry>>) (List<?>) captor.getAllValues()).stream().flatMap(List::stream)
                .map(AuditEntry::sequence).toList();

        assertEquals(List.of(0L, 1L), writtenSequences,
                "the dropped entry must not burn sequence 1 — that hole makes /auditstore/verify report BROKEN");
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count());
        assertTrue(svc.undeliveredSequences("conv-1").isEmpty(),
                "nothing was abandoned mid-chain, so there is nothing to attribute");
    }

    /**
     * The retry path's dead-letter branch: the flush drains the queue, the store
     * refuses the batch, and by the time it is re-offered the queue has refilled.
     * Those entries already own chain positions, so the positions must be recorded
     * — otherwise the gap is indistinguishable from a deletion.
     */
    @Test
    @DisplayName("a re-offer that no longer fits is dead-lettered and its chain positions are recorded")
    void failedFlushDeadLettersWhatNoLongerFits() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-1")).thenReturn(0L);
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, "master-key-1234567890", meterRegistry, 2);
        svc.init();

        // The store is down, and while the flush is in flight the queue it just
        // drained fills back up — so the failed batch no longer fits on retry.
        doAnswer(invocation -> {
            svc.submit(entry("late-1", "conv-1", "agent-1"));
            svc.submit(entry("late-2", "conv-1", "agent-1"));
            throw new RuntimeException("store down");
        }).when(auditStore).appendBatch(anyList());
        // The store is down, so the per-entry retry the ledger falls back to
        // refuses as well; only then is the batch re-offered to the queue.
        doThrow(new RuntimeException("store down")).when(auditStore).appendEntry(any());

        svc.submit(entry("id-1", "conv-1", "agent-1")); // sequence 0
        svc.submit(entry("id-2", "conv-1", "agent-1")); // sequence 1
        svc.flush();

        assertEquals(2, svc.getQueueSize(), "the retry re-offer must respect the bound");
        assertEquals(Set.of(0L, 1L), svc.undeliveredSequences("conv-1"),
                "positions the ledger abandoned must be recorded, or verify() reports them as deletions");
        assertEquals(2.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count());
    }

    @Test
    @DisplayName("a batch abandoned after MAX_FLUSH_RETRIES records its chain positions")
    void abandonedBatchRecordsItsChainPositions() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-1")).thenReturn(0L);
        service = createService(true, "master-key-1234567890");
        storeIsDown();

        service.submit(entry("id-1", "conv-1", "agent-1"));
        service.flush(); // failure 1 — requeue
        service.flush(); // failure 2 — requeue
        service.flush(); // failure 3 — dead-letter

        assertEquals(0, service.getQueueSize());
        assertEquals(Set.of(0L), service.undeliveredSequences("conv-1"));
        assertTrue(service.undeliveredSequences("conv-other").isEmpty());
    }

    @Test
    @DisplayName("submits past the bound are dropped, not queued")
    void submitsPastTheBoundAreDropped() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 2);
        svc.init();

        svc.submit(entry("id-1", "conv-1", "agent-1"));
        svc.submit(entry("id-2", "conv-1", "agent-1"));
        svc.submit(entry("id-3", "conv-1", "agent-1"));

        assertEquals(2, svc.getQueueSize());
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count());
    }

    @Test
    @DisplayName("queue size returns to zero after a successful flush")
    void queueDrainsOnSuccessfulFlush() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 10);
        svc.init();

        svc.submit(entry("id-1", "conv-1", "agent-1"));
        svc.submit(entry("id-2", "conv-1", "agent-1"));
        assertEquals(2, svc.getQueueSize());

        svc.flush();
        assertEquals(0, svc.getQueueSize());
    }

    // ==================== G18: conversation sequence ====================

    @Test
    @DisplayName("entries are numbered per conversation when the store supports it")
    void entriesAreNumberedPerConversation() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.submit(entry("id-2", "conv-a", "agent-1"));
        service.submit(entry("id-3", "conv-b", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();

        assertEquals(0L, written.get(0).sequence());
        assertEquals(1L, written.get(1).sequence(), "the second entry of a conversation continues the chain");
        assertEquals(0L, written.get(2).sequence(), "a different conversation has its own chain");
    }

    /**
     * The fallback path: a store that cannot answer {@link IAuditStore#maxSequence}
     * (it returns {@code UNSEQUENCED}) is seeded from the row count, which is what
     * the ledger did for every store before finding 03.
     */
    @Test
    @DisplayName("the sequence continues from the row count when the store cannot report a max")
    void sequenceIsSeededFromTheStore() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-a")).thenReturn(7L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        assertEquals(7L, written.getFirst().sequence());
    }

    /**
     * Finding 03. {@code countByConversation} counts rows that landed, which stops
     * matching the next free position the moment one was handed out and never
     * stored. Sequences 0-9 issued with 3 and 5 dead-lettered leaves a count of 8
     * while the next free position is 10 — so a restart (or a second cluster node)
     * seeded from the count re-issues 8 and 9, and {@code /auditstore/verify}
     * grades a duplicate exactly like a deletion: {@code BROKEN}. Seeding from
     * {@code max + 1} cannot do that, and unlike the in-memory "undelivered" pin it
     * survives the process.
     */
    @Test
    @DisplayName("the sequence is seeded from max+1, so a dead-lettered gap is never re-issued")
    void sequenceIsSeededFromMaxNotCount() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-a")).thenReturn(8L); // 3 and 5 never landed
        when(auditStore.maxSequence("conv-a")).thenReturn(9L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        assertEquals(10L, written.getFirst().sequence(),
                "the chain must resume past the highest stored position, not at the row count");
    }

    /**
     * A store that drops the field must not be handed a sequence — the signature
     * covers it, so every one of its rows would read as tampered.
     */
    @Test
    @DisplayName("a store that cannot persist the sequence gets unsequenced entries")
    void storeWithoutSequenceSupportGetsUnsequencedEntries() {
        when(auditStore.supportsSequence()).thenReturn(false);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        assertEquals(AuditEntry.UNSEQUENCED, written.getFirst().sequence());
        verify(auditStore, never()).countByConversation(anyString());
    }

    @Test
    @DisplayName("a signed entry verifies through the service")
    void signedEntryVerifies() {
        when(auditStore.supportsSequence()).thenReturn(true);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        AuditEntry stored = written.getFirst();

        assertTrue(service.isSigningEnabled());
        assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(stored));
        assertEquals(AuditVerificationStatus.INVALID, service.verifyEntry(stored.withEnvironment("TAMPERED")));
        assertEquals(AuditVerificationStatus.UNSIGNED, service.verifyEntry(stored.withHmac(null)));
    }

    /**
     * G17 end to end: a GDPR erasure rewrites userId on a stored entry, and the
     * entry must still verify — under v1/v2 it did not, so every routine erasure
     * manufactured rows indistinguishable from tampered ones.
     */
    @Test
    @DisplayName("a pseudonymised stored entry still verifies")
    void pseudonymisedStoredEntryStillVerifies() {
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        AuditEntry stored = written.getFirst();

        AuditEntry pseudonymised = stored.withUserId(AuditHmac.pseudonymFor("user1"));
        assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(pseudonymised));
    }
}
