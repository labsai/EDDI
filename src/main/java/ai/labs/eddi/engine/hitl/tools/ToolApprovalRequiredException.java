/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unchecked signal thrown by the tool-calling loop when a gated tool call
 * requires human approval.
 * <p>
 * It is deliberately a {@link RuntimeException} so it can travel up through the
 * existing agent execution stack —
 * {@code AgentExecutionHelper.executeWithRetry} and
 * {@code CascadingModelExecutor} both {@code catch (Exception)} — without a
 * checked-signature change. Every such catch that encloses the tool loop
 * rethrows this type immediately; the lifecycle task-loop catch in
 * {@code LifecycleManager} converts it into a
 * {@link ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException}
 * with {@code PauseOrigin.TOOL_CALL}, which commits the durable pause.
 * <p>
 * The {@link #batch} carries the persisted, size-capped, secret-redacted
 * snapshot of the interrupted tool-call batch (transcript, gated calls, trace).
 * <p>
 * It also carries what the interrupted run has <em>spent</em> up to the pause —
 * its model tokens and their price at the task's rates, plus its tracked tool
 * cost ({@link #addSpend}). That spend has happened, but the task that would
 * normally record it never returns: {@code LlmTask} folds it into the step's
 * {@code audit:cost} and {@code audit:token_usage} as the signal passes, so the
 * paused step (which the pause snapshot persists) holds it. The resume then
 * counts it once, through {@code audit:cost}, in its spend preflight, its loop
 * ceiling and its final total. Deliberately not on the batch: the batch is
 * persisted, and a figure already recorded on the step must not be applied
 * again.
 */
public class ToolApprovalRequiredException extends RuntimeException {

    private final String pauseReason;
    private final transient PendingToolCallBatch batch;
    private final transient Map<String, Object> tokenUsageSoFar = new LinkedHashMap<>();
    private double costSoFarUsd;

    public ToolApprovalRequiredException(String pauseReason, PendingToolCallBatch batch) {
        super("Tool call requires human approval: "
                + (pauseReason == null || pauseReason.isBlank() ? "approval required" : pauseReason));
        this.pauseReason = pauseReason;
        this.batch = batch;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public PendingToolCallBatch getBatch() {
        return batch;
    }

    /**
     * Add spend the interrupted run made before the pause: the tool loop's own
     * segment where the exception is raised, and an enclosing cascade's earlier
     * steps as it passes through. Token counts are summed field by field.
     *
     * @param tokenUsage
     *            token counts (the {@code tokenUsage} map shape), or {@code null}
     * @param costUsd
     *            priced model and tool spend; not finite or not positive adds
     *            nothing
     */
    public void addSpend(Map<String, ?> tokenUsage, double costUsd) {
        if (tokenUsage != null) {
            for (Map.Entry<String, ?> entry : tokenUsage.entrySet()) {
                if (entry.getValue() instanceof Number n) {
                    long previous = tokenUsageSoFar.get(entry.getKey()) instanceof Number p ? p.longValue() : 0L;
                    tokenUsageSoFar.put(entry.getKey(), previous + n.longValue());
                }
            }
        }
        // !(costUsd > 0): NaN fails every comparison and would poison the total.
        if (Double.isFinite(costUsd) && costUsd > 0.0) {
            costSoFarUsd += costUsd;
        }
    }

    /** Token counts spent before the pause; empty when none were reported. */
    public Map<String, Object> getTokenUsageSoFar() {
        return Collections.unmodifiableMap(tokenUsageSoFar);
    }

    /** Priced model and tool spend before the pause, in USD. */
    public double getCostSoFarUsd() {
        return costSoFarUsd;
    }
}
