/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

/**
 * A hold on part of a tenant's monthly cost budget: money set aside for a call
 * that has not been charged yet. See
 * {@code docs/tenant-quotas.md#hard-ceiling-cost-reservation}.
 *
 * @param amountUsd
 *            the amount held
 * @param generation
 *            the counter epoch the hold was made in. A reservation that is
 *            never released (its node died) is dropped when its epoch grows
 *            older than the reservation TTL; a release that names a dropped
 *            epoch changes nothing, so it cannot cancel somebody else's hold.
 */
public record CostReservation(double amountUsd, long generation) {
}
