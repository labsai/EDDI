/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * Marks an exception that means "the agent's configuration cannot run", as
 * opposed to "something on the server broke".
 * <p>
 * A lifecycle task wraps whatever it threw in a {@link LifecycleException}, and
 * the conversation-start path wraps that again, so by the time a refusal
 * reaches the REST layer every failure looks alike: a 500. Implementing this
 * interface on the exception a task throws for a configuration it refuses — a
 * vault reference that names no stored secret, a secret sent to a host its
 * {@code allowedHosts} does not list — lets {@code POST
 * /agents/{agentId}/start} answer {@code 422} with the reason instead, so a
 * client can tell "fix the agent" from "the server failed".
 * <p>
 * Implement it only where the refusal is certain to be a configuration problem.
 * A failure that MAY be transient or a server fault (the vault provider could
 * not be read, a host name did not resolve) must stay unmarked, and so a 500:
 * calling an outage a misconfiguration sends an administrator to fix a
 * configuration that is fine.
 * <p>
 * The exception's message is shown to the caller. It must name what refused —
 * the call, the reference, the host — and never carry a secret value.
 */
public interface AgentConfigurationRefusal {

    /** How deep {@link #find} looks along a cause chain. */
    int MAX_CAUSE_DEPTH = 16;

    /**
     * The first refusal along {@code failure}'s cause chain, {@code failure}
     * included, or {@code null} when there is none. Bounded and cycle-safe.
     */
    static Throwable find(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AgentConfigurationRefusal) {
                return current;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return null;
    }
}
