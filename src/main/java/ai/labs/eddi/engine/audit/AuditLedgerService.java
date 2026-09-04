/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
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

/**
 * Async batch writer for the immutable audit ledger.
 * <p>
 * Follows the same pattern as
 * {@link ai.labs.eddi.engine.runtime.BoundedLogStore}: capture into a bounded
 * {@link ConcurrentLinkedQueue}, with a {@link ScheduledExecutorService}
 * flushing entries to {@link IAuditStore} at a configurable interval.
 * <p>
 * <strong>{@link #submit} does not touch the store, but it is not
 * free.</strong> Before an entry is queued the caller's thread scrubs it,
 * assigns its chain position, hashes it and signs it — CPU-bound work
 * proportional to the size of the prompts and responses being recorded. What it
 * no longer does is I/O: seeding a conversation's sequence counter reads the
 * store, and that read used to happen inside
 * {@code ConcurrentHashMap.computeIfAbsent} while holding the sequence read
 * lock, so one slow query stalled every other submitter that hashed to the same
 * bin as well as any eviction waiting for the write lock (a
 * {@code ReentrantReadWriteLock} refuses new readers once a writer has queued).
 * The seed is now resolved by {@link #prewarmSequenceCounter} before either is
 * taken.
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

    /**
     * How many consecutive refusals the per-entry fallback tolerates before it
     * declares the store unavailable and stops calling it for the rest of the
     * batch. See {@link #appendIndividually}.
     */
    static final int MAX_CONSECUTIVE_ENTRY_FAILURES = 10;

    /** Default bound for {@code eddi.audit.max-queue-size}. */
    static final int DEFAULT_MAX_QUEUE_SIZE = 100_000;

    /**
     * Fallback for {@code eddi.audit.flush-interval-seconds} when the configured
     * value is not a usable period. Mirrors the {@code defaultValue} on the config
     * property. An operator setting 0 in the hope of "flush immediately" used to
     * take the whole application down at {@code @PostConstruct} with an
     * {@code IllegalArgumentException("period <= 0")} thrown from inside the
     * executor API — a stack trace that never names the property responsible.
     */
    static final int DEFAULT_FLUSH_INTERVAL_SECONDS = 3;

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
    /**
     * Set by {@link #shutdown()} before the final flush. A failure after this point
     * has no later attempt, so {@link #flush()} dead-letters instead of re-queuing.
     */
    private volatile boolean shuttingDown = false;
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
        if (flushIntervalSeconds > 0) {
            this.flushIntervalSeconds = flushIntervalSeconds;
        } else {
            this.flushIntervalSeconds = DEFAULT_FLUSH_INTERVAL_SECONDS;
            LOGGER.warnv("eddi.audit.flush-interval-seconds must be a positive number of seconds; {0} is not a period. "
                    + "Falling back to the default of {1}s.", flushIntervalSeconds, DEFAULT_FLUSH_INTERVAL_SECONDS);
        }
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

        checkDeadLetterSinkReachable();

        flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-ledger-writer");
            t.setDaemon(true);
            return t;
        });
        flushExecutor.scheduleAtFixedRate(this::flush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);

        LOGGER.infov("Audit Ledger initialized (flush every {0}s)", flushIntervalSeconds);
    }

    /**
     * Report at startup whether the last-resort sink can actually be written.
     * <p>
     * The default path lives under {@code /opt/eddi/data}, which only the
     * Kubernetes manifests mount — the shipped container image never creates it and
     * the runtime user cannot create it under {@code /opt}. So on the documented
     * docker/docker-compose quick start every dead-letter write threw
     * {@code NoSuchFileException} into a swallowed catch, and the entries the
     * ledger abandoned were gone outright rather than recoverable. Discovering that
     * during the incident, from an error line nested inside the error line that
     * reported the drop, is the wrong time; {@link #writeToDeadLetter} now also
     * creates the directory, and this says so up front if it cannot.
     */
    private void checkDeadLetterSinkReachable() {
        try {
            Path parent = Path.of(deadLetterPath).toAbsolutePath().getParent();
            if (parent == null || Files.isDirectory(parent)) {
                return;
            }
            Files.createDirectories(parent);
            LOGGER.infov("Created audit dead-letter directory {0}", parent);
        } catch (Exception e) {
            LOGGER.warnv("Audit dead-letter sink {0} is not writable ({1}). Entries the ledger has to abandon "
                    + "will be lost rather than recoverable — point eddi.audit.dead-letter-path at a writable "
                    + "location or mount a volume there.", deadLetterPath, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (flushExecutor != null) {
            // Tells flush() that a failure has no next attempt: re-queuing would put
            // entries into a queue nothing will ever drain again, losing them with
            // neither a dead-letter record nor a dropped-counter increment.
            shuttingDown = true;
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
            // Anything submitted while the final flush was running is still storable
            // — the drain below records it as dropped, so try the store once more
            // first. On a healthy store this persists it; on a failing one
            // shuttingDown makes flush() dead-letter it directly, and the drain then
            // finds nothing.
            flush();
            // Anything submitted during THAT flush has no flush left — the executor
            // is down and the scheduled task will never run again — so the sink is
            // the only thing between it and silent loss.
            drainQueueToDeadLetter();
        }
    }

    /**
     * Move anything still queued after the final flush to the dead-letter sink.
     * Covers entries submitted while the last flush was running, which no scheduled
     * flush will ever pick up — and which a healthy store would have accepted, so
     * {@link #shutdown()} attempts one more flush before calling this rather than
     * recording them as dropped without ever offering them.
     */
    private void drainQueueToDeadLetter() {
        List<AuditEntry> remaining = new ArrayList<>();
        AuditEntry entry;
        while ((entry = queue.poll()) != null) {
            queueSize.decrementAndGet();
            remaining.add(entry);
        }
        if (!remaining.isEmpty()) {
            LOGGER.errorv("Audit ledger shut down with {0} unflushed entries — writing them to the dead-letter sink", remaining.size());
            droppedCounter.increment(remaining.size());
            writeToDeadLetter(remaining);
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

        // Seed this conversation's counter BEFORE taking the lock. Seeding reads the
        // store, and doing that under the read lock stalled every other submitter
        // (a ReentrantReadWriteLock refuses new readers once eviction has queued for
        // the write lock) as well as eviction itself.
        prewarmSequenceCounter(entry.conversationId());

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
     * "re-seeding is correct, only slower". It is not: the counter is re-seeded
     * from the store (see {@link #seedSequence}), which sees only what it already
     * holds. Entries sit in {@link #queue} for up to one flush interval — longer
     * while a failing store is being retried — so clearing mid-flight re-issued
     * positions those entries had already consumed. Duplicates are graded exactly
     * like gaps ({@code ChainStatus.BROKEN}), and unlike a gap there is no
     * exculpatory record for them: the ledger would report the deployment as
     * tampered because its own bookkeeping wrapped around.
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
     * lifetime, and that cannot starve the table.</b> The pin is now
     * belt-and-braces rather than the only thing holding the chain together:
     * {@link #seedSequence} re-seeds from {@link IAuditStore#maxSequence} + 1, so
     * even an evicted counter resumes past its dead-lettered positions instead of
     * reusing them. (It used to seed from {@code countByConversation}, which counts
     * persisted rows: with sequences 0-9 where 3 and 5 never landed the count is 8
     * while the next position is 10, so a re-seed handed out 8 and 9 a second time
     * — and duplicates are graded {@code BROKEN}.) Retaining them keeps the
     * attribution table and the counter consistent for the process's lifetime, and
     * it is bounded: {@link #undeliveredTracked} counts <em>sequences</em>, so at
     * most {@link #MAX_TRACKED_UNDELIVERED} conversations can be pinned (one
     * sequence each, the worst case) out of a {@link #MAX_TRACKED_CONVERSATIONS}
     * table — leaving 80% of it evictable.
     * {@code undeliveredPinCannotExhaustTheTable} exercises that end to end: a
     * dead-lettered conversation stays pinned while the persisted ones around it
     * are reclaimed, a later conversation still receives a real position rather
     * than {@code UNSEQUENCED}, and the pinned chain resumes past its dead-lettered
     * position instead of reusing it.
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
            // Normally already present — prewarmSequenceCounter resolved the seed
            // outside this lock. The inline path only runs when an eviction landed in
            // between, and it still resolves the seed OUTSIDE the mapping function:
            // ConcurrentHashMap holds a bin lock for the duration of computeIfAbsent,
            // and its contract forbids long-running work there. A store round trip
            // inside it blocked every other submitter that hashed to the same bin.
            AtomicLong counter = conversationSequences.get(conversationId);
            if (counter == null) {
                long seed = seedSequence(conversationId);
                counter = conversationSequences.computeIfAbsent(conversationId, id -> new AtomicLong(seed));
            }
            return counter.getAndIncrement();
        } catch (Exception e) {
            // A failed seed must not fabricate a duplicate sequence — an unsequenced
            // entry still verifies, it just does not participate in the chain.
            LOGGER.warnv("Could not seed audit sequence for conversation {0}: {1}", sanitize(conversationId), e.getMessage());
            return AuditEntry.UNSEQUENCED;
        }
    }

    /**
     * Resolve a conversation's sequence counter ahead of the assignment lock, so
     * the store round trip the seed needs happens on no lock and inside no
     * {@code ConcurrentHashMap} mapping function.
     * <p>
     * Two submitters racing here both compute the same seed and one insert wins, so
     * the duplicate work is harmless. An eviction that drops the counter
     * immediately afterwards is harmless too: nothing has been handed out yet, so
     * the re-seed produces the same number.
     */
    private void prewarmSequenceCounter(String conversationId) {
        if (conversationId == null || conversationId.isBlank() || !auditStore.supportsSequence()) {
            return;
        }
        if (conversationSequences.containsKey(conversationId) || conversationSequences.size() >= MAX_TRACKED_CONVERSATIONS) {
            return;
        }
        try {
            long seed = seedSequence(conversationId);
            conversationSequences.computeIfAbsent(conversationId, id -> new AtomicLong(seed));
        } catch (Exception e) {
            // Left unseeded on purpose: nextSequence retries and, if that fails too,
            // records the entry as UNSEQUENCED rather than guessing a position.
            LOGGER.warnv("Could not pre-seed audit sequence for conversation {0}: {1}", sanitize(conversationId), e.getMessage());
        }
    }

    /**
     * The next free chain position for a conversation, as the store sees it.
     * <p>
     * {@code max(sequence) + 1}, not {@code countByConversation()}. The count is
     * the number of rows that landed, which stops matching the next free position
     * the moment one was handed out and never persisted — a dead-lettered entry, a
     * dropped batch. Seeding from the count then re-issues positions that are
     * already spoken for, and {@code /auditstore/verify} grades duplicates as
     * {@code BROKEN}: the ledger reporting the deployment as tampered because of
     * its own bookkeeping. The in-memory {@code undelivered} pin only held that off
     * within one process lifetime and on one node.
     * <p>
     * Falls back to the count for a store that does not implement
     * {@link IAuditStore#maxSequence} (legacy rows that predate the sequence column
     * report {@link AuditEntry#UNSEQUENCED}, and counting them is what the chain
     * verifier already expects — see {@code RestAuditStore.checkChain}).
     */
    private long seedSequence(String conversationId) {
        long max = auditStore.maxSequence(conversationId);
        return max >= 0 ? max + 1 : auditStore.countByConversation(conversationId);
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
                LOGGER.errorv("Failed to flush {0} audit entries as a batch ({1}) — retrying them individually", batch.size(), e.getMessage());
                // A bulk write is not atomic in either backend, so re-offering the
                // whole batch punished every entry for one bad row: the retry carried
                // the rows that HAD landed back into the store (duplicate keys), so
                // nothing new was written, and after MAX_FLUSH_RETRIES three flush
                // windows of unrelated conversations' records were dead-lettered
                // together. Retry per entry instead — both stores' single-entry
                // inserts are idempotent — so only the entry that genuinely cannot
                // be stored is abandoned.
                List<AuditEntry> unstorable = appendIndividually(batch);
                if (unstorable.isEmpty()) {
                    consecutiveFailures.set(0);
                } else {
                    handlePersistentFailures(unstorable);
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
     * Persist a failed batch one entry at a time.
     * <p>
     * Both stores make a single-entry insert idempotent (MongoDB ignores
     * {@code E11000}, PostgreSQL uses {@code ON CONFLICT (id) DO NOTHING}), so the
     * rows an aborted bulk write already committed are simply re-accepted here and
     * only the genuinely unstorable ones come back.
     *
     * <b>The pass gives up after {@link #MAX_CONSECUTIVE_ENTRY_FAILURES}
     * consecutive refusals.</b> The fallback exists to isolate a poison entry from
     * the batch around it, which needs only enough attempts to tell one bad row
     * from a bad store. An outage refuses every one of them, and this method runs
     * on the writer thread inside {@code synchronized flush()} — the same monitor
     * the {@code @PreDestroy} final flush waits on. Without a cap, a queue grown
     * toward {@link #DEFAULT_MAX_QUEUE_SIZE} against an unreachable PostgreSQL
     * (connect timeouts measured in tens of seconds) would block both for hours,
     * where the old whole-batch retry failed once per flush window. The remaining
     * entries are returned unstorable without a store call, which puts them back on
     * the queue for the next flush exactly as if they had been attempted.
     *
     * @return the entries that still could not be stored
     */
    private List<AuditEntry> appendIndividually(List<AuditEntry> batch) {
        List<AuditEntry> unstorable = new ArrayList<>();
        int consecutiveEntryFailures = 0;
        for (int i = 0; i < batch.size(); i++) {
            AuditEntry entry = batch.get(i);
            if (consecutiveEntryFailures >= MAX_CONSECUTIVE_ENTRY_FAILURES) {
                int abandoned = batch.size() - i;
                LOGGER.errorv("Audit store refused {0} entries in a row — treating it as unavailable and abandoning the "
                        + "per-entry retry for the remaining {1} of this batch", MAX_CONSECUTIVE_ENTRY_FAILURES, abandoned);
                unstorable.addAll(batch.subList(i, batch.size()));
                break;
            }
            try {
                auditStore.appendEntry(entry);
                consecutiveEntryFailures = 0;
            } catch (Exception e) {
                consecutiveEntryFailures++;
                LOGGER.errorv("Audit entry {0} (conversation {1}, sequence {2}) could not be stored: {3}", sanitize(entry.id()),
                        sanitize(entry.conversationId()), entry.sequence(), e.getMessage());
                unstorable.add(entry);
            }
        }
        return unstorable;
    }

    /**
     * Decide what happens to entries the per-entry retry could not store either:
     * one more pass through the queue while attempts remain, the dead-letter sink
     * once they are exhausted — and always the sink during shutdown, because there
     * is no later flush to retry them.
     */
    private void handlePersistentFailures(List<AuditEntry> unstorable) {
        if (shuttingDown) {
            LOGGER.errorv("Audit ledger is shutting down — dead-lettering {0} entries that could not be stored", unstorable.size());
            droppedCounter.increment(unstorable.size());
            writeToDeadLetter(unstorable);
            return;
        }

        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= MAX_FLUSH_RETRIES) {
            LOGGER.errorv("Dropping {0} audit entries after {1} consecutive failures — writing to dead-letter log", unstorable.size(),
                    MAX_FLUSH_RETRIES);
            droppedCounter.increment(unstorable.size());
            writeToDeadLetter(unstorable);
            consecutiveFailures.set(0);
            return;
        }

        // Appended for retry — a ConcurrentLinkedQueue has no front insertion, so
        // these go behind whatever was submitted meanwhile. (The loop used to run
        // backwards under a comment claiming front insertion, which only reversed
        // the batch.) The re-offer respects the queue bound: whatever no longer
        // fits goes straight to the dead-letter sink instead of growing the heap.
        List<AuditEntry> rejected = new ArrayList<>();
        for (AuditEntry entry : unstorable) {
            if (!offerBounded(entry)) {
                rejected.add(entry);
            }
        }
        LOGGER.warnv("Re-queued {0} audit entries for retry (attempt {1}/{2})", unstorable.size() - rejected.size(), failures, MAX_FLUSH_RETRIES);
        if (!rejected.isEmpty()) {
            // Not counted here: reserveQueueSlot already increments droppedCounter
            // once per refusal.
            LOGGER.errorv("Audit queue full — dead-lettering {0} entries that did not fit on retry", rejected.size());
            writeToDeadLetter(rejected);
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

    /**
     * The period the flush task actually runs at, after the constructor has
     * replaced an unusable configured value with
     * {@link #DEFAULT_FLUSH_INTERVAL_SECONDS}.
     */
    int getFlushIntervalSeconds() {
        return flushIntervalSeconds;
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
            // StandardOpenOption.CREATE creates the file, never its parent — and the
            // default path's parent does not exist in the shipped container image.
            Path parent = dlPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(dlPath, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LOGGER.infov("Wrote {0} entries to dead-letter log: {1}", entries.size(), dlPath.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.errorv("Failed to write to dead-letter log: {0}", e.getMessage());
        }
    }

    /**
     * Serializes a dead-letter record as a JSON string using Jackson for correct
     * escaping of all field values.
     * <p>
     * The record carries the <em>whole</em> entry, not a five-field summary. The
     * summary was neither replayable nor the "durable evidence"
     * {@link #undeliveredSequences} rests its INCOMPLETE-not-BROKEN verdict on:
     * with no {@code sequence} in it, an operator who restarted after a dead-letter
     * event had no way to prove which positions the ledger itself dropped, so every
     * self-inflicted gap read as {@code BROKEN} forever — and the audit content,
     * the HMAC and the agent signature were gone outright.
     * <p>
     * Fields are written individually rather than by serializing the record, so the
     * output does not depend on the injected mapper having a JSR-310 module
     * registered. {@code timestamp} keeps its original meaning — the moment the
     * record was abandoned — and the entry's own timestamp is
     * {@code entryTimestamp} so existing sink consumers keep reading what they read
     * before.
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
        dlMap.put("id", entry.id());
        dlMap.put("sequence", entry.sequence());
        dlMap.put("agentVersion", entry.agentVersion());
        // userId, input and output make the sink a personal-data location that the
        // GDPR erasure cascade does not reach — deliberately, because without them
        // the record is neither replayable nor evidence of what was lost. See
        // docs/gdpr-compliance.md, "The audit dead-letter sink holds personal data".
        dlMap.put("userId", entry.userId());
        dlMap.put("environment", entry.environment());
        dlMap.put("stepIndex", entry.stepIndex());
        dlMap.put("taskIndex", entry.taskIndex());
        dlMap.put("durationMs", entry.durationMs());
        dlMap.put("cost", entry.cost());
        dlMap.put("entryTimestamp", entry.timestamp() != null ? entry.timestamp().toString() : null);
        dlMap.put("hmac", entry.hmac());
        dlMap.put("agentSignature", entry.agentSignature());
        dlMap.put("actions", entry.actions());
        dlMap.put("input", entry.input());
        dlMap.put("output", entry.output());
        dlMap.put("llmDetail", entry.llmDetail());
        dlMap.put("toolCalls", entry.toolCalls());

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
