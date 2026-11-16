/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

/**
 * Receives every charge to a tenant's spend, right after the tenant counter
 * accepted it — the attribution SPI for features that keep their own spend view
 * (per-team budgets, cost analytics).
 * <p>
 * Implement it as an {@code @ApplicationScoped} bean; {@link SpendSinks}
 * collects every implementation. Contract:
 * <ul>
 * <li><b>Synchronous</b>, on the thread that charged, after the tenant charge
 * succeeded — so a sink sees exactly the charges the tenant counter saw, with
 * the same outage handling, and an event cannot be lost the way an async CDI
 * event can.</li>
 * <li><b>Exactly once per charge, deltas only</b>: a turn's spend is delivered
 * once even across a pause and resume (it inherits {@code TurnCostMeter}'s
 * {@code audit:metered_cost} bookkeeping).</li>
 * <li><b>Isolated</b>: an exception is logged and swallowed; it never fails
 * metering, the turn, or another sink. Keep the work small — it runs on the
 * conversation's thread.</li>
 * </ul>
 */
public interface ISpendSink {

    /** One charge. Must not block for long; exceptions are swallowed. */
    void onSpend(SpendEvent event);
}
