/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy.model;

/**
 * Result of a quota check — allowed or denied with reason.
 *
 * @param allowed
 *            whether the operation is permitted
 * @param reason
 *            human-readable reason for denial (null if allowed)
 * @param accountingUnavailable
 *            true when the denial is the store failing to answer rather than
 *            the tenant being over a limit. Both fail closed, but they are
 *            different events: an over-quota denial is 429 with
 *            {@code Retry-After: 60} and belongs on
 *            {@code eddi.tenant.quota.denied}, while an accounting outage is
 *            503 and must not make a tenant's quota graph spike as if they had
 *            burned through their allowance. Only the wording of the reason
 *            used to differ, so neither monitoring nor a client could tell the
 *            two apart.
 */
public record QuotaCheckResult(boolean allowed, String reason, boolean accountingUnavailable) {

    public static final QuotaCheckResult OK = new QuotaCheckResult(true, null, false);

    /** Compatibility constructor for the original two-component shape. */
    public QuotaCheckResult(boolean allowed, String reason) {
        this(allowed, reason, false);
    }

    /** The tenant is over a limit. */
    public static QuotaCheckResult denied(String reason) {
        return new QuotaCheckResult(false, reason, false);
    }

    /**
     * The store could not answer, so the request is refused for safety — not
     * because any limit was reached.
     */
    public static QuotaCheckResult unavailable(String reason) {
        return new QuotaCheckResult(false, reason, true);
    }
}
