/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import io.micrometer.core.instrument.MeterRegistry;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.nio.file.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.type.TypeReference;
import ai.labs.eddi.utils.LogSanitizer;
import java.util.Map;
import io.micrometer.core.instrument.Counter;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Async batch writer for the immutable audit ledger.
 * <p>
 * Follows the same pattern as
 * {@link ai.labs.eddi.engine.runtime.BoundedLogStore}: non-blocking capture via
 * a {@link ConcurrentLinkedQueue}, with a {@link ScheduledExecutorService}
 * flushing entries to {@link IAuditStore} at a configurable interval.
 * <p>
 * Before persisting, each entry passes through:
 * <ol>
 * <li>{@link SecretRedactionFilter} — scrubs potential secrets from string
 * values</li>
 * <li>{@link AuditHmac} — computes HMAC-SHA256 integrity hash using the vault
 * master key</li>
 * </ol>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class AuditLedgerService {

    private static final Logger LOGGER = Logger.getLogger(AuditLedgerService.class);
    private static final int MAX_FLUSH_RETRIES = 3;

    /** Default bound for {@code eddi.audit.max-queue-size}. */
    static final int DEFAULT_MAX_QUEUE_SIZE = 100_000;

    /**
     * Threshold at which sequence-counter eviction kicks in. On overflow, counters
     * whose positions are all accounted for are evicted and re-seeded from the
     * store on next use — see {@link #evictSequenceCountersIfFull} for why only
     * those are safe to drop.
     * <p>
     * A threshold, not a hard ceiling: the check in {@link #nextSequence} is not
     * atomic with the insert that follows it, so concurrent submitters can
     * overshoot it slightly. See the comment there for why that is the right trade.
     * <p>
     * Package-visible so the eviction tests can reach the threshold instead of
     * hard-coding a copy of it that would drift.
     */
    static final int MAX_TRACKED_CONVERSATIONS = 50_000;

    /**
     * Cap on how many chain positions are remembered as "consumed but never
     * persisted". Bounded so a permanently broken store cannot turn the attribution
     * table itself into the leak the queue bound was added to prevent. Once the cap
     * is hit the remaining drops go unattributed and the verifier falls back to
     * reporting them as {@code BROKEN} — the conservative verdict.
     */
    static final int MAX_TRACKED_UNDELIVERED = 10_000;

    private final IAuditStore auditStore;

    /**
     * Whether verification reconstructs the timestamp precision pre-v4 rows lost in
     * storage. On by default: without it every entry written before the v4
     * canonical form reports INVALID, which is what made the ledger's
     * tamper-evidence emit no usable signal at all. Set
     * {@code eddi.audit.verify.recover-legacy=false} to hold legacy rows to their
     * literal stored form.
     */
    private final boolean recoverLegacyTimestamps;

    /**
     * How many legacy rows one sweep may search before it stops trying. A search is
     * only spent on a row whose direct check already failed, so a healthy ledger
     * never touches this; a ledger verified with the wrong key would otherwise
     * spend one on every row. 500 bounds the worst case at a second or two of HMAC
     * work per request.
     */
    private final int recoverLegacyMaxRows;
    private final boolean enabled;
    private final int flushIntervalSeconds;
    private final Optional<String> masterKeyConfig;
    private final Counter droppedCounter;
    private final Instance<Connection> natsConnectionInstance;
    private final String deadLetterPath;
    private final boolean agentSigningEnabled;
    private final String defaultTenantId;
    private final AgentSigningService agentSigningService;
    private final ObjectMapper objectMapper;

    /** Target shape for {@link #normalizePayloadsForStorage}. */
    private static final TypeReference<Map<String, Object>> MAP_OF_OBJECT = new TypeReference<>() {
    };
    private final int maxQueueSize;

    private byte[] hmacKey;
    private final ConcurrentLinkedQueue<AuditEntry> queue = new ConcurrentLinkedQueue<>();
    /**
     * Tracks {@link #queue}'s length. {@link ConcurrentLinkedQueue#size()} is an
     * O(n) traversal, so it cannot be consulted on every submit to enforce the
     * bound.
     */
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicLong> conversationSequences = new ConcurrentHashMap<>();
    /**
     * Chain positions that were handed out but never reached the store, keyed by
     * conversation. See {@link #undeliveredSequences(String)}.
     */
    private final ConcurrentHashMap<String, Set<Long>> undelivered = new ConcurrentHashMap<>();
    private final AtomicInteger undeliveredTracked = new AtomicInteger(0);
    /**
     * Serialises sequence-table eviction against sequence assignment. Submitters
     * take the read lock (shared, so they never contend with one another) for the
     * whole span between consuming a chain position and making the entry visible in
     * {@link #queue}; {@link #evictSequenceCountersIfFull} takes the write lock.
     * Without it, eviction could drop a counter in that window and the next entry
     * would re-seed from a store that has not yet seen the outstanding one.
     */
    private final ReentrantReadWriteLock sequenceLock = new ReentrantReadWriteLock();
    /**
     * The batch {@link #flush()} has polled off the queue but not yet persisted.
     * Those entries own chain positions that are in neither the queue nor the
     * store, so eviction has to treat their conversations as live.
     */
    private volatile List<AuditEntry> inFlightBatch = List.of();
    /**
     * Bumped once per completed {@link #flush()}. The only event that can change
     * whether a counter is evictable, so it is what
     * {@link #evictSequenceCountersIfFull} uses to decide a rescan is worthwhile.
     */
    private final AtomicLong flushGeneration = new AtomicLong();
    /**
     * The {@link #flushGeneration} at which an eviction scan last found nothing to
     * evict. Equal values mean "already tried, nothing has moved since".
     */
    private final AtomicLong evictionBarrier = new AtomicLong(-1L);
    /**
     * Counts scans that evicted nothing — see {@link #getFutileEvictionScans()}.
     */
    private final AtomicLong futileEvictionScans = new AtomicLong();
    private ScheduledExecutorService flushExecutor;

    @Inject
    public AuditLedgerService(IAuditStore auditStore, @ConfigProperty(name = "eddi.audit.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.audit.flush-interval-seconds", defaultValue = "3") int flushIntervalSeconds,
            @ConfigProperty(name = "eddi.vault.master-key") Optional<String> masterKeyConfig,
            @ConfigProperty(name = "eddi.audit.dead-letter-path", defaultValue = "/opt/eddi/data/eddi-audit-deadletter.jsonl") String deadLetterPath,
            @ConfigProperty(name = "eddi.audit.agent-signing-enabled", defaultValue = "true") boolean agentSigningEnabled,
            @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId,
            @ConfigProperty(name = "eddi.audit.max-queue-size", defaultValue = "100000") int maxQueueSize,
            @ConfigProperty(name = "eddi.audit.verify.recover-legacy", defaultValue = "true") boolean recoverLegacyTimestamps,
            @ConfigProperty(name = "eddi.audit.verify.recover-legacy-max-rows", defaultValue = "500") int recoverLegacyMaxRows,
            MeterRegistry meterRegistry, Instance<Connection> natsConnectionInstance,
            AgentSigningService agentSigningService, ObjectMapper objectMapper) {
        this.recoverLegacyTimestamps = recoverLegacyTimestamps;
        this.recoverLegacyMaxRows = recoverLegacyMaxRows;
        this.auditStore = auditStore;
        this.enabled = enabled;
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.masterKeyConfig = masterKeyConfig;
        this.deadLetterPath = deadLetterPath;
        this.agentSigningEnabled = agentSigningEnabled;
        this.defaultTenantId = defaultTenantId;
        this.maxQueueSize = maxQueueSize > 0 ? maxQueueSize : DEFAULT_MAX_QUEUE_SIZE;
        this.droppedCounter = meterRegistry.counter("eddi_audit_entries_dropped_total");
        this.natsConnectionInstance = natsConnectionInstance;
        this.agentSigningService = agentSigningService;
        this.objectMapper = objectMapper;
    }

    /**
     * Factory method for unit testing — creates a service without CDI. Call
     * {@link #init()} after construction.
     */
    static AuditLedgerService createForTesting(IAuditStore auditStore, boolean enabled, int flushIntervalSeconds, String masterKeyConfig,
                                               MeterRegistry meterRegistry) {
        return createForTesting(auditStore, enabled, flushIntervalSeconds, masterKeyConfig, meterRegistry, DEFAULT_MAX_QUEUE_SIZE);
    }

    /**
     * Factory method for unit testing with an explicit queue bound.
     */
    static AuditLedgerService createForTesting(IAuditStore auditStore, boolean enabled, int flushIntervalSeconds, String masterKeyConfig,
                                               MeterRegistry meterRegistry, int maxQueueSize) {
        return new AuditLedgerService(auditStore, enabled, flushIntervalSeconds, Optional.ofNullable(masterKeyConfig), "eddi-audit-deadletter.jsonl",
                false, "default", maxQueueSize, true, 500, meterRegistry, null, null, new ObjectMapper());
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            LOGGER.info("Audit Ledger is DISABLED (eddi.audit.enabled=false)");
            return;
        }

        if (masterKeyConfig.isPresent() && !masterKeyConfig.get().isBlank()) {
            this.hmacKey = AuditHmac.deriveHmacKey(masterKeyConfig.get());
            LOGGER.info("Audit Ledger: HMAC integrity signing enabled.");
        } else {
            LOGGER.info("Audit Ledger: HMAC signing disabled (no vault master key).");
        }

        flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-ledger-writer");
            t.setDaemon(true);
            return t;
        });
        flushExecutor.scheduleAtFixedRate(this::flush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);

        LOGGER.infov("Audit Ledger initialized (flush every {0}s)", flushIntervalSeconds);
    }

    @PreDestroy
    void shutdown() {
        if (flushExecutor != null) {
            flush(); // Final flush
            flushExecutor.shutdown();
            try {
                if (!flushExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    flushExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                flushExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Enqueue an audit entry for async persistence. Applies secret redaction and
     * HMAC signing before queuing.
     *
     * @param entry
     *            the entry to persist (hmac field will be computed)
     */
    public void submit(AuditEntry entry) {
        if (!enabled || entry == null)
            return;

        // Take the queue slot BEFORE a chain position is consumed. G18 puts the
        // sequence inside the signed payload so a deleted row leaves a gap that
        // cannot be closed; G20 drops entries once the queue is full. Assigning
        // the sequence first made those two collide: every entry the ledger
        // itself dropped burned a number, and /auditstore/verify then reported
        // ChainStatus.BROKEN — the ledger accusing the deployment of deleting
        // records it had discarded on its own. Reserving first means a refused
        // entry never occupies a position in the chain at all.
        if (!reserveQueueSlot(entry.conversationId())) {
            return;
        }

        // Make room in the sequence table BEFORE taking the assignment lock —
        // eviction needs the write lock and this thread is about to hold the read
        // lock, which a ReentrantReadWriteLock cannot upgrade.
        evictSequenceCountersIfFull(entry.conversationId());

        boolean queued = false;
        // Read lock (shared — submitters never contend with each other) spans
        // "position consumed" → "entry visible in the queue". Eviction takes the
        // write lock, so it can never observe a conversation as idle while this
        // thread holds a number for it that nothing can see yet.
        sequenceLock.readLock().lock();
        try {
            // Scrub secrets from string values in maps
            AuditEntry scrubbed = scrubSecrets(entry);

            // Assign the conversation chain position BEFORE signing — the sequence is
            // part of the signed payload, which is what makes a deleted entry's gap
            // impossible to close by renumbering its neighbours.
            scrubbed = scrubbed.withSequence(nextSequence(scrubbed.conversationId()));

            // Payload maps must be reduced to their STORED shape before signing, for
            // exactly the same reason as the timestamp below: sign what is stored.
            scrubbed = normalizePayloadsForStorage(scrubbed);

            // A null timestamp must be stamped BEFORE signing, not by the store. v4
            // signs the empty string for null, but PostgresAuditStore substitutes
            // now() on write — so a null-timestamped entry read back would carry a
            // timestamp the signature never covered and report INVALID forever.
            // (MongoDB stores the field as absent, which round-trips; only the
            // Postgres fallback diverges.) Stamping here makes the two agree and the
            // signature honest.
            if (scrubbed.timestamp() == null) {
                scrubbed = scrubbed.withTimestamp(Instant.now());
            }

            // Floor the timestamp to what the signature covers and the backends can
            // store, BEFORE signing — so the row that lands in the database is
            // byte-for-byte the row that was signed and nothing downstream can round it.
            scrubbed = AuditHmac.withStorablePrecision(scrubbed);

            // Compute HMAC if key is available
            AuditEntry signed;
            if (hmacKey != null) {
                String hmac = AuditHmac.computeHmac(scrubbed, hmacKey);
                signed = scrubbed.withHmac(hmac);
            } else {
                signed = scrubbed;
            }

            // Sign with agent's Ed25519 key if signing is enabled
            if (agentSigningEnabled && agentSigningService != null && signed.agentId() != null) {
                signed = applyAgentSignature(signed);
            }

            queue.offer(signed);
            queued = true;
        } finally {
            sequenceLock.readLock().unlock();
            if (!queued) {
                // Signing/scrubbing blew up: give the reservation back rather than
                // leaking capacity that no entry occupies.
                queueSize.decrementAndGet();
            }
        }
    }

    /**
     * Claim one slot in the bounded queue, or refuse (loudly, and counted on
     * {@code eddi_audit_entries_dropped_total}) when it is full.
     * <p>
     * The queue used to be unbounded while a failed flush re-offered its whole
     * batch back into it: a store that slows down or stops therefore turned every
     * subsequent turn's entries into permanently retained heap, and the retry path
     * fed itself. The bound keeps a broken ledger from taking the process down with
     * it.
     *
     * @return true when capacity was reserved (the caller MUST either enqueue an
     *         entry or release the reservation)
     */
    private boolean reserveQueueSlot(String conversationId) {
        while (true) {
            int current = queueSize.get();
            if (current >= maxQueueSize) {
                droppedCounter.increment();
                LOGGER.warnv("Audit queue is full ({0} entries) — dropping entry for conversation {1}. "
                        + "The audit store is not keeping up; raise eddi.audit.max-queue-size or fix the store.", maxQueueSize,
                        sanitize(conversationId));
                return false;
            }
            if (queueSize.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Enqueue an already-sequenced entry, refusing it once the queue is at its
     * bound. Used by the flush retry path, where the entry's chain position has
     * already been consumed — a refusal there is recorded through
     * {@link #writeToDeadLetter} so the verifier can attribute the gap.
     *
     * @return true if the entry was queued, false if it was dropped
     */
    private boolean offerBounded(AuditEntry entry) {
        if (!reserveQueueSlot(entry.conversationId())) {
            return false;
        }
        queue.offer(entry);
        return true;
    }

    /**
     * Drop sequence counters for conversations that can be re-seeded from the store
     * without risk, once the table reaches {@link #MAX_TRACKED_CONVERSATIONS}.
     * <p>
     * The table used to be {@code clear()}ed wholesale, on the reasoning that
     * "re-seeding is correct, only slower". It is not: the counter is seeded from
     * {@code countByConversation}, which sees only what the store already holds.
     * Entries sit in {@link #queue} for up to one flush interval — longer while a
     * failing store is being retried — so clearing mid-flight re-issued positions
     * those entries had already consumed. Duplicates are graded exactly like gaps
     * ({@code ChainStatus.BROKEN}), and unlike a gap there is no exculpatory record
     * for them: the ledger would report the deployment as tampered because its own
     * bookkeeping wrapped around.
     * <p>
     * A counter is safe to drop only when every position it handed out is already
     * accounted for somewhere the re-seed can see: persisted in the store, or
     * attributed in {@link #undelivered}. So conversations still represented in the
     * queue, in the in-flight flush batch, or in the undelivered table are retained
     * and everything else goes. Called under no lock and takes the write lock
     * itself, so it cannot run while a submitter holds an assigned-but-not-
     * yet-queued position.
     * <p>
     * One residual case is deliberate: past {@link #MAX_TRACKED_UNDELIVERED} the
     * undelivered table stops recording, so a dead-lettered position may be reused.
     * That window already reports {@code BROKEN} by the documented fail-strict
     * rule, so the verdict is unchanged — only its reason is.
     * <p>
     * <b>Conversations with dead-lettered positions stay pinned for the process
     * lifetime, and that cannot starve the table.</b> Re-seeding them is not merely
     * inconvenient, it is unsound: the seed comes from {@code countByConversation},
     * which counts persisted rows, and a dead-lettered gap makes that count smaller
     * than the next free position. With sequences 0-9 where 3 and 5 never landed,
     * the count is 8 while the next position is 10 — so a re-seed would hand out 8
     * and 9 a second time. Accounting for the highest known undelivered position
     * does not rescue it either ({@code max(8, 6)} is still 8); a sound re-seed
     * would need a {@code maxSequence(conversationId)} that {@link IAuditStore}
     * does not expose. Retention is therefore correct, and it is bounded:
     * {@link #undeliveredTracked} counts <em>sequences</em>, so at most
     * {@link #MAX_TRACKED_UNDELIVERED} conversations can be pinned (one sequence
     * each, the worst case) out of a {@link #MAX_TRACKED_CONVERSATIONS} table —
     * leaving 80% of it evictable. {@code undeliveredPinCannotExhaustTheTable}
     * exercises that end to end: a dead-lettered conversation stays pinned while
     * the persisted ones around it are reclaimed, a later conversation still
     * receives a real position rather than {@code UNSEQUENCED}, and the pinned
     * chain resumes past its dead-lettered position instead of reusing it.
     */
    private void evictSequenceCountersIfFull(String conversationId) {
        // Fast path: nothing to do until the table is full, and a conversation
        // already tracked does not grow it.
        if (conversationSequences.size() < MAX_TRACKED_CONVERSATIONS || conversationSequences.containsKey(conversationId)) {
            return;
        }

        // A scan that evicted nothing will evict nothing again until a flush has
        // moved entries out of the queue. Without this guard, a table full of
        // genuinely live conversations makes EVERY submit for an unseen
        // conversation take the write lock and traverse the whole queue (bounded
        // at maxQueueSize, 100_000 by default) to reach the same conclusion —
        // and because submitters hold the read lock, they all queue behind it.
        // The barrier turns a per-submit scan into at most one per flush cycle.
        if (evictionBarrier.get() == flushGeneration.get()) {
            return;
        }

        sequenceLock.writeLock().lock();
        try {
            if (conversationSequences.size() < MAX_TRACKED_CONVERSATIONS) {
                return;
            }
            long generation = flushGeneration.get();

            Set<String> retain = new HashSet<>(undelivered.keySet());
            collectConversationIds(queue, retain);
            collectConversationIds(inFlightBatch, retain);

            int before = conversationSequences.size();
            conversationSequences.keySet().removeIf(id -> !retain.contains(id));
            int evicted = before - conversationSequences.size();

            if (evicted > 0) {
                LOGGER.infov("Audit sequence table hit {0} conversations — evicted {1} fully-persisted counters; "
                        + "{2} retained as in flight", MAX_TRACKED_CONVERSATIONS, evicted, conversationSequences.size());
            } else {
                // Read AFTER the scan: a flush that completed while we scanned
                // must not be recorded as already-accounted-for, or the next
                // genuinely useful scan would be skipped.
                evictionBarrier.set(generation);
                futileEvictionScans.incrementAndGet();
                LOGGER.warnv("Audit sequence table is full ({0}) and every counter is in flight; not rescanning "
                        + "until the next flush. New conversations are recorded unsequenced meanwhile.",
                        MAX_TRACKED_CONVERSATIONS);
            }
        } finally {
            sequenceLock.writeLock().unlock();
        }
    }

    /** Add every non-null conversation id in {@code entries} to {@code target}. */
    private static void collectConversationIds(Iterable<AuditEntry> entries, Set<String> target) {
        for (AuditEntry entry : entries) {
            if (entry != null && entry.conversationId() != null) {
                target.add(entry.conversationId());
            }
        }
    }

    /**
     * Next 0-based position for {@code conversationId}, or
     * {@link AuditEntry#UNSEQUENCED} when the entry cannot be chained.
     * <p>
     * Entries without a conversation (compliance events) and stores that do not
     * round-trip the field are left unsequenced rather than signed with a value the
     * store would drop — that would make every row read as tampered.
     */
    private long nextSequence(String conversationId) {
        if (conversationId == null || conversationId.isBlank() || !auditStore.supportsSequence()) {
            return AuditEntry.UNSEQUENCED;
        }

        // Still full after eviction means every remaining counter belongs to a
        // conversation with entries in flight. Re-seeding one of those from the
        // store would hand out a position it already used, and a DUPLICATE is
        // reported as BROKEN — the ledger accusing the deployment of tampering
        // because its own table filled up. An unsequenced entry instead degrades
        // the window to UNAVAILABLE ("cannot be established"), which is honest.
        // Deliberately NOT atomic with the computeIfAbsent below. Submitters share
        // the read lock, so N concurrent unseen conversations can all observe
        // "one slot left" and all insert, overshooting by up to the number of
        // concurrent callers. That is why MAX_TRACKED_CONVERSATIONS is a
        // threshold that triggers eviction rather than a hard ceiling: the
        // overshoot is bounded by concurrency, self-corrects at the next
        // eviction, and costs one AtomicLong per excess conversation. Making it
        // exact would mean serialising the insert path — a lock on every submit
        // for a new conversation — to enforce a bound that is a memory heuristic,
        // not a correctness property.
        if (!conversationSequences.containsKey(conversationId) && conversationSequences.size() >= MAX_TRACKED_CONVERSATIONS) {
            LOGGER.warnv("Audit sequence table is at its {0}-conversation threshold with every counter in flight — "
                    + "new conversations are recorded unsequenced until it drains", MAX_TRACKED_CONVERSATIONS);
            return AuditEntry.UNSEQUENCED;
        }

        try {
            AtomicLong counter = conversationSequences.computeIfAbsent(conversationId, id -> new AtomicLong(auditStore.countByConversation(id)));
            return counter.getAndIncrement();
        } catch (Exception e) {
            // A failed seed must not fabricate a duplicate sequence — an unsequenced
            // entry still verifies, it just does not participate in the chain.
            LOGGER.warnv("Could not seed audit sequence for conversation {0}: {1}", sanitize(conversationId), e.getMessage());
            return AuditEntry.UNSEQUENCED;
        }
    }

    /**
     * Flush pending entries to the audit store in a batch.
     */
    // synchronized: the scheduled writer thread and the @PreDestroy final flush
    // can otherwise poll interleaved halves of the queue into two batches, and
    // `inFlightBatch` below would only describe one of them.
    synchronized void flush() {
        if (queue.isEmpty())
            return;

        List<AuditEntry> batch = new ArrayList<>();
        // Draining and publishing must be atomic WITH RESPECT TO EVICTION: an
        // entry that has been polled but not yet published is in neither `queue`
        // nor `inFlightBatch`, and an eviction landing in that window would read
        // its conversation as fully persisted and re-seed it — reintroducing the
        // duplicate this whole mechanism exists to prevent. The read lock is the
        // same one submitters hold, so this only ever contends with eviction, and
        // no I/O happens inside it.
        sequenceLock.readLock().lock();
        try {
            AuditEntry entry;
            while ((entry = queue.poll()) != null) {
                queueSize.decrementAndGet();
                batch.add(entry);
            }
            inFlightBatch = batch;
        } finally {
            sequenceLock.readLock().unlock();
        }

        if (!batch.isEmpty()) {
            try {
                auditStore.appendBatch(batch);
                consecutiveFailures.set(0);
            } catch (Exception e) {
                int failures = consecutiveFailures.incrementAndGet();
                LOGGER.errorv("Failed to flush {0} audit entries (attempt {1}/{2}): {3}", batch.size(), failures, MAX_FLUSH_RETRIES, e.getMessage());

                if (failures < MAX_FLUSH_RETRIES) {
                    // Re-queue entries at the front so the next flush retries them.
                    // The re-offer respects the bound: whatever no longer fits goes
                    // straight to the dead-letter sink instead of growing the heap.
                    List<AuditEntry> rejected = new ArrayList<>();
                    for (int i = batch.size() - 1; i >= 0; i--) {
                        if (!offerBounded(batch.get(i))) {
                            rejected.add(batch.get(i));
                        }
                    }
                    LOGGER.warnv("Re-queued {0} audit entries for retry", batch.size() - rejected.size());
                    if (!rejected.isEmpty()) {
                        LOGGER.errorv("Audit queue full — dead-lettering {0} entries that did not fit on retry", rejected.size());
                        writeToDeadLetter(rejected);
                    }
                } else {
                    LOGGER.errorv("Dropping {0} audit entries after {1} consecutive failures — writing to dead-letter log", batch.size(),
                            MAX_FLUSH_RETRIES);
                    droppedCounter.increment(batch.size());
                    writeToDeadLetter(batch);
                    consecutiveFailures.set(0);
                }
            } finally {
                // Every outcome has put these positions somewhere eviction can see
                // again: persisted, re-queued, or recorded in `undelivered`.
                inFlightBatch = List.of();
                // Entries have moved, so a previously futile eviction scan may now
                // find something. Bump last, once the move is visible.
                flushGeneration.incrementAndGet();
            }
        }
    }

    /**
     * Check if the audit ledger is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether entries are being written with an HMAC integrity signature. False
     * when no vault master key is configured — the ledger is then a plain log, not
     * evidence.
     */
    public boolean isSigningEnabled() {
        return hmacKey != null;
    }

    /**
     * Re-check a stored entry's HMAC against the deployment's signing key.
     * <p>
     * This is the only place the key is used for verification; it is deliberately
     * kept inside the service so no caller has to hold key material. Backs the
     * admin verification endpoint — without it {@link AuditHmac#verifyHmac} had no
     * production caller at all, and the ledger shipped with tamper detection that
     * nothing ever ran.
     *
     * @param entry
     *            a stored entry, as read back from {@link IAuditStore}
     * @return the verification outcome for that entry
     */
    public AuditVerificationStatus verifyEntry(AuditEntry entry) {
        return verifyEntry(entry, newRecoveryBudget());
    }

    /**
     * Re-check a stored entry, spending legacy-recovery searches from a budget the
     * caller's whole sweep shares.
     *
     * @param entry
     *            a stored entry, as read back from {@link IAuditStore}
     * @param recoveryBudget
     *            from {@link #newRecoveryBudget()}, created once per sweep
     * @return the verification outcome for that entry
     */
    public AuditVerificationStatus verifyEntry(AuditEntry entry, AuditRecoveryBudget recoveryBudget) {
        if (entry == null) {
            return AuditVerificationStatus.INVALID;
        }
        if (hmacKey == null) {
            return AuditVerificationStatus.SIGNING_DISABLED;
        }
        if (entry.hmac() == null || entry.hmac().isBlank()) {
            return AuditVerificationStatus.UNSIGNED;
        }
        return switch (AuditHmac.verify(entry, hmacKey, recoveryBudget)) {
            case MATCH -> AuditVerificationStatus.VALID;
            case MATCH_RECOVERED -> AuditVerificationStatus.VALID_RECOVERED;
            case MISMATCH -> AuditVerificationStatus.INVALID;
        };
    }

    /**
     * A recovery budget for one verification sweep, sized by
     * {@code eddi.audit.verify.recover-legacy-max-rows}.
     */
    public AuditRecoveryBudget newRecoveryBudget() {
        return recoverLegacyTimestamps ? new AuditRecoveryBudget(recoverLegacyMaxRows) : AuditRecoveryBudget.none();
    }

    /**
     * Reduces an entry's payload maps to the JSON-native shape the stores persist.
     * <p>
     * The pipeline hands this service <em>live Java objects</em>: a turn's
     * {@code output} is a list of {@code TextOutputItem} POJOs, not of Maps. The
     * signature was computed over those objects while verification later ran over
     * whatever JSON gave back — and the two canonicalize completely differently, a
     * POJO as {@code s:<toString()>} and its round-tripped form as
     * {@code m&#123;…&#125;}. Verified against a real PostgreSQL container, a
     * single output item signed as {@code {output=[Hi there!]}} came back as
     * {@code {output=[{text=Hi there!, type=text, delay=0}]}} and could never
     * verify.
     * <p>
     * This is the same defect class as the nanosecond timestamp, and it takes the
     * same cure: normalise first, then sign, so the row that lands in the database
     * is byte-for-byte the row that was signed. Fixing only the timestamp left
     * every entry carrying rendered output — on a rule-based turn, the majority of
     * them — still reporting as tampered.
     * <p>
     * Deliberately non-fatal: an audit write must never break the turn it records.
     * A value the mapper cannot convert keeps its original form, which verifies no
     * worse than it did before and is visible in the ledger's own verify report.
     */
    private AuditEntry normalizePayloadsForStorage(AuditEntry entry) {
        Map<String, Object> input = normalizeMap(entry.input(), entry.id());
        Map<String, Object> output = normalizeMap(entry.output(), entry.id());
        Map<String, Object> llmDetail = normalizeMap(entry.llmDetail(), entry.id());
        Map<String, Object> toolCalls = normalizeMap(entry.toolCalls(), entry.id());

        if (input == entry.input() && output == entry.output()
                && llmDetail == entry.llmDetail() && toolCalls == entry.toolCalls()) {
            return entry;
        }
        return new AuditEntry(entry.id(), entry.conversationId(), entry.agentId(), entry.agentVersion(),
                entry.userId(), entry.environment(), entry.stepIndex(), entry.taskId(), entry.taskType(),
                entry.taskIndex(), entry.durationMs(), input, output, llmDetail, toolCalls, entry.actions(),
                entry.cost(), entry.timestamp(), entry.hmac(), entry.agentSignature(), entry.sequence());
    }

    /**
     * @return the JSON-native form of {@code map}, or {@code map} itself if it
     *         cannot be converted
     */
    private Map<String, Object> normalizeMap(Map<String, Object> map, String entryId) {
        if (map == null || map.isEmpty()) {
            return map;
        }
        try {
            return objectMapper.convertValue(map, MAP_OF_OBJECT);
        } catch (IllegalArgumentException e) {
            LOGGER.warnf("Audit entry %s carries a payload value that cannot be normalised (%s); "
                    + "it is stored as-is and will not verify.", LogSanitizer.sanitize(entryId), e.getMessage());
            return map;
        }
    }

    /**
     * Apply the agent's Ed25519 signature to the entry. Signs the HMAC if available
     * (covers full entry integrity), otherwise signs the entry ID. Gracefully
     * returns the original entry if no signing key exists.
     */
    private AuditEntry applyAgentSignature(AuditEntry entry) {
        try {
            String payload = entry.hmac() != null ? entry.hmac() : entry.id();
            String signature = agentSigningService.sign(defaultTenantId, entry.agentId(), payload);
            return entry.withAgentSignature(signature);
        } catch (AgentSigningService.AgentSigningException e) {
            // No signing key for this agent — this is expected for agents without identity
            // setup.
            // Log at debug level to avoid noise.
            LOGGER.debugv("Agent signing skipped for agent '{0}': {1}", entry.agentId(), e.getMessage());
            return entry;
        }
    }

    /**
     * Chain positions this node handed out to entries that never reached the store
     * (a full queue on the retry path, or a batch dead-lettered after
     * {@code MAX_FLUSH_RETRIES} consecutive store failures).
     * <p>
     * Without this, the back-pressure protection manufactures the tamper verdict:
     * the missing numbers look exactly like deleted rows to
     * {@code /auditstore/verify}. A gap listed here is attributable to the ledger
     * itself and is reported as {@code ChainStatus.INCOMPLETE} rather than
     * {@code BROKEN}.
     * <p>
     * Node-local and non-persistent by design — it is an <em>exculpatory</em>
     * record, so losing it on restart or on another cluster node can only make the
     * verdict stricter, never laxer. The dead-letter sink (NATS or the JSONL file)
     * remains the durable evidence.
     *
     * @param conversationId
     *            the conversation to ask about
     * @return the undelivered positions, or an empty set
     */
    public Set<Long> undeliveredSequences(String conversationId) {
        if (conversationId == null) {
            return Set.of();
        }
        Set<Long> tracked = undelivered.get(conversationId);
        return tracked == null ? Set.of() : Set.copyOf(tracked);
    }

    /**
     * Remember the chain positions of entries that are being abandoned. Bounded by
     * {@link #MAX_TRACKED_UNDELIVERED}; past that the drops go unattributed and the
     * verifier reports them as {@code BROKEN} (fail-strict).
     */
    private void recordUndelivered(List<AuditEntry> entries) {
        for (AuditEntry entry : entries) {
            if (entry == null || entry.conversationId() == null || entry.sequence() == AuditEntry.UNSEQUENCED) {
                continue;
            }
            if (undeliveredTracked.get() >= MAX_TRACKED_UNDELIVERED) {
                LOGGER.errorv("Undelivered-sequence table is full ({0}) — further dropped audit entries cannot be "
                        + "distinguished from deleted ones by /auditstore/verify", MAX_TRACKED_UNDELIVERED);
                return;
            }
            if (undelivered.computeIfAbsent(entry.conversationId(), id -> ConcurrentHashMap.newKeySet()).add(entry.sequence())) {
                undeliveredTracked.incrementAndGet();
            }
        }
    }

    // ==================== Visible for Testing ====================

    int getQueueSize() {
        return queueSize.get();
    }

    /** Conversations currently holding a sequence counter. */
    int getTrackedConversationCount() {
        return conversationSequences.size();
    }

    /**
     * How many eviction scans found nothing to evict. The barrier exists so this
     * stays near-constant under a full table of live conversations rather than
     * growing once per submit, which is what the test asserts.
     */
    long getFutileEvictionScans() {
        return futileEvictionScans.get();
    }

    byte[] getHmacKey() {
        return hmacKey;
    }

    // ==================== Private Helpers ====================

    /**
     * Scrub potential secrets from string values in the entry's maps.
     */
    private static AuditEntry scrubSecrets(AuditEntry entry) {
        return new AuditEntry(entry.id(), entry.conversationId(), entry.agentId(), entry.agentVersion(), entry.userId(), entry.environment(),
                entry.stepIndex(), entry.taskId(), entry.taskType(), entry.taskIndex(), entry.durationMs(), scrubMap(entry.input()),
                scrubMap(entry.output()), scrubMap(entry.llmDetail()), scrubMap(entry.toolCalls()), entry.actions(), entry.cost(), entry.timestamp(),
                null, // HMAC not yet computed
                null, // agentSignature
                entry.sequence());
    }

    private static Map<String, Object> scrubMap(Map<String, Object> map) {
        if (map == null || map.isEmpty())
            return map;

        Map<String, Object> scrubbed = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, Object> e : map.entrySet()) {
            scrubbed.put(e.getKey(), scrubValue(e.getValue()));
        }
        return scrubbed;
    }

    @SuppressWarnings("unchecked")
    private static Object scrubValue(Object value) {
        if (value instanceof String s) {
            return SecretRedactionFilter.redact(s);
        } else if (value instanceof Map<?, ?> nested) {
            return scrubMap((Map<String, Object>) nested);
        } else if (value instanceof List<?> list) {
            return list.stream().map(AuditLedgerService::scrubValue).toList();
        }
        return value;
    }

    /**
     * Write dropped entries to a dead-letter destination. Tries NATS JetStream
     * first (when nats profile is active and connection is available), falls back
     * to a local file-based dead-letter log.
     */
    private void writeToDeadLetter(List<AuditEntry> entries) {
        // These entries already own a chain position, so their absence from the
        // store is a real gap. Remember it, so the verification endpoint can say
        // "this ledger never persisted these" instead of "someone deleted them".
        recordUndelivered(entries);

        // Try NATS JetStream first
        if (natsConnectionInstance != null && natsConnectionInstance.isResolvable()) {
            try {
                Connection conn = natsConnectionInstance.get();
                if (conn.getStatus() == Connection.Status.CONNECTED) {
                    JetStream js = conn.jetStream();
                    for (AuditEntry entry : entries) {
                        String payload = serializeDeadLetterEntry(entry, "audit_dead_letter");
                        js.publish("eddi.deadletter.audit", payload.getBytes(StandardCharsets.UTF_8));
                    }
                    LOGGER.infov("Published {0} audit dead-letter entries to NATS JetStream", entries.size());
                    return;
                }
            } catch (Exception e) {
                LOGGER.warnv("NATS dead-letter publish failed, falling back to file: {0}", e.getMessage());
            }
        }

        // Fallback: file-based dead-letter log
        try {
            Path dlPath = Path.of(deadLetterPath);
            var lines = new ArrayList<String>(entries.size());
            for (AuditEntry entry : entries) {
                lines.add(serializeDeadLetterEntry(entry, null));
            }
            Files.write(dlPath, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LOGGER.infov("Wrote {0} entries to dead-letter log: {1}", entries.size(), dlPath.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.errorv("Failed to write to dead-letter log: {0}", e.getMessage());
        }
    }

    /**
     * Serializes a dead-letter entry as a JSON string using Jackson for correct
     * escaping of all field values.
     *
     * @param entry
     *            the audit entry to serialize
     * @param type
     *            optional type field (e.g. "audit_dead_letter" for NATS), null for
     *            file output
     * @return JSON string
     */
    String serializeDeadLetterEntry(AuditEntry entry, String type) {
        Map<String, Object> dlMap = new LinkedHashMap<>();
        if (type != null) {
            dlMap.put("type", type);
        }
        dlMap.put("timestamp", Instant.now().toString());
        dlMap.put("conversationId", entry.conversationId());
        dlMap.put("agentId", entry.agentId());
        dlMap.put("taskId", entry.taskId());
        dlMap.put("taskType", entry.taskType());

        try {
            return objectMapper.writeValueAsString(dlMap);
        } catch (JsonProcessingException e) {
            // Absolute fallback — should never happen with simple string maps.
            // Do NOT embed entry fields here: we'd reintroduce the escaping bug.
            LOGGER.errorv("Jackson serialization failed for dead-letter entry: {0}", e.getMessage());
            return "{\"error\":\"serialization_failed\"}";
        }
    }
}
