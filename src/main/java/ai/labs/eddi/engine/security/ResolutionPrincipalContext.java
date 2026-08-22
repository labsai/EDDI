/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.Callable;

/**
 * Carries the {@link ResolutionPrincipal} of the conversation turn being
 * executed on this thread.
 * <p>
 * The same {@link ThreadLocal} shape as {@link CallerIdentityContext}, and for
 * a related reason — but they answer different questions. The caller context
 * answers "who made this HTTP request"; this one answers "whose conversation is
 * this turn, and did anybody verify that". Only the second is a safe basis for
 * releasing a per-user credential, because on a HITL resume the request belongs
 * to the approving administrator while the tool call being approved belongs to
 * the user who asked for it.
 * <p>
 * A {@code ThreadLocal} rather than a parameter because the consumers cannot
 * take one. A cached MCP client's {@code customHeaders} lambda runs per request
 * with no conversation in scope, and the client is shared across conversations
 * by design; the same is true of the A2A call path. Threading a principal
 * through langchain4j's transport is not available to us, so the principal has
 * to be on the thread that runs the tool call.
 * <p>
 * Always restore or clear in a {@code finally} — pool threads are reused across
 * conversations, and a leaked principal would let the next user's turn resolve
 * the previous user's credentials.
 */
@ApplicationScoped
public class ResolutionPrincipalContext {

    private static final ThreadLocal<ResolutionPrincipal> CURRENT = new ThreadLocal<>();

    /**
     * Bind a principal to this thread. {@code null} removes the binding rather than
     * doing nothing, so work that deliberately has no principal cannot observe one
     * left behind by whatever ran on this thread before.
     */
    public void bind(ResolutionPrincipal principal) {
        if (principal == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(principal);
        }
    }

    /** Clear the binding. */
    public void clear() {
        CURRENT.remove();
    }

    /** The principal bound to this thread, or {@code null}. */
    public ResolutionPrincipal current() {
        return CURRENT.get();
    }

    /**
     * Bind a principal around work that will run on another thread.
     * <p>
     * The previous binding is restored rather than cleared, so a nested turn — a
     * sub-agent conversation started from inside a pipeline — does not wipe the
     * parent's binding on the way out.
     */
    public <T> Callable<T> withPrincipal(ResolutionPrincipal principal, Callable<T> work) {
        return () -> {
            final ResolutionPrincipal previous = current();
            bind(principal);
            try {
                return work.call();
            } finally {
                bind(previous);
            }
        };
    }

    /**
     * {@link #withPrincipal(ResolutionPrincipal, Callable)} for a {@link Runnable}.
     */
    public Runnable withPrincipal(ResolutionPrincipal principal, Runnable work) {
        return () -> {
            final ResolutionPrincipal previous = current();
            bind(principal);
            try {
                work.run();
            } finally {
                bind(previous);
            }
        };
    }
}
