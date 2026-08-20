/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

/**
 * How much legacy-timestamp recovery one verification sweep may spend.
 * <p>
 * A pre-v4 row that fails the direct check triggers a completion search worth
 * about two thousand HMAC computations. Per row that is a couple of
 * milliseconds and entirely reasonable. Per <em>sweep</em> it is not bounded at
 * all by the per-row limit: {@code /auditstore/verify} accepts a limit of ten
 * thousand entries and runs verification inline on the request thread, so a
 * page where nothing can be recovered would spend roughly twenty million HMACs
 * before answering.
 * <p>
 * "Nothing can be recovered" is not a rare case, either — it is what a ledger
 * verified with the wrong key looks like, and what genuinely tampered rows look
 * like. Exactly the situations where an operator most wants a prompt answer are
 * the ones that would take longest.
 * <p>
 * So the budget is per sweep, and rows past it are verified without the search:
 * they report {@code INVALID}, and the count of rows that went unsearched is
 * reported alongside so the result is never quietly less thorough than it
 * looks. Not thread-safe; one instance belongs to one sweep on one thread.
 *
 * @since 6.3.1
 */
public final class AuditRecoveryBudget {

    /**
     * A budget that permits no searching at all — for callers verifying a single
     * entry outside a sweep, and for deployments with recovery switched off.
     */
    public static AuditRecoveryBudget none() {
        return new AuditRecoveryBudget(0);
    }

    private final int maxSearches;
    private int searchesUsed;
    private int searchesSkipped;

    public AuditRecoveryBudget(int maxSearches) {
        this.maxSearches = Math.max(maxSearches, 0);
    }

    /**
     * Claims one recovery search.
     *
     * @return {@code true} when the sweep may still search; {@code false} once the
     *         budget is spent, counting this row as unsearched
     */
    public boolean tryConsume() {
        if (searchesUsed >= maxSearches) {
            searchesSkipped++;
            return false;
        }
        searchesUsed++;
        return true;
    }

    /** Rows that failed the direct check and were not searched. */
    public int searchesSkipped() {
        return searchesSkipped;
    }
}
