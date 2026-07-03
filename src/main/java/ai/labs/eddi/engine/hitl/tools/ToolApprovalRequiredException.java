/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;

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
 */
public class ToolApprovalRequiredException extends RuntimeException {

    private final String pauseReason;
    private final transient PendingToolCallBatch batch;

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
}
