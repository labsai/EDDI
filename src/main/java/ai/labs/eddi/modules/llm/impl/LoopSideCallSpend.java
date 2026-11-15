/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.llm.guardrails.PaidCallGate;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;

import java.util.OptionalDouble;
import java.util.function.DoubleSupplier;

/**
 * Admission and charging of a paid call the tool loop makes on the side — a
 * {@code summarize} truncation's model call, a {@code toolCallGuardrails} judge
 * or classifier.
 * <ul>
 * <li><b>Admit</b> while the conversation's cost ceiling and the tenant budget
 * allow another paid call, with the loop run's unsettled spend counted.</li>
 * <li><b>Charge</b> it to the conversation as a priced tool call under its own
 * dispatch name, so it lands in the run's tool cost — and from there in the
 * mid-loop ceiling, a pause's spend, a failure's {@code UnrecordedSpend}, the
 * returned {@code toolCostUsd} and {@code audit:cost} — like any other tool
 * call.</li>
 * </ul>
 * One copy for every side call, so they cannot be admitted or charged
 * differently.
 */
final class LoopSideCallSpend {

    /** Refusal reason: the conversation's cost ceiling is reached. */
    static final String CONVERSATION_CEILING = "conversation_ceiling";

    /** Refusal reason: the tenant budget refuses (or could not be checked). */
    static final String TENANT_BUDGET = "tenant_budget";

    private LoopSideCallSpend() {
    }

    /**
     * Why a side call may not start, or null when it may.
     *
     * @param unsettled
     *            what the loop run has spent and not yet recorded, plus what the
     *            call is expected to cost
     */
    static String refusal(IConversationMemory memory, TenantQuotaService tenantQuotaService, double unsettled) {
        if (memory != null) {
            OptionalDouble ceiling = LlmSpendGate.effectiveCeiling(memory);
            if (LlmSpendGate.ceilingApplies(ceiling) && LlmSpendGate.conversationCost(memory) + unsettled >= ceiling.getAsDouble()) {
                return CONVERSATION_CEILING;
            }
        }
        if (tenantQuotaService == null) {
            return null;
        }
        try {
            return tenantQuotaService
                    .checkCostBudget(tenantQuotaService.getDefaultTenantId(), Math.max(0.0, LlmSpendGate.unmeteredStepCost(memory) + unsettled))
                    .allowed() ? null : TENANT_BUDGET;
        } catch (RuntimeException e) {
            // Fail closed, as every other tenant check.
            return TENANT_BUDGET;
        }
    }

    /** Charges one side call to the conversation as a priced tool call. */
    static void charge(ToolExecutionService toolExecutionService, String conversationId, boolean enableCostTracking, String dispatchName,
                       double priceUsd) {
        if (!enableCostTracking || conversationId == null || toolExecutionService == null || toolExecutionService.getCostTracker() == null) {
            return;
        }
        toolExecutionService.getCostTracker().trackToolCall(new ToolInvocation(dispatchName, dispatchName, priceUsd), conversationId);
    }

    /**
     * A {@link PaidCallGate} for a guardrail's model call inside the loop: admitted
     * as {@link #refusal}, with the run's unsettled spend and the expected cost;
     * charged as {@link #charge} when the guardrail reports what it spent.
     */
    static PaidCallGate gate(IConversationMemory memory, TenantQuotaService tenantQuotaService, ToolExecutionService toolExecutionService,
                             String conversationId, boolean enableCostTracking, DoubleSupplier runSpend, String dispatchName) {
        return expectedUsd -> {
            double unsettled = (runSpend != null ? runSpend.getAsDouble() : 0.0) + (Double.isFinite(expectedUsd) ? Math.max(0.0, expectedUsd) : 0.0);
            String refused = refusal(memory, tenantQuotaService, unsettled);
            return new PaidCallGate.PaidCall() {
                @Override
                public String refusalReason() {
                    return refused;
                }

                @Override
                public void spent(double actualUsd) {
                    if (refused == null && Double.isFinite(actualUsd) && actualUsd > 0.0) {
                        charge(toolExecutionService, conversationId, enableCostTracking, dispatchName, actualUsd);
                    }
                }

                @Override
                public void close() {
                    // nothing held: the charge is the record
                }
            };
        };
    }
}
