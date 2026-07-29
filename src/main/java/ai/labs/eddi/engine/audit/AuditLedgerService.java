/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

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
import java.nio.file.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

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
     * Cap on how many conversations are tracked for sequence assignment at once. On
     * overflow the whole table is dropped; the next entry for a conversation then
     * re-seeds from the store, which costs one count query and keeps the sequence
     * gap-free.
     */
    private static final int MAX_TRACKED_CONVERSATIONS = 50_000;

    private final IAuditStore auditStore;
    private final boolean enabled;
    private final int flushIntervalSeconds;
    private final Optional<String> masterKeyConfig;
    private final io.micrometer.core.instrument.Counter droppedCounter;
    private final Instance<Connection> natsConnectionInstance;
    private final String deadLetterPath;
    private final boolean agentSigningEnabled;
    private final String defaultTenantId;
    private final AgentSigningService agentSigningService;
    private final ObjectMapper objectMapper;
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
    private ScheduledExecutorService flushExecutor;

    @Inject
    public AuditLedgerService(IAuditStore auditStore, @ConfigProperty(name = "eddi.audit.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.audit.flush-interval-seconds", defaultValue = "3") int flushIntervalSeconds,
            @ConfigProperty(name = "eddi.vault.master-key") Optional<String> masterKeyConfig,
            @ConfigProperty(name = "eddi.audit.dead-letter-path", defaultValue = "/opt/eddi/data/eddi-audit-deadletter.jsonl") String deadLetterPath,
            @ConfigProperty(name = "eddi.audit.agent-signing-enabled", defaultValue = "true") boolean agentSigningEnabled,
            @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId,
            @ConfigProperty(name = "eddi.audit.max-queue-size", defaultValue = "100000") int maxQueueSize,
            io.micrometer.core.instrument.MeterRegistry meterRegistry, Instance<Connection> natsConnectionInstance,
            AgentSigningService agentSigningService, ObjectMapper objectMapper) {
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
                                               io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return createForTesting(auditStore, enabled, flushIntervalSeconds, masterKeyConfig, meterRegistry, DEFAULT_MAX_QUEUE_SIZE);
    }

    /**
     * Factory method for unit testing with an explicit queue bound.
     */
    static AuditLedgerService createForTesting(IAuditStore auditStore, boolean enabled, int flushIntervalSeconds, String masterKeyConfig,
                                               io.micrometer.core.instrument.MeterRegistry meterRegistry, int maxQueueSize) {
        return new AuditLedgerService(auditStore, enabled, flushIntervalSeconds, Optional.ofNullable(masterKeyConfig), "eddi-audit-deadletter.jsonl",
                false, "default", maxQueueSize, meterRegistry, null, null, new ObjectMapper());
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

        // Scrub secrets from string values in maps
        AuditEntry scrubbed = scrubSecrets(entry);

        // Assign the conversation chain position BEFORE signing — the sequence is
        // part of the signed payload, which is what makes a deleted entry's gap
        // impossible to close by renumbering its neighbours.
        scrubbed = scrubbed.withSequence(nextSequence(scrubbed.conversationId()));

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

        offerBounded(signed);
    }

    /**
     * Enqueue one entry, refusing it once the queue is at its bound.
     * <p>
     * The queue used to be unbounded while a failed flush re-offered its whole
     * batch back into it: a store that slows down or stops therefore turned every
     * subsequent turn's entries into permanently retained heap, and the retry path
     * fed itself. Dropping past the bound (loudly, and counted on
     * {@code eddi_audit_entries_dropped_total}) keeps a broken ledger from taking
     * the process down with it.
     *
     * @return true if the entry was queued, false if it was dropped
     */
    private boolean offerBounded(AuditEntry entry) {
        if (queueSize.get() >= maxQueueSize) {
            droppedCounter.increment();
            LOGGER.warnv("Audit queue is full ({0} entries) — dropping entry for conversation {1}. "
                    + "The audit store is not keeping up; raise eddi.audit.max-queue-size or fix the store.", maxQueueSize,
                    sanitize(entry.conversationId()));
            return false;
        }
        queue.offer(entry);
        queueSize.incrementAndGet();
        return true;
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

        if (conversationSequences.size() >= MAX_TRACKED_CONVERSATIONS) {
            // Bounded by construction: re-seeding is correct, only slower.
            LOGGER.warnv("Audit sequence table hit {0} conversations — resetting; sequences will be re-seeded from the store",
                    MAX_TRACKED_CONVERSATIONS);
            conversationSequences.clear();
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
    void flush() {
        if (queue.isEmpty())
            return;

        List<AuditEntry> batch = new ArrayList<>();
        AuditEntry entry;
        while ((entry = queue.poll()) != null) {
            queueSize.decrementAndGet();
            batch.add(entry);
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
        if (entry == null) {
            return AuditVerificationStatus.INVALID;
        }
        if (hmacKey == null) {
            return AuditVerificationStatus.SIGNING_DISABLED;
        }
        if (entry.hmac() == null || entry.hmac().isBlank()) {
            return AuditVerificationStatus.UNSIGNED;
        }
        return AuditHmac.verifyHmac(entry, hmacKey) ? AuditVerificationStatus.VALID : AuditVerificationStatus.INVALID;
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

    // ==================== Visible for Testing ====================

    int getQueueSize() {
        return queueSize.get();
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
