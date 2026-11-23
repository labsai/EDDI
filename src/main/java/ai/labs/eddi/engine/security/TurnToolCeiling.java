/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.model.Context;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The tool ceiling of a conversation: the only tool names its turns may call,
 * set by an email sender role (email plan v3 §9) — {@code tools} of the
 * sender's role policy, and none at all for an unauthenticated sender.
 * <p>
 * It works like the origin taint ({@link TurnOrigin}), and for the same reason
 * it is safe to accept from anyone: it only ever <em>removes</em> tools.
 * <ul>
 * <li><b>set</b> by the email channel in {@link #CONTEXT_KEY} of the
 * conversation start and of every mail turn;</li>
 * <li><b>persisted</b> on the conversation memory, so a HITL resume or a
 * self-continuation keeps it;</li>
 * <li><b>bound</b> per turn on the {@link ResolutionPrincipal}, and inherited
 * by every conversation a turn starts (a delegation runs under the same
 * ceiling);</li>
 * <li><b>sticky and narrowing</b>: every new ceiling is intersected with the
 * stored one, nothing widens or clears it.</li>
 * </ul>
 * {@code null} means no ceiling; an empty set means no tool at all. Enforced in
 * {@code ToolExecutionService} (every LLM tool source, the HITL resume loop,
 * {@code mcpcalls}) and {@code ApiCallsTask} (rule-driven {@code httpcalls}).
 */
public final class TurnToolCeiling {

    /** The context key carrying the ceiling into a conversation start or turn. */
    public static final String CONTEXT_KEY = "emailToolCeiling";

    private static final ResolutionPrincipalContext PRINCIPALS = new ResolutionPrincipalContext();

    private TurnToolCeiling() {
    }

    /** The ceiling of the turn on this thread, or {@code null} for none. */
    public static Set<String> current() {
        ResolutionPrincipal principal = PRINCIPALS.current();
        return principal == null ? null : principal.toolCeiling();
    }

    /** Whether the turn on this thread may call {@code toolName}. */
    public static boolean permits(Set<String> ceiling, String toolName) {
        return ceiling == null || (toolName != null && ceiling.contains(toolName.trim()));
    }

    /**
     * The refusal for calling {@code toolName} in the turn on this thread, if any.
     */
    public static Optional<String> refuseTool(String toolName) {
        Set<String> ceiling = current();
        return permits(ceiling, toolName) ? Optional.empty() : Optional.of(refusal(toolName));
    }

    /** The text a refused call answers with. */
    public static String refusal(String toolName) {
        return "Refused: the sender role of the email behind this conversation does not allow the tool '" + toolName
                + "'. An administrator can change the role's tools in the integration's sender roles.";
    }

    /**
     * The ceiling a context map carries, or {@code null}. A value that is present
     * but not a list of names is read as no tools at all: fail closed.
     */
    public static Set<String> fromContext(Map<String, Context> context) {
        if (context == null) {
            return null;
        }
        Context value = context.get(CONTEXT_KEY);
        if (value == null) {
            return null;
        }
        return fromValue(value.getValue());
    }

    /** As {@link #fromContext}, for a stored or transported value. */
    public static Set<String> fromValue(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Collection<?> names)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object name : names) {
            if (name instanceof String s && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return Set.copyOf(out);
    }

    /**
     * The narrower of two ceilings: their intersection, {@code null} meaning none.
     */
    public static Set<String> intersect(Set<String> first, Set<String> second) {
        if (first == null) {
            return second == null ? null : Set.copyOf(second);
        }
        if (second == null) {
            return Set.copyOf(first);
        }
        Set<String> both = new LinkedHashSet<>(first);
        both.retainAll(second);
        return Set.copyOf(both);
    }

    /** The context value for {@code ceiling}, names in a stable order. */
    public static Context toContext(Set<String> ceiling) {
        return new Context(Context.ContextType.array, List.copyOf(new TreeSet<>(ceiling)));
    }
}
