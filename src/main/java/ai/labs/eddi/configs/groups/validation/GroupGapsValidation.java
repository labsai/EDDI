/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.validation;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig.OnTieBreakTimeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Save-time rules for the group capabilities that put a human where the engine
 * used to refuse one: {@code tiePolicy: HUMAN_DECIDES} today. Every rule reads
 * only fields or combinations that were refused outright before, so no stored
 * config can start failing.
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
