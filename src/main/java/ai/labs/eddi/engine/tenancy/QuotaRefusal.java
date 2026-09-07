/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

/**
 * Implemented by every exception the tenant-quota layer raises to refuse a
 * request, whatever the reason.
 * <p>
 * Callers that must react to "the quota layer said no" — as opposed to "this
 * one agent failed" — match on this type rather than on a concrete exception
 * class. The group-conversation engines are the reason it exists: they treat a
 * quota refusal as non-retryable and affecting every member, so they abort the
 * whole discussion instead of recording a SKIPPED turn. When
 * {@link QuotaAccountingUnavailableException} was added beside
 * {@link QuotaExceededException} — which had to extend
 * {@link java.util.concurrent.RejectedExecutionException}, so it could not
 * simply subclass it — the six {@code instanceof QuotaExceededException} guards
 * in {@code MemberTurnExecutor}, {@code TaskForceEngine} and
 * {@code PhaseExecutionEngine} silently stopped matching, and a quota-store
 * outage degraded into per-member retries against a dead store or a transcript
 * full of SKIPPED entries with the discussion reported complete.
 * <p>
 * Matching on the marker means the next refusal type is handled by those guards
 * the day it is introduced rather than the day someone notices.
 *
 * @since 6.0.0
 */
public interface QuotaRefusal {

    /**
     * Short operator-facing label naming <em>which</em> refusal this is, used to
     * prefix the message of the wrapping exception.
     * <p>
     * "Tenant quota exceeded: Quota accounting unavailable — denying request for
     * safety" is the sentence that gets written without it, and it is false in its
     * first half.
     *
     * @return a label such as {@code "Tenant quota exceeded"}
     */
    String refusalSummary();
}
