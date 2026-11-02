/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard.Protection;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
 * The binding also carries the conversation the call runs in: "an agent may not
 * send a request to its own conversation" is checked on the preview too, and a
 * {@code preRequest} instruction can point the final URI at
 * {@code /agents/<own conversation>} just as it can at the agent's own
 * configuration.
 * <p>
 * A {@link ThreadLocal}: {@link #propagate} carries it onto the thread a tool
 * call is run on (ToolExecutionService runs tools on a timeout executor).
 */
public final class ApprovedCallScope {

    /**
     * What an approved execution is held to: the agent's protection and its
     * conversation.
     */
    private record Binding(Protection protection, String conversationId) {
    }

    private static final ThreadLocal<Binding> ACTING_AGENT = new ThreadLocal<>();

    /** The reason a request aimed at the running conversation is refused with. */
    public static final String OWN_CONVERSATION_REASON = "the request targets the conversation the agent is running in";

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
        return run(protection, null, work);
    }

    /**
     * As {@link #run(Protection, Supplier)}, also refusing a request aimed at
     * {@code conversationId} — the conversation the approved call runs in.
     */
    public static <T> T run(Protection protection, String conversationId, Supplier<T> work) {
        Binding previous = ACTING_AGENT.get();
        ACTING_AGENT.set(new Binding(protection == null ? Protection.ofAgent(null) : protection, conversationId));
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
        Binding captured = ACTING_AGENT.get();
        if (captured == null) {
            return work;
        }
        return () -> {
            Binding previous = ACTING_AGENT.get();
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
        Binding binding = ACTING_AGENT.get();
        if (binding == null) {
            return;
        }
        String refusal = SelfUngatingGuard.refusal(method, uri, body, binding.protection());
        if (refusal == null && targetsConversation(uri, binding.conversationId())) {
            refusal = OWN_CONVERSATION_REASON;
        }
        if (refusal != null) {
            throw new IllegalArgumentException("NOT_EXECUTED: " + refusal + " (refused before sending)");
        }
    }

    /**
     * Whether {@code candidate} (a URI, or raw tool arguments) names
     * {@code conversationId}: case-insensitive containment, tolerating
     * percent-encoding. A false positive costs one refused call, a false negative
     * the boundary.
     */
    public static boolean targetsConversation(String candidate, String conversationId) {
        if (candidate == null || conversationId == null || conversationId.isBlank()) {
            return false;
        }
        String decoded = candidate;
        try {
            decoded = URLDecoder.decode(candidate, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A malformed escape is not a reason to stop checking — fall back to the
            // raw string rather than returning false and allowing the write.
        }
        return decoded.toLowerCase(Locale.ROOT).contains(conversationId.trim().toLowerCase(Locale.ROOT));
    }

    private static void restore(Binding previous) {
        if (previous == null) {
            ACTING_AGENT.remove();
        } else {
            ACTING_AGENT.set(previous);
        }
    }
}
