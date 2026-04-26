/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

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

    private byte[] hmacKey;
    private final ConcurrentLinkedQueue<AuditEntry> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private ScheduledExecutorService flushExecutor;

    @Inject
    public AuditLedgerService(IAuditStore auditStore, @ConfigProperty(name = "eddi.audit.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.audit.flush-interval-seconds", defaultValue = "3") int flushIntervalSeconds,
            @ConfigProperty(name = "eddi.vault.master-key") Optional<String> masterKeyConfig,
            @ConfigProperty(name = "eddi.audit.dead-letter-path", defaultValue = "/opt/eddi/data/eddi-audit-deadletter.jsonl") String deadLetterPath,
            @ConfigProperty(name = "eddi.audit.agent-signing-enabled", defaultValue = "true") boolean agentSigningEnabled,
            @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId,
            io.micrometer.core.instrument.MeterRegistry meterRegistry, Instance<Connection> natsConnectionInstance,
            AgentSigningService agentSigningService, ObjectMapper objectMapper) {
        this.auditStore = auditStore;
        this.enabled = enabled;
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.masterKeyConfig = masterKeyConfig;
        this.deadLetterPath = deadLetterPath;
        this.agentSigningEnabled = agentSigningEnabled;
        this.defaultTenantId = defaultTenantId;
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
        return new AuditLedgerService(auditStore, enabled, flushIntervalSeconds, Optional.ofNullable(masterKeyConfig), "eddi-audit-deadletter.jsonl",
                false, "default", meterRegistry, null, null, new ObjectMapper());
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
                    // Re-queue entries at the front so the next flush retries them
                    for (int i = batch.size() - 1; i >= 0; i--) {
                        queue.offer(batch.get(i));
                    }
                    LOGGER.warnv("Re-queued {0} audit entries for retry", batch.size());
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
        return queue.size();
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
                null // agentSignature
        );
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
