/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;

import java.time.Duration;
import java.time.Instant;

/**
 * The one retry policy of the email ledger's outbound rows — HITL notifications
 * and conversation-mode replies — whose backoff the mailbox poller also uses
 * for failed polls.
 * <p>
 * <b>Backoff:</b> {@code base × 2^(attempts-1)}, capped at
 * {@link #MAX_BACKOFF}. <b>After a failed attempt</b> a row fails for good when
 * the failure is permanent or it has used
 * {@code eddi.email.outbound.max-attempts}; otherwise it is released for
 * another attempt after its backoff, and counted as a retry only when this
 * claim still held it.
 */
public final class LedgerRetry {

    /** The longest a retry waits. */
    public static final Duration MAX_BACKOFF = Duration.ofHours(1);

    /** The base when {@code eddi.email.outbound.retry-backoff} is unusable. */
    static final Duration DEFAULT_BASE = Duration.ofMinutes(1);

    /** 2^20 × any base is past the cap; more doublings would overflow. */
    private static final int MAX_DOUBLINGS = 20;

    private final IEmailMessageLedger ledger;
    private final int maxAttempts;
    private final Duration base;

    /**
     * @param maxAttempts
     *            attempts a row gets, at least one
     * @param base
     *            the first retry's delay; null, zero or negative means one minute
     */
    public LedgerRetry(IEmailMessageLedger ledger, int maxAttempts, Duration base) {
        this.ledger = ledger;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.base = base == null || base.isZero() || base.isNegative() ? DEFAULT_BASE : base;
    }

    /** {@code base × 2^(attempts-1)}, capped at {@link #MAX_BACKOFF}. */
    public static Duration backoff(Duration base, int attempts) {
        int doublings = Math.min(Math.max(0, attempts - 1), MAX_DOUBLINGS);
        Duration delay = base.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /** This policy's backoff after {@code attempts} attempts. */
    public Duration backoff(int attempts) {
        return backoff(base, attempts);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * After a failed attempt of the claimed {@code row}: {@code onFail} when the
     * failure is permanent or the row has no attempt left — it is the caller that
     * ends the row, since what a failure records differs — otherwise the row is
     * rescheduled after its backoff with {@code error}, and {@code onRetry} runs
     * when this claim still held it.
     */
    public void deferOrFail(LedgerEntry row, String error, boolean permanent, Instant now, Runnable onFail, Runnable onRetry) {
        if (permanent || row.attempts() >= maxAttempts) {
            onFail.run();
        } else if (ledger.reschedule(row.id(), row.claimToken(), now.plus(backoff(row.attempts())), error, now)) {
            onRetry.run();
        }
    }
}
