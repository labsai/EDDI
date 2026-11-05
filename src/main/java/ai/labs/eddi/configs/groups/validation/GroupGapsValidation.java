/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.validation;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig.OnTieBreakTimeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Save-time rules for the group capabilities that put a human where the engine
 * used to refuse one: {@code tiePolicy: HUMAN_DECIDES} and HUMAN task-force
 * executors. Every rule reads only fields or combinations that were refused
 * outright before, so no stored config can start failing.
 */
public final class GroupGapsValidation {

    private GroupGapsValidation() {
    }

    /**
     * Problems with one VOTE phase's {@code HUMAN_DECIDES} tie policy. Empty for
     * any other policy.
     *
     * @param path
     *            the config path to name in messages, e.g.
     *            {@code phases[2].voteConfig}
     */
    public static List<String> tieBreakProblems(String path, VoteConfig voteConfig, AgentGroupConfiguration config) {
        List<String> problems = new ArrayList<>();
        if (voteConfig == null || voteConfig.tiePolicy() != TiePolicy.HUMAN_DECIDES) {
            return problems;
        }
        HumanTieBreakConfig tieBreak = voteConfig.humanTieBreak() != null ? voteConfig.humanTieBreak() : new HumanTieBreakConfig();
        if (resolveDecider(tieBreak, config) == null) {
            problems.add(path + ".humanTieBreak.decider is required — tiePolicy HUMAN_DECIDES needs one principal to decide, "
                    + "and the group does not have exactly one HUMAN member to default to");
        }
        if (tieBreak.timeout() != null) {
            try {
                Duration parsed = Duration.parse(tieBreak.timeout());
                if (parsed.isZero() || parsed.isNegative()) {
                    problems.add(path + ".humanTieBreak.timeout must be a positive duration, not '" + tieBreak.timeout() + "'");
                }
            } catch (Exception e) {
                problems.add(path + ".humanTieBreak.timeout must be an ISO-8601 duration (e.g. PT24H), not '" + tieBreak.timeout() + "'");
            }
        }
        if (tieBreak.onTimeout() == OnTieBreakTimeout.MODERATOR_DECIDES
                && (config.getModeratorAgentId() == null || config.getModeratorAgentId().isBlank())) {
            problems.add(path + ".humanTieBreak.onTimeout MODERATOR_DECIDES needs a moderatorAgentId on the group");
        }
        return problems;
    }

    /**
     * HUMAN members in a task-force group (PLAN/EXECUTE/VERIFY phases) execute
     * tasks; they may not <em>plan</em> or <em>verify</em>. Plan parsing and the
     * JSON verdict happen inside the planner's and verifier's own turn, which has
     * no resume point — and prose from a person would silently auto-verify every
     * task (the verifier's documented fail-open). So a group with PLAN or VERIFY
     * phases is refused when its planner/verifier would be a HUMAN member: the
     * moderator, or — without one — the first member by speaking order, which is
     * who stands in.
     *
     * @param phases
     *            the phases the group runs (preset-expanded by the caller)
     */
    public static List<String> humanTaskForceProblems(AgentGroupConfiguration config, List<DiscussionPhase> phases) {
        List<String> problems = new ArrayList<>();
        boolean plansOrVerifies = phases != null && phases.stream().filter(Objects::nonNull)
                .anyMatch(p -> p.type() == PhaseType.PLAN || p.type() == PhaseType.VERIFY);
        if (!plansOrVerifies || config.getMembers() == null) {
            return problems;
        }
        // Each PLAN/VERIFY phase is resolved on its own, the way TaskForceEngine does:
        // the first of
        // the phase's participants plans or verifies, and `participants` is not always
        // the moderator.
        var reported = new LinkedHashSet<String>();
        for (DiscussionPhase phase : phases) {
            if (phase == null || (phase.type() != PhaseType.PLAN && phase.type() != PhaseType.VERIFY)) {
                continue;
            }
            GroupMember planner = firstParticipant(phase, config);
            if (planner != null && planner.memberType() == MemberType.HUMAN && reported.add(planner.agentId())) {
                problems.add("HUMAN member '" + planner.agentId() + "' would plan or verify the task force (PLAN/VERIFY phase '" + phase.name()
                        + "') — a person can execute tasks, but planning and verification need an agent's structured reply. "
                        + "Name an agent as moderatorAgentId, or give the phase agent participants");
            }
        }
        return problems;
    }

    /**
     * The member who takes a phase's first turn, resolved with the rules of
     * {@code GroupConversationService.resolveParticipants} (which the task-force
     * engine uses for its planner and verifier): {@code MODERATOR} is the
     * moderator, or the first member by speaking order when none is configured;
     * {@code ROLE:x} is the first member with that role, falling back to everyone
     * when nobody has it; anything else is the first member by speaking order.
     */
    static GroupMember firstParticipant(DiscussionPhase phase, AgentGroupConfiguration config) {
        List<GroupMember> members = config.getMembers().stream().filter(Objects::nonNull).toList();
        String participants = phase.participants() != null ? phase.participants() : "ALL";
        if ("MODERATOR".equalsIgnoreCase(participants)) {
            String moderator = config.getModeratorAgentId();
            if (moderator == null || moderator.isBlank()) {
                return firstBySpeakingOrder(members);
            }
            return members.stream().filter(m -> moderator.equals(m.agentId())).findFirst().orElse(null);
        }
        if (participants.toUpperCase(Locale.ROOT).startsWith("ROLE:")) {
            String role = participants.substring(5).trim();
            List<GroupMember> withRole = members.stream().filter(m -> role.equalsIgnoreCase(m.role())).toList();
            return firstBySpeakingOrder(withRole.isEmpty() ? members : withRole);
        }
        return firstBySpeakingOrder(members);
    }

    private static GroupMember firstBySpeakingOrder(List<GroupMember> members) {
        return members.stream().min(Comparator.comparingInt(m -> m.speakingOrder() != null ? m.speakingOrder() : Integer.MAX_VALUE)).orElse(null);
    }

    /**
     * Who decides a tie: the configured {@code decider}, else the group's single
     * HUMAN member, else {@code null} (none or several — ambiguous).
     */
    public static String resolveDecider(HumanTieBreakConfig tieBreak, AgentGroupConfiguration config) {
        if (tieBreak != null && tieBreak.decider() != null) {
            return tieBreak.decider();
        }
        List<GroupMember> humans = config == null || config.getMembers() == null
                ? List.of()
                : config.getMembers().stream()
                        .filter(Objects::nonNull)
                        .filter(m -> m.memberType() == MemberType.HUMAN && m.agentId() != null && !m.agentId().isBlank())
                        .toList();
        return humans.size() == 1 ? humans.getFirst().agentId() : null;
    }
}
