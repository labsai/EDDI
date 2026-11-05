/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;

/**
 * The answer to {@link ITenantQuotaStore#tryReserveCost}: the hold, or why
 * there is none.
 *
 * @param check
 *            allowed when the amount fit and was held; otherwise denied (the
 *            budget cannot take it) or flagged accounting-unavailable (the
 *            store could not answer — fail closed)
 * @param reservation
 *            what is held; null unless {@code check} allowed
 */
public record CostReservationResult(QuotaCheckResult check, CostReservation reservation) {

    public static CostReservationResult granted(CostReservation reservation) {
        return new CostReservationResult(QuotaCheckResult.OK, reservation);
    }

    public static CostReservationResult refused(QuotaCheckResult check) {
        return new CostReservationResult(check, null);
    }

    /** Nothing needed holding (reservations off, no budget, or a zero amount). */
    public static CostReservationResult notNeeded() {
        return new CostReservationResult(QuotaCheckResult.OK, null);
    }

    public boolean granted() {
        return reservation != null && check.allowed();
    }
}
