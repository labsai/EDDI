/*
 * Copyright (c) 2016-2026 EDDI contributors
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
 */
public record QuotaCheckResult(boolean allowed, String reason) {

    public static final QuotaCheckResult OK = new QuotaCheckResult(true, null);

    public static QuotaCheckResult denied(String reason) {
        return new QuotaCheckResult(false, reason);
    }
}
