/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Context keys the engine writes for itself and must never accept from a
 * client.
 * <p>
 * A conversation's context map is one namespace shared by two very different
 * writers: the client, which may put anything it likes into
 * {@code InputData.context} or the start-conversation body, and the engine's
 * own orchestrators — {@code MemberTurnExecutor} and {@code GroupLifecycleOps}
 * for group members, {@code ConverseWithAgentTool} for delegation — which use
 * the same map to hand a member or callee its policy. {@code Conversation}
 * stores every entry verbatim as {@code context:<key>} step data, and the
 * readers ({@code DynamicAgentToolsProvider}, {@code ContextualToolsProvider},
 * {@code AgentOrchestrator}, {@code Conversation.extractGroupIds}) cannot tell
 * who wrote it. So a client that sent {@code dynamicAgentConfig} unlocked the
 * sub-agent tools on a standalone agent with limits of its own choosing, one
 * that sent {@code dynamicCreatedAgentIds} could get any agent torn down and
 * permanently deleted, and one that sent {@code groupId} loaded another team's
 * group-visible memories.
 * <p>
 * The fix is to decide who wrote a key at the boundary, where that is still
 * known: every entry point that accepts context from outside the engine calls
 * {@link #stripFromExternal} before the map reaches
 * {@code ConversationService}. The engine-internal overloads
 * ({@code startConversation} and the agent-id {@code say}) stay trusting,
 * because their only callers are the orchestrators above.
 * <p>
 * Dropped rather than rejected: a client that echoes back the context it once
 * saw, or a trigger whose {@code initialContext} predates this list, keeps
 * working, and the key it can no longer set was never its to set. The drop is
 * logged at WARN so it is not silent.
 */
public final class ReservedContextKeys {

    private static final Logger LOGGER = Logger.getLogger(ReservedContextKeys.class);

    /** The group the member conversation belongs to — scopes group memory. */
    public static final String GROUP_ID = "groupId";
    /** The running discussion — gates recruit, artifact and group-task tools. */
    public static final String GROUP_CONVERSATION_ID = "groupConversationId";
    /** Nesting depth of a group-of-groups discussion. */
    public static final String GROUP_DEPTH = "groupDepth";
    /** The discussion transcript handed to a member turn. */
    public static final String GROUP_TRANSCRIPT = "groupTranscript";
    /** The group's dynamic-agent policy for a member turn. */
    public static final String DYNAMIC_AGENT_CONFIG = "dynamicAgentConfig";
    /** The discussion-wide created-agent total for a member turn. */
    public static final String DYNAMIC_CREATED_AGENT_IDS = "dynamicCreatedAgentIds";
    /**
     * Delegation hop count carried into a callee by {@code converse_with_agent}.
     */
    public static final String DELEGATION_DEPTH = "delegationDepth";

    /** Every key above. */
    public static final Set<String> ALL = Set.of(GROUP_ID, GROUP_CONVERSATION_ID, GROUP_DEPTH, GROUP_TRANSCRIPT, DYNAMIC_AGENT_CONFIG,
            DYNAMIC_CREATED_AGENT_IDS, DELEGATION_DEPTH);

    private ReservedContextKeys() {
    }

    /** Whether {@code key} is engine-reserved. */
    public static boolean isReserved(String key) {
        return key != null && ALL.contains(key);
    }

    /**
     * {@code context} without the engine-reserved keys, for a map that came from
     * outside the engine.
     * <p>
     * Returns the same instance when nothing had to be removed, so an immutable map
     * a caller built stays usable, and a mutable copy otherwise. Never modifies the
     * argument. {@code null} stays {@code null}.
     *
     * @param context
     *            client-supplied context, may be null
     * @param source
     *            where it came from, for the log line (e.g. {@code "REST say"})
     */
    public static Map<String, Context> stripFromExternal(Map<String, Context> context, String source) {
        if (context == null || context.isEmpty()) {
            return context;
        }
        List<String> dropped = context.keySet().stream().filter(ReservedContextKeys::isReserved).sorted().toList();
        if (dropped.isEmpty()) {
            return context;
        }
        Map<String, Context> filtered = new LinkedHashMap<>(context);
        dropped.forEach(filtered::remove);
        LOGGER.warnf("Dropped engine-reserved context key(s) %s from %s input — these are set by the engine only",
                sanitize(String.join(",", dropped)), sanitize(source));
        return filtered;
    }

    /**
     * Strips the reserved keys from {@code inputData}'s context in place (by
     * replacing the map, never by mutating it). Returns the same {@code inputData}
     * for chaining; {@code null} is passed through.
     */
    public static InputData stripFromExternal(InputData inputData, String source) {
        if (inputData != null) {
            Map<String, Context> original = inputData.getContext();
            Map<String, Context> filtered = stripFromExternal(original, source);
            if (filtered != original) {
                inputData.setContext(filtered);
            }
        }
        return inputData;
    }
}
