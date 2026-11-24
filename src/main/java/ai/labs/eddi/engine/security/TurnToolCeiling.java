/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.model.Context;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /**
     * Tools that hand work to somewhere the ceiling cannot follow: a group
     * discussion or a team (whose members run their own tools), the team backlog
     * (worked later by a cadence run), and EDDI's own MCP tools that start a
     * conversation or a schedule over the API (the ceiling does not cross an HTTP
     * hop, only the origin taint does). Under any ceiling they are refused, even
     * when the role names them. Matched like {@link EscalationGuard}, also behind a
     * namespace prefix. In-process delegation ({@code converseWithAgent}) and a
     * follow-up ({@code scheduleFollowup}) are not listed: they carry the ceiling.
     */
    public static final Set<String> SPAWNING_TOOLS = Set.of(
            // in-process LLM tools
            "handoff_to_team", "addgrouptask",
            // EDDI's MCP server, reached through an MCP client connection
            "discuss_with_group", "start_group_discussion", "add_team_task", "create_schedule", "fire_schedule_now",
            "retry_failed_schedule", "create_team_cadence", "schedule_lesson_consolidation", "chat_with_agent", "talk_to_agent",
            "create_conversation", "accept_team_handoff");

    /** Separators an MCP proxy or a tool namespace puts in front of a name. */
    private static final String NAMESPACE_SEPARATORS = "_.:/-";

    /** Whether the turn on this thread may call {@code toolName}. */
    public static boolean permits(Set<String> ceiling, String toolName) {
        if (ceiling == null) {
            return true;
        }
        return toolName != null && ceiling.contains(toolName.trim()) && !isSpawning(toolName);
    }

    /**
     * Whether {@code toolName} is, or ends in a namespaced, {@link #SPAWNING_TOOLS}
     * name (case-insensitive).
     */
    public static boolean isSpawning(String toolName) {
        if (toolName == null) {
            return false;
        }
        String name = toolName.trim().toLowerCase(Locale.ROOT);
        for (String spawning : SPAWNING_TOOLS) {
            if (name.equals(spawning) || (name.endsWith(spawning) && name.length() > spawning.length()
                    && NAMESPACE_SEPARATORS.indexOf(name.charAt(name.length() - spawning.length() - 1)) >= 0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The refusal for calling {@code toolName} in the turn on this thread, if any.
     */
    public static Optional<String> refuseTool(String toolName) {
        Set<String> ceiling = current();
        if (permits(ceiling, toolName)) {
            return Optional.empty();
        }
        return Optional.of(isSpawning(toolName) ? spawningRefusal(toolName) : refusal(toolName));
    }

    /** The text a refused spawning call answers with. */
    public static String spawningRefusal(String toolName) {
        return "Refused: the tool '" + toolName + "' hands work to a group, a team or another conversation where the tool limits "
                + "of this email's sender role would not apply, so it is never available under a sender role's tool limits.";
    }

    /**
     * The ceiling a schedule's metadata carries in {@link #CONTEXT_KEY} (a
     * check-back a turn under a ceiling booked), or {@code null}.
     */
    public static Set<String> fromMetadata(Map<String, Object> metadata) {
        return metadata == null ? null : fromValue(metadata.get(CONTEXT_KEY));
    }

    /**
     * {@code context} with {@link #CONTEXT_KEY} set to {@code ceiling}, as a new
     * mutable map; the argument itself when {@code ceiling} is {@code null}.
     */
    public static Map<String, Context> withCeiling(Map<String, Context> context, Set<String> ceiling) {
        if (ceiling == null) {
            return context;
        }
        Map<String, Context> narrowed = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
        narrowed.put(CONTEXT_KEY, toContext(ceiling));
        return narrowed;
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
