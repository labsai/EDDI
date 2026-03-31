package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;

/**
 * Functional interface for collecting audit entries from the lifecycle
 * workflow.
 * <p>
 * Implementations receive audit data after each task completes and delegate to
 * {@link AuditLedgerService} for async persistence.
 * <p>
 * This keeps the {@code LifecycleManager} decoupled from storage concerns — it
 * produces data, doesn't know about persistence.
 *
 * @author ginccc
 * @since 6.0.0
 */
@FunctionalInterface
public interface IAuditEntryCollector {

    /**
     * Collect a completed task's audit entry for persistence.
     *
     * @param entry
     *            the audit entry to persist
     */
    void collect(AuditEntry entry);
}
