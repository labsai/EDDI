/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.OptionalDouble;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The pre-model-call spend gate of an LLM task: asked once per task execution,
 * before any model request is built, whether the task may spend at all.
 * <p>
 * First the agent's own {@code maxCostPerConversationUsd}: the conversation's
 * priced spend so far (every step's {@code audit:cost}) against the ceiling —
 * arithmetic over memory, no I/O. The tool loop repeats that check between its
 * rounds ({@code ToolLoopRunner}), since this gate sees a loop's cost only once
 * the loop has finished.
 * <p>
 * Then it asks {@link TenantQuotaService#checkCostBudget} — the same read-only
 * gate {@code ToolLoopRunner} asks before every tool call. That gate alone
 * never stopped a model call: an agent without tools kept calling its model
 * after the tenant's monthly budget was spent, and an agent with tools kept
 * calling it to be told, tool after tool, that the budget was gone. With quotas
 * disabled (the default) the check is a cache hit and does no I/O; with quotas
 * enabled it is one monthly-cost read per LLM task, the same cost as the
 * tool-call gate.
 * <p>
 * A refusal fails <em>soft</em>, like the tool-call gate: nothing is thrown,
 * the model is not called, and {@code LlmTask} serves the task's configured
 * fallback answer instead — a deterministic reply. The step records why under
 * {@code llm:spend_refused:<taskId>}; {@code eddi.llm.spend.refused} counts it.
 * A store that cannot answer refuses too (fail closed), exactly as the
 * tool-call gate does.
 * <p>
 * Stateless; the per-conversation inputs come from the memory passed in.
 */
@ApplicationScoped
public class LlmSpendGate {

    private static final Logger LOGGER = Logger.getLogger(LlmSpendGate.class);

    static final String METRIC = "eddi.llm.spend.refused";

    /** {@code reason} of a refusal because the tenant's monthly budget is spent. */
    static final String REASON_TENANT_BUDGET = "tenant_budget";
    /**
     * {@code reason} of a refusal because the conversation's cost ceiling is
     * reached.
     */
    static final String REASON_CONVERSATION_CEILING = "conversation_ceiling";
    /** {@code reason} of a refusal because the quota store could not answer. */
    static final String REASON_TENANT_ACCOUNTING_UNAVAILABLE = "tenant_accounting_unavailable";

    /**
     * Why the model was not called.
     *
     * @param reason
     *            one of the {@code REASON_*} constants — the metric tag and the
     *            step record's {@code reason}
     * @param detail
     *            a human-readable explanation for the step record and the log
     */
    public record Refusal(String reason, String detail) {
    }

    /** Thrown inside {@code LlmTask}'s guarded model phase to skip it. */
    static final class SpendRefusedException extends RuntimeException {
        private final transient Refusal refusal;

        SpendRefusedException(Refusal refusal) {
            super(refusal.detail(), null, false, false);
            this.refusal = refusal;
        }

        Refusal refusal() {
            return refusal;
        }
    }

    private final TenantQuotaService tenantQuotaService;
    private final MeterRegistry meterRegistry;

    @Inject
    public LlmSpendGate(TenantQuotaService tenantQuotaService, MeterRegistry meterRegistry) {
        this.tenantQuotaService = tenantQuotaService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @return {@code null} when the task may call its model, otherwise why not
     *         (already counted and logged)
     */
    public Refusal check(IConversationMemory memory) {
        // The agent's own ceiling first: pure arithmetic over memory, no I/O.
        OptionalDouble ceiling = memory != null ? memory.getConversationCostCeilingUsd() : OptionalDouble.empty();
        if (ceilingApplies(ceiling)) {
            double spent = conversationCost(memory);
            if (spent >= ceiling.getAsDouble()) {
                return refused(memory, new Refusal(REASON_CONVERSATION_CEILING,
                        String.format("Conversation cost ceiling ($%.4f) reached: $%.4f spent", ceiling.getAsDouble(), spent)));
            }
        }
        if (tenantQuotaService == null) {
            return null;
        }
        var costCheck = tenantQuotaService.checkCostBudget(tenantQuotaService.getDefaultTenantId());
        if (costCheck.allowed()) {
            return null;
        }
        Refusal refusal = costCheck.accountingUnavailable()
                ? new Refusal(REASON_TENANT_ACCOUNTING_UNAVAILABLE, costCheck.reason())
                : new Refusal(REASON_TENANT_BUDGET, costCheck.reason());
        return refused(memory, refusal);
    }

    /**
     * True when {@code maxCostPerConversationUsd} bounds anything: set, finite and
     * not negative ({@code 0} allows no priced spend; negative means unlimited).
     */
    static boolean ceilingApplies(OptionalDouble ceiling) {
        return ceiling != null && ceiling.isPresent() && Double.isFinite(ceiling.getAsDouble()) && ceiling.getAsDouble() >= 0;
    }

    /**
     * The conversation's priced spend so far: the sum of every step's
     * {@code audit:cost}, the current step included (it holds what earlier tasks of
     * this turn already spent). Memory holds every step, so this is a walk over
     * in-memory data, no I/O.
     */
    static double conversationCost(IConversationMemory memory) {
        double total = 0.0;
        var steps = memory.getAllSteps();
        if (steps == null) {
            return total;
        }
        for (IData<Object> cost : steps.<Object>getExactDataPerStep(MemoryKeys.AUDIT_COST)) {
            if (cost != null && cost.getResult() instanceof Number n && Double.isFinite(n.doubleValue())) {
                total += n.doubleValue();
            }
        }
        return total;
    }

    /** Count and log a refusal the tool loop decided on its own (mid-loop). */
    static void countMidLoop(String conversationId, String detail) {
        Metrics.globalRegistry.counter(METRIC, "reason", REASON_CONVERSATION_CEILING).increment();
        LOGGER.warnf("Tool loop stopped in conversation %s (%s): %s", sanitize(conversationId), REASON_CONVERSATION_CEILING, detail);
    }

    private Refusal refused(IConversationMemory memory, Refusal refusal) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC, "reason", refusal.reason()).increment();
        }
        LOGGER.warnf("LLM call skipped in conversation %s (%s): %s", sanitize(memory != null ? memory.getConversationId() : null),
                refusal.reason(), refusal.detail());
        return refusal;
    }
}
