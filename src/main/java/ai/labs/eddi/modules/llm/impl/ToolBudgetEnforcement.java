/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

/**
 * The public face of {@code AgentOrchestrator}'s budget-enforcement default for
 * surfaces outside this package (the MCP {@code get_agent} and
 * {@code deploy_agent} tools), so an operator reading a stored
 * {@code maxBudgetPerConversation} also sees whether it refuses (M8).
 */
public final class ToolBudgetEnforcement {

    private ToolBudgetEnforcement() {
    }

    /**
     * Whether a ceiling is enforced when its task does not set
     * {@code enforceBudget}.
     */
    public static boolean isEnforcedByDefault() {
        return AgentOrchestrator.isBudgetEnforcedByDefault();
    }
}
