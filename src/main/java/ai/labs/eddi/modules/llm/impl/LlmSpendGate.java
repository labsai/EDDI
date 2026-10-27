/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The pre-model-call spend gate of an LLM task: asked once per task execution,
 * before any model request is built, whether the task may spend at all.
 * <p>
 * First the agent's own {@code maxCostPerConversationUsd}: the conversation's
 * priced spend so far (every step's {@code audit:cost}, undone steps included —
 * see {@link #conversationCost}) against the ceiling — arithmetic over memory,
 * no I/O. The tool loop repeats that check between its rounds
 * ({@code ToolLoopRunner}), since this gate sees a loop's cost only once the
 * loop has finished.
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
 * tool-call gate does — on every backend, and as this fallback answer, never as
 * an exception out of the turn.
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
     * {@code reason} of a refusal because the tenant budget check itself threw — a
     * defect, not a store outage. A store outage is answered by
     * {@code TenantQuotaService} as an accounting-unavailable result (and counted
     * on {@code eddi.tenant.quota.unavailable}); this tag keeps a bug from passing
     * for one. Shared by this gate and the tool-call gate in
     * {@code ToolLoopRunner}.
     */
    static final String REASON_TENANT_CHECK_ERROR = "tenant_check_error";

    /**
     * What the model is told when a tool call is refused for
     * {@link #REASON_TENANT_CHECK_ERROR}. Deliberately generic: the exception class
     * belongs in the log and the trace, not in a prompt.
     */
    static final String TENANT_CHECK_ERROR_MESSAGE = "Tenant cost budget could not be checked — denying request for safety";

    /** At most one stack trace per this interval for {@link #tenantCheckFailed}. */
    static final long CHECK_ERROR_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    /**
     * When {@link #tenantCheckFailed} last logged with a stack; a defect fires on
     * every turn, and one trace a minute is enough to diagnose it.
     */
    private static final AtomicLong lastCheckErrorLog = new AtomicLong(System.nanoTime() - CHECK_ERROR_LOG_INTERVAL_NANOS);

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
        OptionalDouble ceiling = memory != null ? effectiveCeiling(memory) : OptionalDouble.empty();
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
        QuotaCheckResult costCheck;
        try {
            costCheck = tenantQuotaService.checkCostBudget(tenantQuotaService.getDefaultTenantId());
        } catch (RuntimeException e) {
            // The gate runs outside LlmTask's guarded model phase, so a throw here fails
            // the whole turn. The service already turns a store outage into a result, so
            // whatever still escapes is a defect, not an outage: refused all the same
            // (fail closed, the deterministic fallback answer, never a 500), but under
            // its own reason so it cannot pass for one.
            return new Refusal(REASON_TENANT_CHECK_ERROR,
                    tenantCheckFailed(meterRegistry, memory != null ? memory.getConversationId() : null, "model call", e));
        }
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
     * The conversation cost ceiling this turn is held to: the agent's
     * {@code maxCostPerConversationUsd}, tightened by a scheduled fire's
     * {@code maxCostPerFire} when the turn carries one (context
     * {@value ScheduleConfiguration#CONTEXT_MAX_COST_PER_FIRE} on the current
     * step). The fire's ceiling is what was spent before this step — the
     * {@link #conversationCost} total, undone and discarded steps included, less
     * the current step's own {@code audit:cost} — plus the allowance, so the
     * allowance is per fire even on a persistent conversation; the tighter of the
     * two wins. The tool loop asks the same question between rounds.
     */
    static OptionalDouble effectiveCeiling(IConversationMemory memory) {
        OptionalDouble agentCeiling = memory.getConversationCostCeilingUsd();
        double allowance = maxCostPerFire(memory);
        if (!(allowance > 0)) {
            return agentCeiling;
        }
        double fireCeiling = conversationCost(memory) - currentStepCost(memory) + allowance;
        if (ceilingApplies(agentCeiling) && agentCeiling.getAsDouble() <= fireCeiling) {
            return agentCeiling;
        }
        return OptionalDouble.of(fireCeiling);
    }

    /**
     * The current step's {@code maxCostPerFire} allowance, or {@code 0} when the
     * turn is not a capped scheduled fire (or the value is not a finite number).
     * Exact-key read on the current step only: the allowance belongs to the fire
     * that set it, not to later turns of the same conversation.
     */
    static double maxCostPerFire(IConversationMemory memory) {
        try {
            var step = memory.getCurrentStep();
            IData<Object> data = step == null ? null : step.getData("context:" + ScheduleConfiguration.CONTEXT_MAX_COST_PER_FIRE);
            if (data == null || !(data.getResult() instanceof Context context) || context.getValue() == null) {
                return 0.0;
            }
            Object value = context.getValue();
            double allowance = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
            return Double.isFinite(allowance) ? allowance : 0.0;
        } catch (RuntimeException e) {
            LOGGER.debugf("maxCostPerFire context could not be read: %s", e.getMessage());
            return 0.0;
        }
    }

    /** What the current step has spent so far — its {@code audit:cost}. */
    private static double currentStepCost(IConversationMemory memory) {
        var step = memory.getCurrentStep();
        IData<Object> cost = step == null ? null : step.getData(MemoryKeys.AUDIT_COST);
        return cost != null && cost.getResult() instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : 0.0;
    }

    /**
     * The conversation's priced spend so far — monotonic: an undo cannot lower it.
     * The sum of every step's {@code audit:cost}, the current step included (it
     * holds what earlier tasks of this turn already spent), plus
     * <ul>
     * <li>the steps an undo moved to the redo cache: still spent, and a rerun of
     * the step before them runs while they sit there;</li>
     * <li>{@code audit:discarded_cost}: what a new turn inherited from a redo cache
     * it cleared ({@code ConversationMemory.startNextStep()}).</li>
     * </ul>
     * A redo moves a step back without changing the total. Memory holds every step,
     * so this is a walk over in-memory data, no I/O.
     */
    private static final List<String> SPEND_KEYS = List.of(MemoryKeys.AUDIT_COST, MemoryKeys.AUDIT_DISCARDED_COST);

    static double conversationCost(IConversationMemory memory) {
        double total = 0.0;
        var steps = memory.getAllSteps();
        if (steps != null) {
            // One stack-level read per key, the same two keys recordedSpend sums.
            for (String key : SPEND_KEYS) {
                for (IData<Object> cost : steps.<Object>getExactDataPerStep(key)) {
                    if (cost != null && cost.getResult() instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() > 0.0) {
                        total += n.doubleValue();
                    }
                }
            }
        }
        var undone = memory.getRedoCache();
        if (undone != null) {
            for (IConversationStep step : undone) {
                total += ConversationMemory.recordedSpend(step);
            }
        }
        return total;
    }

    /**
     * Count and log a tenant budget check that threw instead of answering, for
     * either gate (this one, or the tool-call gate in {@code ToolLoopRunner}, which
     * passes {@code null} for the registry and so counts on the global one — see
     * {@link #countMidLoop}). Counted on {@value #METRIC} with
     * {@code reason=tenant_check_error}; logged at ERROR with the stack trace at
     * most once per {@link #CHECK_ERROR_LOG_INTERVAL_NANOS}, and without it
     * otherwise, since a defect here fires on every turn.
     *
     * @return the refusal detail, naming the exception class, for the log, the step
     *         record and the tool trace — never for the model, which gets
     *         {@link #TENANT_CHECK_ERROR_MESSAGE}
     */
    static String tenantCheckFailed(MeterRegistry registry, String conversationId, String refusedWhat, RuntimeException e) {
        (registry != null ? registry : Metrics.globalRegistry).counter(METRIC, "reason", REASON_TENANT_CHECK_ERROR).increment();
        String detail = "Tenant cost budget check failed (" + e.getClass().getSimpleName() + ") — " + refusedWhat + " refused for safety";
        long now = System.nanoTime();
        long last = lastCheckErrorLog.get();
        if (now - last >= CHECK_ERROR_LOG_INTERVAL_NANOS && lastCheckErrorLog.compareAndSet(last, now)) {
            LOGGER.errorf(e, "%s in conversation %s (%s)", detail, sanitize(conversationId), REASON_TENANT_CHECK_ERROR);
        } else {
            LOGGER.errorf("%s in conversation %s (%s): %s", detail, sanitize(conversationId), REASON_TENANT_CHECK_ERROR,
                    sanitize(String.valueOf(e.getMessage())));
        }
        return detail;
    }

    /**
     * Count and log a refusal the tool loop decided on its own (mid-loop).
     * <p>
     * Static, on the Micrometer global registry, because its caller
     * {@code ToolLoopRunner} is not a CDI bean and is built (by
     * {@code AgentOrchestrator} and a dozen tests) without a registry — the idiom
     * {@code LifecycleManager} and the pause-cap guard use. Quarkus adds its
     * registry to the global composite, so the series reaches {@code /q/metrics}
     * under the same name and tags as {@link #check}'s.
     */
    static void countMidLoop(String conversationId, String detail) {
        Metrics.globalRegistry.counter(METRIC, "reason", REASON_CONVERSATION_CEILING).increment();
        LOGGER.warnf("Tool loop stopped in conversation %s (%s): %s", sanitize(conversationId), REASON_CONVERSATION_CEILING, detail);
    }

    private Refusal refused(IConversationMemory memory, Refusal refusal) {
        (meterRegistry != null ? meterRegistry : Metrics.globalRegistry).counter(METRIC, "reason", refusal.reason()).increment();
        LOGGER.warnf("LLM call skipped in conversation %s (%s): %s", sanitize(memory != null ? memory.getConversationId() : null),
                refusal.reason(), refusal.detail());
        return refusal;
    }
}
