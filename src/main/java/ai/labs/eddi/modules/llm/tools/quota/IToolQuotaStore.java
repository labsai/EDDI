/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import java.time.Instant;
import java.util.List;

/**
 * Counters behind {@code toolQuotas}: how many calls a subject has made through
 * a tool (or pool) in a fixed window.
 * <p>
 * <strong>Atomicity contract.</strong> {@link #tryAcquire} MUST check the limit
 * and increment as one indivisible, storage-level operation, so that with three
 * calls left a batch of five concurrent calls — from one node or from several —
 * is granted exactly three. Java synchronisation is not enough; the deployment
 * runs several nodes against one database.
 * <p>
 * Every method throws {@link ToolQuotaStoreException} when the database cannot
 * answer. The caller fails closed on it.
 */
public interface IToolQuotaStore {

    /**
     * Counts one call if, and only if, the counter is still below {@code limit}.
     *
     * @param expiresAt
     *            when the row may be removed (the window's end plus a grace
     *            period); written when the row is created
     */
    Acquisition tryAcquire(ToolQuotaKey key, int limit, Instant expiresAt);

    /**
     * Gives back one call counted by {@link #tryAcquire}, because it did not run to
     * success. Never takes the counter below zero.
     */
    void release(ToolQuotaKey key);

    /**
     * Calls counted so far; {@code 0} when there is no row. A read, no increment.
     */
    int used(ToolQuotaKey key);

    /**
     * Deletes every counter of {@code subject}, for GDPR erasure.
     *
     * @return how many counters were removed
     */
    long deleteBySubject(String subject);

    /**
     * The counters held for {@code subject}, newest window first, at most
     * {@code max}.
     */
    List<ToolQuotaUsage> findBySubject(String subject, int max);

    /**
     * Removes counters past their {@code expiresAt}. Retention, not enforcement:
     * {@code tryAcquire} keys on the window, so an expired row is never read again.
     *
     * @return how many rows were removed
     */
    int deleteExpired(Instant now);

    /**
     * The outcome of {@link #tryAcquire}.
     *
     * @param granted
     *            whether the call was counted
     * @param used
     *            calls counted after this attempt; when refused, the count that
     *            caused the refusal
     */
    record Acquisition(boolean granted, int used) {
    }
}
