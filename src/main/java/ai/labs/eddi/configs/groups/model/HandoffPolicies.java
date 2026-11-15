/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The configuration records behind native cross-team handoff: a team (or a
 * standalone agent) files a task into ANOTHER team's standing backlog.
 * <p>
 * <b>Two-sided consent.</b> An edge source to target exists only when both
 * hold: the source lists the target ({@code targets}) AND the target's
 * {@link AcceptPolicy} lists the source. Neither side can create the edge
 * alone, so an author with EDIT on agent X who names group Y gains nothing
 * until Y's owner accepts {@code agent:X}.
 * <p>
 * <b>Absence is off.</b> A {@code null} config means the
 * {@code handoff_to_team} tool is never assembled (it costs no prompt tokens
 * and cannot be argued with); a group with only {@code accept} receives but
 * never sends.
 * <p>
 * Every record normalizes in its compact constructor, the same choke-point
 * style as {@code AgentGroupConfiguration.GroupTaskConfig}, so no reader
 * re-derives defaults from a partially specified JSON. Hard ceilings are
 * clamped here; <em>shape</em> problems (a malformed alias, a duplicate source)
 * are rejected at save time by {@code HandoffConfigValidator}.
 *
 * @since 6.7.0
 */
public final class HandoffPolicies {

    private HandoffPolicies() {
    }

    /** Alias grammar: what the model sees in the tool's {@code team} enum. */
    public static final Pattern ALIAS = Pattern.compile("[a-z0-9-]{1,40}");
    /** Accept-rule source grammar: an exact id, never a wildcard (v1). */
    public static final Pattern SOURCE = Pattern.compile("(agent|group):\\S+");

    public static final int MAX_TARGETS = 20;
    public static final int MAX_DESCRIPTION_OF_TARGET = 300;
    public static final int MAX_TEMPLATE_CHARS = 1000;
    public static final int MAX_ACCEPT_RULES = 100;
    public static final int MAX_DECISION_RULES = 20;

    public static final int DEFAULT_MAX_PER_TURN = 2;
    public static final int HARD_MAX_PER_TURN = 5;
    public static final int DEFAULT_MAX_PER_CONVERSATION = 5;
    public static final int DEFAULT_MAX_PER_DISCUSSION = 10;
    public static final int HARD_MAX_PER_RUN = 50;
    public static final int DEFAULT_MAX_DESCRIPTION_CHARS = 2000;
    public static final int HARD_MAX_DESCRIPTION_CHARS = 4000;
    public static final int DEFAULT_PRIORITY = 5;
    public static final int MAX_PRIORITY = 10;
    public static final int DEFAULT_ACCEPT_MAX_PER_DAY = 50;
    public static final int HARD_MAX_PER_DAY = 500;
    public static final int DEFAULT_MAX_INBOX = 50;
    public static final int HARD_MAX_INBOX = 100;

    /** {@code toolParticipants} value: every member gets the tool. */
    public static final String PARTICIPANTS_ALL = "ALL";
    /** {@code toolParticipants} value: only the moderator gets the tool. */
    public static final String PARTICIPANTS_MODERATOR = "MODERATOR";
    /** {@code toolParticipants} prefix: members holding the role after it. */
    public static final String PARTICIPANTS_ROLE_PREFIX = "ROLE:";

    /** How a handoff from an accepted source lands on the receiving team. */
    public enum IntakeMode {
        /** Lands as PENDING in the backlog; a cadence may pull it at once. */
        AUTO,
        /**
         * Lands in the team's intake inbox and is never pulled by a cadence until a
         * human with EDIT on the target accepts it. The default.
         */
        REVIEW
    }

    /**
     * One team a source may hand off to.
     *
     * @param alias
     *            what the model addresses it by ({@code [a-z0-9-]{1,40}}, unique)
     * @param groupId
     *            the target group's config id. Existence is deliberately not
     *            checked at save time, so an agent ZIP whose target does not exist
     *            here still imports; an unresolvable target is dropped at runtime
     * @param description
     *            when to hand off to this team, shown to the model (at most
     *            {@value HandoffPolicies#MAX_DESCRIPTION_OF_TARGET} characters)
     * @param defaultPriority
     *            priority used when the model gives none, 0..10 (default 5)
     */
    public record HandoffTarget(String alias, String groupId, String description, Integer defaultPriority) {

        public HandoffTarget {
            alias = alias == null ? null : alias.strip();
            groupId = groupId == null ? null : groupId.strip();
            defaultPriority = clamp(defaultPriority == null ? DEFAULT_PRIORITY : defaultPriority, 0, MAX_PRIORITY);
        }
    }

    /**
     * Source side for a standalone agent: the {@code teamHandoff} field of an LLM
     * task.
     */
    public record TeamHandoffConfig(List<HandoffTarget> targets, int maxPerTurn, int maxPerConversation, int maxDescriptionChars) {

        public TeamHandoffConfig {
            targets = targets == null ? List.of() : List.copyOf(targets);
            maxPerTurn = clampOrDefault(maxPerTurn, DEFAULT_MAX_PER_TURN, HARD_MAX_PER_TURN);
            maxPerConversation = clampOrDefault(maxPerConversation, DEFAULT_MAX_PER_CONVERSATION, HARD_MAX_PER_RUN);
            maxDescriptionChars = clampOrDefault(maxDescriptionChars, DEFAULT_MAX_DESCRIPTION_CHARS, HARD_MAX_DESCRIPTION_CHARS);
        }
    }

