/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.Optional;

/**
 * Server code that requires a human decision for one tool call because of
 * <em>what the call carries</em> — its arguments — not because of its name.
 * <p>
 * Supplied per turn by the tool source that built the tool
 * ({@code ToolSourceProvider#mandatoryApprovals}). A call any rule matches is
 * gated <em>before</em> the agent's approval patterns are consulted, so
 * {@code exempt} does not apply to it, an empty {@code requireApproval} does
 * not disable it, and no timeout policy or auto-approval can approve it.
 * <p>
 * Rules see raw arguments; they must be pure and cheap (no I/O). A rule that
 * throws gates the call with the strictest approver
 * ({@link ApproverRule#CONVERSATION_USER}) — a rule that cannot answer never
 * lets a call through.
 */
@FunctionalInterface
public interface MandatoryApprovalRule {

    /**
     * @return the approval this call needs, or empty when this rule has nothing to
     *         say about it
     */
    Optional<MandatoryApproval> evaluate(ToolExecutionRequest request);
}
