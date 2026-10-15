/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Charges the priced spend of a settled conversation turn to the tenant's
 * monthly cost counter — the post-call half of the monthly cost budget, whose
 * pre-call half is {@link TenantQuotaService#checkCostBudget}.
 * <p>
 * <b>What is charged.</b> The step's {@link MemoryKeys#AUDIT_COST}: every LLM
 * call of the turn priced at its configured {@code inputPricePer1M} /
 * {@code outputPricePer1M} (or its cascade step / judge prices) plus tracked
 * tool cost. It is already a per-step running total over every LLM task of the
 * turn, so this meters per <em>step</em>, never per task.
 * <p>
 * <b>Exactly once.</b> A step can settle more than once: a HITL pause settles
 * it part-way and the resume continues accumulating into the same step. The
 * amount already charged is kept on the step under
 * {@link MemoryKeys#AUDIT_METERED_COST}, and only the difference is recorded.
 * The marker is written to the live memory from the pipeline's completion
 * callback, which runs before the turn is persisted, so it is durable with the
 * cost it describes.
 * <p>
 * <b>Never fails the turn.</b> The spend has already happened; a store that
 * cannot answer, or a budget crossed by this very turn, is logged and counted
 * on {@code eddi.tenant.cost.metering}, never thrown. Blocking the
 * <em>next</em> call is the pre-call gates' job. A store outage leaves the
 * marker unchanged, so a later settle of the same step retries the charge.
 * <p>
 * Tenant resolution matches {@code checkCostBudget}'s callers exactly: the
 * single default tenant.
 */
@ApplicationScoped
public class TurnCostMeter {

    private static final Logger LOGGER = Logger.getLogger(TurnCostMeter.class);

    /** Counter of settle-time charges, tagged {@code outcome}. */
    static final String METRIC = "eddi.tenant.cost.metering";

    private final TenantQuotaService tenantQuotaService;
    private final MeterRegistry meterRegistry;

    @Inject
    public TurnCostMeter(TenantQuotaService tenantQuotaService, MeterRegistry meterRegistry) {
        this.tenantQuotaService = tenantQuotaService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Charge the not-yet-metered part of the current step's cost to the default
     * tenant. Unpriced turns (no {@code audit:cost}, or nothing new since the last
     * settle) touch neither the store nor the step.
     *
     * @return the amount charged (0 when nothing was)
     */
    public double meterSettledStep(IConversationMemory memory) {
        try {
            var step = memory != null ? memory.getCurrentStep() : null;
            if (step == null) {
                return 0.0;
            }
            // getData is an exact-key lookup; getLatestData would prefix-match.
            double cost = valueOf(step.getData(MemoryKeys.AUDIT_COST));
            double alreadyMetered = valueOf(step.getData(MemoryKeys.AUDIT_METERED_COST));
            double delta = cost - alreadyMetered;
            // !(delta > 0), not delta <= 0: NaN fails every comparison.
            if (!Double.isFinite(delta) || !(delta > 0.0)) {
                return 0.0;
            }

            String tenantId = tenantQuotaService.getDefaultTenantId();
            QuotaCheckResult result = tenantQuotaService.recordCost(tenantId, delta);
            if (result.accountingUnavailable()) {
                // Nothing was added: leave the marker so a later settle retries.
                count("unavailable");
                LOGGER.warnf("Could not charge $%.6f of conversation %s to tenant '%s' — quota accounting unavailable",
                        delta, sanitize(memory.getConversationId()), sanitize(tenantId));
                return 0.0;
            }
            step.storeData(new Data<>(MemoryKeys.AUDIT_METERED_COST, cost));
            count(result.allowed() ? "recorded" : "over_budget");
            return delta;
        } catch (RuntimeException e) {
            count("error");
            LOGGER.errorf(e, "Metering the turn cost of conversation %s failed — the turn is unaffected",
                    sanitize(memory != null ? memory.getConversationId() : null));
            return 0.0;
        }
    }

    private void count(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC, "outcome", outcome).increment();
        }
    }

    private static double valueOf(IData<?> data) {
        return data != null && data.getResult() instanceof Number n ? n.doubleValue() : 0.0;
    }
}
