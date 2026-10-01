/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.engine.model.Context;

import java.util.List;

/**
 * The group(s) a conversation belongs to — what {@code group}-visibility
 * memories are scoped to, on both the write paths (the {@code rememberFact}
 * tool and the {@code longTerm} property boundary) and recall.
 * <p>
 * {@code groupId} arrives as a <b>context</b> value —
 * {@code MemberTurnExecutor} and {@code GroupLifecycleOps} both inject it that
 * way. It is read from the current step first, then from an earlier step whose
 * discussion confirms the membership — see
 * {@link #resolveGroupIds(IConversationMemory, MembershipCheck)}.
 * <p>
 * The context value is the only source. A {@code groupId} conversation
 * <em>property</em> used to be honoured as a last resort, but properties are
 * not a trusted channel — a client can set them per turn through
 * {@code properties} context expressions, and property setters can capture user
 * input into them — so the fallback let a conversation claim membership of any
 * group and reach its shared memories. Group membership is a runtime fact the
 * group orchestrator asserts, and {@code ClientContextGuard} keeps clients from
 * asserting it through the context key instead.
 */
public final class ConversationGroups {

    private static final String CONTEXT_KEY = "context:groupId";
    private static final String DISCUSSION_KEY = "context:groupConversationId";

    private ConversationGroups() {
    }

    /**
     * Confirms an earlier step's group claim against the discussion that step
     * names. Implemented by {@code LiveDiscussionRegistry#isLiveMember}: the
     * discussion is running on this node, has this conversation as a member, and
     * belongs to that group.
     */
    @FunctionalInterface
    public interface MembershipCheck {
        boolean isLiveMember(String groupConversationId, String conversationId, String groupId);
    }

    /**
     * {@link #resolveGroupIds(IConversationMemory, MembershipCheck)} without a
     * membership check: only the current step's {@code groupId} counts.
     */
    public static List<String> resolveGroupIds(IConversationMemory memory) {
        return resolveGroupIds(memory, null);
    }

    /**
     * The conversation's group: the current step's {@code context:groupId}, else
     * the most recent earlier step's — but an earlier value only when
     * {@code membershipCheck} confirms it.
     * <p>
     * The current step's value was written this turn, and since
     * {@code ClientContextGuard} removes the key from client input it can only have
     * come from the group orchestrator. An earlier step's may predate that guard: a
     * conversation in which a client forged {@code groupId} before the fix still
     * carries it, and trusting it would keep that client in another team's group
     * memory — reading it through the tool and writing into it at the
     * {@code longTerm} boundary — for as long as the conversation lives. So a
     * fallback value counts only when the same step's {@code groupConversationId}
     * names a discussion that is running, that this conversation is a member of,
     * and that belongs to that group. Engine-driven member turns (the discussion
     * loop, follow-ups) always carry the context on the current step; the fallback
     * serves a turn the owner sends into a member conversation while its discussion
     * runs. {@code null} check, or anything unverified: no group (self scope).
     * <p>
     * Exact-key reads throughout ({@code getData} / {@code getExactDataPerStep}):
     * the prefix-matching {@code getLatestData} / {@code getAllLatestData} would
     * also return a client-sent {@code context:groupIdSuffix} as this
     * conversation's group.
     */
    public static List<String> resolveGroupIds(IConversationMemory memory, MembershipCheck membershipCheck) {
        var currentStep = memory.getCurrentStep();
        if (currentStep != null) {
            String fromCurrent = contextValueAsString(currentStep.getData(CONTEXT_KEY));
            if (fromCurrent != null) {
                return List.of(fromCurrent);
            }
        }

        var allSteps = memory.getAllSteps();
        if (membershipCheck == null || allSteps == null) {
            return List.of();
        }
        List<IData<Object>> priorGroupIds = allSteps.getExactDataPerStep(CONTEXT_KEY);
        List<IData<Object>> priorDiscussions = allSteps.getExactDataPerStep(DISCUSSION_KEY);
        if (priorGroupIds == null || priorDiscussions == null || priorGroupIds.size() != priorDiscussions.size()) {
            return List.of();
        }
        // Most recent first: both lists hold one entry per step, oldest first.
        for (int i = priorGroupIds.size() - 1; i >= 0; i--) {
            String groupId = contextValueAsString(priorGroupIds.get(i));
            if (groupId == null) {
                continue;
            }
            String discussionId = contextValueAsString(priorDiscussions.get(i));
            boolean verified = discussionId != null && membershipCheck.isLiveMember(discussionId, memory.getConversationId(), groupId);
            return verified ? List.of(groupId) : List.of();
        }
        return List.of();
    }

    /**
     * The visibility a {@code longTerm} property is persisted with: its own, else
     * the agent's {@code userMemoryConfig.defaultVisibility}, else {@code global}
     * (the legacy unscoped behaviour). A {@code group} property outside any group
     * is stored as {@code self} — a group entry with no group matches no reader,
     * and {@code self} is the only scope that keeps it reachable without widening
     * it. The turn boundary and undo/redo both persist through this, so an undone
     * or redone property lands exactly where the turn put it.
     */
    public static Visibility persistedVisibility(Property property, AgentConfiguration.UserMemoryConfig config, List<String> groupIds) {
        Visibility vis = property.getVisibility() != null ? property.getVisibility() : configuredDefault(config);
        return vis == Visibility.group && (groupIds == null || groupIds.isEmpty()) ? Visibility.self : vis;
    }

    private static Visibility configuredDefault(AgentConfiguration.UserMemoryConfig config) {
        if (config == null || config.getDefaultVisibility() == null) {
            return Visibility.global;
        }
        try {
            return Visibility.valueOf(config.getDefaultVisibility());
        } catch (IllegalArgumentException e) {
            return Visibility.global;
        }
    }

    /** Unwraps a {@code context:*} data entry, which holds a {@link Context}. */
    private static String contextValueAsString(IData<?> data) {
        if (data == null || data.getResult() == null) {
            return null;
        }
        Object result = data.getResult();
        Object value = result instanceof Context ctx ? ctx.getValue() : result;
        if (value == null) {
            return null;
        }
        String asString = String.valueOf(value);
        return asString.isBlank() ? null : asString;
    }
}
