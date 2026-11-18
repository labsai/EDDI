/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * Marks a failure of the {@code CONVERSATION_START} turn that is certain to
 * come from the agent's own configuration or logic and not from the server — a
 * rule set whose dependencies form a cycle, say — but that is not an
 * {@link AgentConfigurationRefusal} (nothing was refused on purpose, and there
 * is no message fit to show a caller).
 * <p>
 * {@code POST /agents/{agentId}/start} answers such a failure with a typed
 * {@code 422 agent_start_failed} and a fixed message instead of a bare 500, and
 * creates no conversation. Like the refusal marker it is implemented only where
 * the failure cannot be a transient server fault: a vault outage or a database
 * error also surfaces as a task failure and must stay a 500.
 */
public interface AgentStartFailure {

    /**
     * The first marked failure along {@code failure}'s cause chain, or
     * {@code null}.
     */
    static Throwable find(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < AgentConfigurationRefusal.MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AgentStartFailure) {
                return current;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return null;
    }
}
