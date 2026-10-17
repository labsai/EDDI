/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard.Protection;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Marks the execution of a human-approved tool call, so the request it finally
 * sends is held to {@link SelfUngatingGuard} at the moment of dispatch.
 * <p>
 * {@code ToolLoopResumer} checks the request it can resolve ahead of execution,
 * but an http call's {@code preRequest.propertyInstructions} run only inside
 * {@code ApiCallExecutor.execute} and can change the URI or body the template
 * builds. The resolved preview is therefore not proof of what is sent. The
 * resumer binds the acting agent here around the approved execution;
 * {@code ApiCallExecutor} checks every request it builds while a binding is
 * present, immediately before sending it. Outside an approved execution nothing
 * is bound and nothing is checked — a designer's own pipeline http calls are
 * not this rule's business.
 * <p>
 * A {@link ThreadLocal}: {@link #propagate} carries it onto the thread a tool
 * call is run on (ToolExecutionService runs tools on a timeout executor).
 */
public final class ApprovedCallScope {

    private static final ThreadLocal<Protection> ACTING_AGENT = new ThreadLocal<>();

    private ApprovedCallScope() {
    }

    /**
     * Runs {@code work} as the approved execution of a call by
     * {@code actingAgentId}.
     */
    public static <T> T run(String actingAgentId, Supplier<T> work) {
        return run(Protection.ofAgent(actingAgentId), work);
    }

    /**
     * Runs {@code work} as the approved execution of a call under
     * {@code protection} — the acting agent and the resources it runs on, as
     * {@code ToolLoopResumer} resolved them (L13).
     */
    public static <T> T run(Protection protection, Supplier<T> work) {
        Protection previous = ACTING_AGENT.get();
        ACTING_AGENT.set(protection == null ? Protection.ofAgent(null) : protection);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * {@code work}, carrying the current binding (if any) onto whichever thread
     * runs it.
     */
    public static <T> Callable<T> propagate(Callable<T> work) {
        Protection captured = ACTING_AGENT.get();
        if (captured == null) {
            return work;
        }
        return () -> {
            Protection previous = ACTING_AGENT.get();
            ACTING_AGENT.set(captured);
            try {
                return work.call();
            } finally {
                restore(previous);
            }
        };
    }

    /** Whether an approved call is executing on this thread. */
    public static boolean isActive() {
        return ACTING_AGENT.get() != null;
    }

    /**
     * Refuses a request about to be sent during an approved execution when
     * {@link SelfUngatingGuard} would refuse it. Does nothing outside one.
     *
     * @throws IllegalArgumentException
     *             naming the reason; nothing has been sent
     */
    public static void checkOutgoing(String method, String uri, String body) {
        Protection protection = ACTING_AGENT.get();
        if (protection == null) {
            return;
        }
        String refusal = SelfUngatingGuard.refusal(method, uri, body, protection);
        if (refusal != null) {
            throw new IllegalArgumentException("NOT_EXECUTED: " + refusal + " (refused before sending)");
        }
    }

    private static void restore(Protection previous) {
        if (previous == null) {
            ACTING_AGENT.remove();
        } else {
            ACTING_AGENT.set(previous);
        }
    }
}
