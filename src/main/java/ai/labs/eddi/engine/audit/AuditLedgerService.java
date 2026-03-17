package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async batch writer for the immutable audit ledger.
 * <p>
 * Follows the same pattern as {@link ai.labs.eddi.engine.runtime.BoundedLogStore}:
 * non-blocking capture via a {@link ConcurrentLinkedQueue}, with a
 * {@link ScheduledExecutorService} flushing entries to {@link IAuditStore}
 * at a configurable interval.
 * <p>
 * Before persisting, each entry passes through:
 * <ol>
 *   <li>{@link SecretRedactionFilter} — scrubs potential secrets from string values</li>
 *   <li>{@link AuditHmac} — computes HMAC-SHA256 integrity hash using the vault master key</li>
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
    private final String masterKeyConfig;

    private byte[] hmacKey;
    private final ConcurrentLinkedQueue<AuditEntry> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private ScheduledExecutorService flushExecutor;

    @Inject
    public AuditLedgerService(
            IAuditStore auditStore,
            @ConfigProperty(name = "eddi.audit.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.audit.flush-interval-seconds", defaultValue = "3") int flushIntervalSeconds,
            @ConfigProperty(name = "eddi.vault.master-key", defaultValue = "") String masterKeyConfig) {
        this.auditStore = auditStore;
        this.enabled = enabled;
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.masterKeyConfig = masterKeyConfig;
    }

    /**
     * Factory method for unit testing — creates a service without CDI.
     * Call {@link #init()} after construction.
     */
    static AuditLedgerService createForTesting(IAuditStore auditStore, boolean enabled,
                                                int flushIntervalSeconds, String masterKeyConfig) {
        return new AuditLedgerService(auditStore, enabled, flushIntervalSeconds, masterKeyConfig);
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            LOGGER.info("Audit Ledger is DISABLED (eddi.audit.enabled=false)");
            return;
        }

        if (masterKeyConfig != null && !masterKeyConfig.isBlank()) {
            this.hmacKey = AuditHmac.deriveHmacKey(masterKeyConfig);
            LOGGER.info("Audit Ledger: HMAC signing enabled (derived from vault master key)");
        } else {
            LOGGER.warn("Audit Ledger: HMAC signing DISABLED — EDDI_VAULT_MASTER_KEY not configured. " +
                    "Entries will be stored without integrity hashes.");
        }

        flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-ledger-writer");
            t.setDaemon(true);
            return t;
        });
        flushExecutor.scheduleAtFixedRate(this::flush,
                flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);

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
     * Enqueue an audit entry for async persistence.
     * Applies secret redaction and HMAC signing before queuing.
     *
     * @param entry the entry to persist (hmac field will be computed)
     */
    public void submit(AuditEntry entry) {
        if (!enabled || entry == null) return;

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

        queue.offer(signed);
    }

    /**
     * Flush pending entries to the audit store in a batch.
     */
    void flush() {
        if (queue.isEmpty()) return;

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
                LOGGER.errorv("Failed to flush {0} audit entries (attempt {1}/{2}): {3}",
                        batch.size(), failures, MAX_FLUSH_RETRIES, e.getMessage());

                if (failures < MAX_FLUSH_RETRIES) {
                    // Re-queue entries at the front so the next flush retries them
                    for (int i = batch.size() - 1; i >= 0; i--) {
                        queue.offer(batch.get(i));
                    }
                    LOGGER.warnv("Re-queued {0} audit entries for retry", batch.size());
                } else {
                    LOGGER.errorv("Dropping {0} audit entries after {1} consecutive failures",
                            batch.size(), MAX_FLUSH_RETRIES);
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
        return new AuditEntry(
                entry.id(),
                entry.conversationId(),
                entry.botId(),
                entry.botVersion(),
                entry.userId(),
                entry.environment(),
                entry.stepIndex(),
                entry.taskId(),
                entry.taskType(),
                entry.taskIndex(),
                entry.durationMs(),
                scrubMap(entry.input()),
                scrubMap(entry.output()),
                scrubMap(entry.llmDetail()),
                scrubMap(entry.toolCalls()),
                entry.actions(),
                entry.cost(),
                entry.timestamp(),
                null // HMAC not yet computed
        );
    }

    private static Map<String, Object> scrubMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return map;

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
            return list.stream()
                    .map(AuditLedgerService::scrubValue)
                    .toList();
        }
        return value;
    }
}