    /**
     * A deterministic rule that files a handoff when a group discussion completes
     * with a matching structured decision, so a VOTE winner is filed without
     * relying on an LLM to remember.
     *
     * @param target
     *            an alias declared in the group's {@code targets}
     * @param decisionTypes
     *            {@code DecisionType} names that fire the rule (empty means none)
     * @param requireWinner
     *            skip when the decision has no winner (default true: a tie must not
     *            file "Research account: null")
     * @param subjectTemplate
     *            Qute-free {@code {decision.winner}} / {@code {decision.outcome}} /
     *            {@code {decision.type}} substitution; the values are DATA, never
     *            evaluated as a template
     * @param descriptionTemplate
     *            same substitution; optional
     * @param priority
     *            0..10, {@code null} uses the target's default
     */
    public record DecisionRule(String target, List<String> decisionTypes, Boolean requireWinner, String subjectTemplate,
            String descriptionTemplate, Integer priority) {

        public DecisionRule {
            target = target == null ? null : target.strip();
            decisionTypes = decisionTypes == null ? List.of() : List.copyOf(decisionTypes);
            requireWinner = requireWinner == null ? Boolean.TRUE : requireWinner;
            priority = priority == null ? null : clamp(priority, 0, MAX_PRIORITY);
        }
    }

    /**
     * One source a team accepts handoffs from.
     *
     * @param source
     *            {@code agent:<id>} or {@code group:<id>}, exact ids, no wildcards.
     *            Accepting {@code agent:X} trusts everyone with EDIT on X
     * @param mode
     *            {@link IntakeMode#REVIEW} (default) or {@link IntakeMode#AUTO}
     * @param maxPerDay
     *            per-source cap per UTC day, {@code null} for no per-source cap
     *            (the team-wide {@link AcceptPolicy#maxPerDay()} still applies)
     */
    public record AcceptRule(String source, IntakeMode mode, Integer maxPerDay) {

        public AcceptRule {
            source = source == null ? null : source.strip();
            mode = mode == null ? IntakeMode.REVIEW : mode;
            maxPerDay = maxPerDay == null ? null : clamp(maxPerDay, 1, HARD_MAX_PER_DAY);
        }
    }

    /**
     * The receiving side: who may hand off to this team and how it lands.
     *
     * @param from
     *            accepted sources; empty means the team accepts nothing
     * @param maxPerDay
     *            team-wide intake per UTC day across all sources (default 50)
     * @param maxInbox
     *            pending REVIEW items held at once (default 50, at most 100)
     */
    public record AcceptPolicy(List<AcceptRule> from, int maxPerDay, int maxInbox) {

        public AcceptPolicy {
            from = from == null ? List.of() : List.copyOf(from);
            maxPerDay = clampOrDefault(maxPerDay, DEFAULT_ACCEPT_MAX_PER_DAY, HARD_MAX_PER_DAY);
            maxInbox = clampOrDefault(maxInbox, DEFAULT_MAX_INBOX, HARD_MAX_INBOX);
        }

        /** The rule naming {@code sourceKey}, or {@code null}: the consent check. */
        public AcceptRule ruleFor(String sourceKey) {
            if (sourceKey == null) {
                return null;
            }
            for (AcceptRule rule : from) {
                if (rule != null && sourceKey.equals(rule.source())) {
                    return rule;
                }
            }
            return null;
        }
    }

    /**
     * The group-side config: {@code AgentGroupConfiguration.handoffConfig}.
     * <p>
     * Unlike {@code taskListConfig}, whose null means "tools absent", this is two
     * independent halves: {@code targets}/{@code onDecision} make the team a
     * <em>sender</em>, {@code accept} makes it a <em>receiver</em>. Either may be
     * empty.
     *
     * @param toolParticipants
     *            who gets the {@code handoff_to_team} tool inside a discussion:
     *            {@code ALL}, {@code MODERATOR} (default) or {@code ROLE:<role>}
     */
    public record GroupHandoffConfig(List<HandoffTarget> targets, String toolParticipants, int maxPerTurn, int maxPerDiscussion,
            int maxDescriptionChars, List<DecisionRule> onDecision, AcceptPolicy accept) {

        public GroupHandoffConfig {
            targets = targets == null ? List.of() : List.copyOf(targets);
            toolParticipants = toolParticipants == null || toolParticipants.isBlank() ? PARTICIPANTS_MODERATOR : toolParticipants.strip();
            maxPerTurn = clampOrDefault(maxPerTurn, DEFAULT_MAX_PER_TURN, HARD_MAX_PER_TURN);
            maxPerDiscussion = clampOrDefault(maxPerDiscussion, DEFAULT_MAX_PER_DISCUSSION, HARD_MAX_PER_RUN);
            maxDescriptionChars = clampOrDefault(maxDescriptionChars, DEFAULT_MAX_DESCRIPTION_CHARS, HARD_MAX_DESCRIPTION_CHARS);
            onDecision = onDecision == null ? List.of() : List.copyOf(onDecision);
        }

        /** Whether this team can send at all (a tool target or a decision rule). */
        public boolean sends() {
            return !targets.isEmpty();
        }

        /** The target with {@code alias}, or {@code null}. */
        public HandoffTarget targetByAlias(String alias) {
            return findTarget(targets, alias);
        }
    }

    /** The target with {@code alias} in {@code targets}, or {@code null}. */
    public static HandoffTarget findTarget(List<HandoffTarget> targets, String alias) {
        if (targets == null || alias == null) {
            return null;
        }
        for (HandoffTarget target : targets) {
            if (target != null && alias.equals(target.alias())) {
                return target;
            }
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Non-positive means "unset" (a mistyped 0 is never "unlimited"); above ceiling
     * clamps.
     */
    private static int clampOrDefault(int value, int defaultValue, int ceiling) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, ceiling);
    }
}
