/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

    /**
     * A collector that reports whether the ledger took the entry. The ledger drops
     * an entry (counted, logged) when its queue is full; a caller that is about to
     * remove its own copy of the evidence must know that did not happen.
     */
    interface Acknowledging extends IAuditEntryCollector {

        /**
         * @return true when the entry is now owned by the ledger
         */
        boolean offer(AuditEntry entry);

        @Override
        default void collect(AuditEntry entry) {
            offer(entry);
        }
    }
}
