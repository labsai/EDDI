/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.integrations.email.EmailUserIdentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The origin taint of a conversation: {@link #EMAIL} when its root turn came
 * from a mail, or from a conversation a mail started (email plan v3 §5.3).
 * <p>
 * A turn a mail started must never widen who can make EDDI act, nor decide its
 * own approvals; {@link EscalationGuard} refuses those operations for a tainted
 * turn. The taint is:
 * <ul>
 * <li><b>set</b> where the turn is submitted — the email channel puts
 * {@link #CONTEXT_KEY} into the context of the conversation start and of every
 * turn, a group stores it on the discussion and hands it to its members, a
 * scheduled check-back carries it in the schedule's metadata, and an A2A call
 * sends it as {@link #HEADER};</li>
 * <li><b>persisted</b> on the conversation memory and on a group conversation,
 * so a HITL resume next week or a self-continuation still carries it;</li>
 * <li><b>bound</b> per turn on the {@link ResolutionPrincipal}, so it travels
 * wherever that principal does;</li>
 * <li><b>inherited</b> by every conversation a tainted turn starts or
 * continues, whatever its user id;</li>
 * <li><b>sticky</b>: nothing clears it.</li>
 * </ul>
 * Accepting the taint from any caller is safe — it only ever restricts the
 * conversation that carries it — so the context key and the header need no
 * authentication. {@link ClientContextGuard} still reserves the key, because
 * only EDDI itself has a reason to set it.
 */
public final class TurnOrigin {

    /** A mail started the conversation, or one that led to it. */
    public static final String EMAIL = "email";

    /** The context key carrying the taint into a conversation start or a turn. */
    public static final String CONTEXT_KEY = "turnOrigin";

    /** The request header an outbound A2A call carries the taint in. */
    public static final String HEADER = "X-Eddi-Turn-Origin";

    private static final ResolutionPrincipalContext PRINCIPALS = new ResolutionPrincipalContext();

    /**
     * A taint bound without a principal, by {@link #callWith}: for work that starts
     * conversations on a thread no turn runs on — the email poller starting a group
     * discussion, a group starting a nested one — where binding a whole principal
     * would also change whose credentials the started conversations may use.
     */
    private static final ThreadLocal<String> BOUND = new ThreadLocal<>();

    private TurnOrigin() {
    }

    /**
     * The taint of the work running on this thread, or {@code null}: the bound
     * principal's ({@link #of}) or one bound by {@link #callWith}. A turn whose
     * user id is an email identity counts as tainted even when its conversation
     * predates the stored taint.
     */
    public static String current() {
        return combine(of(PRINCIPALS.current()), BOUND.get());
    }

    /**
     * Runs {@code work} with {@code origin} bound on this thread (nothing bound
     * when it is {@code null}), restoring the previous binding afterwards.
     */
    public static <T> T callWith(String origin, Callable<T> work) throws Exception {
        if (origin == null) {
            return work.call();
        }
        String previous = BOUND.get();
        BOUND.set(combine(previous, origin));
        try {
            return work.call();
        } finally {
            if (previous == null) {
                BOUND.remove();
            } else {
                BOUND.set(previous);
            }
        }
    }

    /** Whether the turn running on this thread is tainted by a mail. */
    public static boolean currentIsEmail() {
        return EMAIL.equals(current());
    }

    /**
     * The taint {@code principal} carries: its origin, or {@link #EMAIL} for an
     * email user id (a conversation stored before the taint was recorded).
     */
    public static String of(ResolutionPrincipal principal) {
        if (principal == null) {
            return null;
        }
        if (principal.origin() != null) {
            return principal.origin();
        }
        return EmailUserIdentity.isEmailIdentity(principal.userId()) ? EMAIL : null;
    }

    /** The taint a context map carries in {@link #CONTEXT_KEY}, or {@code null}. */
    public static String fromContext(Map<String, Context> context) {
        if (context == null) {
            return null;
        }
        Context value = context.get(CONTEXT_KEY);
        return value != null && value.getValue() != null ? normalise(String.valueOf(value.getValue())) : null;
    }

    /**
     * The taint a schedule's metadata carries in {@link #CONTEXT_KEY} (a check-back
     * a tainted turn booked), or {@code null}.
     */
    public static String fromMetadata(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get(CONTEXT_KEY);
        return value == null ? null : normalise(String.valueOf(value));
    }

    /**
     * The taint a header or a stored value names, or {@code null} for anything that
     * is not a known origin.
     */
    public static String normalise(String value) {
        return value != null && EMAIL.equalsIgnoreCase(value.trim()) ? EMAIL : null;
    }

    /** The stronger of two taints: {@link #EMAIL} when either is. */
    public static String combine(String first, String second) {
        return EMAIL.equals(first) || EMAIL.equals(second) ? EMAIL : null;
    }

    /**
     * {@code context} with {@link #CONTEXT_KEY} set to {@code origin}, as a new
     * mutable map; the argument itself when {@code origin} is {@code null}.
     */
    public static Map<String, Context> withOrigin(Map<String, Context> context, String origin) {
        if (origin == null) {
            return context;
        }
        Map<String, Context> tainted = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
        tainted.put(CONTEXT_KEY, new Context(Context.ContextType.string, origin));
        return tainted;
    }
}
