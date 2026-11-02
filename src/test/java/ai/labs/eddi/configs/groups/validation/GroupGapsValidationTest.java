/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.validation;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OptionsSource;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteMethod;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig.OnTieBreakTimeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GroupGapsValidation — HUMAN_DECIDES save-time rules")
class GroupGapsValidationTest {

    private static GroupMember human(String id) {
        return new GroupMember(id, "Human " + id, 1, null, MemberType.HUMAN);
    }

    private static GroupMember agent(String id) {
        return new GroupMember(id, id, 2, null);
    }

    private static AgentGroupConfiguration group(GroupMember... members) {
        var config = new AgentGroupConfiguration();
        config.setName("g");
        config.setMembers(List.of(members));
        return config;
    }

    private static VoteConfig vote(HumanTieBreakConfig tieBreak) {
        return new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, List.of("A", "B"), 0.5, Map.of(), false,
                TiePolicy.HUMAN_DECIDES, tieBreak);
    }

    private static List<String> problems(VoteConfig vote, AgentGroupConfiguration config) {
        return GroupGapsValidation.tieBreakProblems("phases[0].voteConfig", vote, config);
    }

    @Nested
    @DisplayName("decider resolution")
    class Decider {

        @Test
        @DisplayName("an explicit decider wins, member or not")
        void explicit() {
            assertEquals("ceo@acme.com", GroupGapsValidation.resolveDecider(new HumanTieBreakConfig("ceo@acme.com", null, null),
                    group(agent("a1"), human("h1"), human("h2"))));
        }

        @Test
        @DisplayName("without one, the single HUMAN member decides")
        void singleHuman() {
            assertEquals("h1", GroupGapsValidation.resolveDecider(new HumanTieBreakConfig(), group(agent("a1"), human("h1"))));
        }

        @Test
        @DisplayName("zero or two HUMAN members cannot default — the config must name one")
        void ambiguous() {
            assertNull(GroupGapsValidation.resolveDecider(new HumanTieBreakConfig(), group(agent("a1"))));
            assertNull(GroupGapsValidation.resolveDecider(null, group(human("h1"), human("h2"))));
            assertTrue(problems(vote(null), group(agent("a1"))).getFirst().contains("humanTieBreak.decider is required"));
            assertTrue(problems(vote(null), group(human("h1"), human("h2"))).getFirst().contains("decider is required"));
        }
    }

    @Test
    @DisplayName("a valid HUMAN_DECIDES config has no problems")
    void valid() {
        assertTrue(problems(vote(new HumanTieBreakConfig(null, "PT24H", OnTieBreakTimeout.NO_DECISION)), group(human("h1"))).isEmpty());
    }

    @Test
    @DisplayName("other tie policies are not this validator's business")
    void otherPolicies() {
        var moderator = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, List.of("A", "B"), 0.5, Map.of(), false,
                TiePolicy.MODERATOR_DECIDES);
        assertTrue(problems(moderator, group(agent("a1"))).isEmpty());
        assertTrue(problems(null, group(agent("a1"))).isEmpty());
    }

    @Test
    @DisplayName("the timeout must be a positive ISO-8601 duration")
    void timeout() {
        assertTrue(problems(vote(new HumanTieBreakConfig("x", "tomorrow", null)), group()).getFirst().contains("ISO-8601"));
        assertTrue(problems(vote(new HumanTieBreakConfig("x", "PT0S", null)), group()).getFirst().contains("positive"));
        assertTrue(problems(vote(new HumanTieBreakConfig("x", "PT-1H", null)), group()).getFirst().contains("positive"));
    }

    @Test
    @DisplayName("a MODERATOR_DECIDES timeout fallback needs a moderator")
    void moderatorFallback() {
        var tieBreak = new HumanTieBreakConfig("x", null, OnTieBreakTimeout.MODERATOR_DECIDES);
        assertTrue(problems(vote(tieBreak), group()).getFirst().contains("moderatorAgentId"));
        var withModerator = group();
        withModerator.setModeratorAgentId("mod");
        assertTrue(problems(vote(tieBreak), withModerator).isEmpty());
    }
}
