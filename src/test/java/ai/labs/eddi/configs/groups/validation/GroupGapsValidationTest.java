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

    @Nested
    @DisplayName("humans in task forces")
    class TaskForce {

        // as the TASK_FORCE preset writes them: the moderator plans and verifies
        private final List<AgentGroupConfiguration.DiscussionPhase> planAndVerify = List.of(
                new AgentGroupConfiguration.DiscussionPhase("Plan", AgentGroupConfiguration.PhaseType.PLAN, "MODERATOR",
                        AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL, false, null, 1),
                new AgentGroupConfiguration.DiscussionPhase("Execute", AgentGroupConfiguration.PhaseType.EXECUTE),
                new AgentGroupConfiguration.DiscussionPhase("Verify", AgentGroupConfiguration.PhaseType.VERIFY, "MODERATOR",
                        AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL, false, null, 1));

        @Test
        @DisplayName("a human executor with an agent moderator is fine")
        void humanExecutor() {
            var config = group(agent("lead"), human("h1"));
            config.setModeratorAgentId("lead");
            assertTrue(GroupGapsValidation.humanTaskForceProblems(config, planAndVerify).isEmpty());
        }

        @Test
        @DisplayName("a human moderator would plan and verify — refused")
        void humanModerator() {
            var config = group(agent("a1"), human("h1"));
            config.setModeratorAgentId("h1");
            assertTrue(GroupGapsValidation.humanTaskForceProblems(config, planAndVerify).getFirst().contains("plan or verify"));
        }

        @Test
        @DisplayName("without a moderator, the first member by speaking order stands in — a human there is refused")
        void firstBySpeakingOrder() {
            var humanFirst = group(new GroupMember("h1", "H", 0, null, MemberType.HUMAN), new GroupMember("a1", "A", 1, null));
            var agentFirst = group(new GroupMember("a1", "A", 0, null), new GroupMember("h1", "H", 1, null, MemberType.HUMAN));
            assertTrue(!GroupGapsValidation.humanTaskForceProblems(humanFirst, planAndVerify).isEmpty());
            assertTrue(GroupGapsValidation.humanTaskForceProblems(agentFirst, planAndVerify).isEmpty());
        }

        @Test
        @DisplayName("a PLAN phase whose participants are ALL or a ROLE reaches a human even when the moderator is an agent")
        void participantsDecideNotTheModerator() {
            var config = group(new GroupMember("h1", "H", 0, "lead", MemberType.HUMAN), new GroupMember("a1", "A", 1, null));
            config.setModeratorAgentId("a1");
            var all = new AgentGroupConfiguration.DiscussionPhase("Plan", AgentGroupConfiguration.PhaseType.PLAN, "ALL",
                    AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL, false, null, 1, false, null);
            var role = new AgentGroupConfiguration.DiscussionPhase("Verify", AgentGroupConfiguration.PhaseType.VERIFY, "ROLE:lead",
                    AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL, false, null, 1, false, null);
            var moderator = new AgentGroupConfiguration.DiscussionPhase("Plan", AgentGroupConfiguration.PhaseType.PLAN, "MODERATOR",
                    AgentGroupConfiguration.TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL, false, null, 1, false, null);

            assertTrue(!GroupGapsValidation.humanTaskForceProblems(config, List.of(all)).isEmpty());
            assertTrue(!GroupGapsValidation.humanTaskForceProblems(config, List.of(role)).isEmpty());
            assertTrue(GroupGapsValidation.humanTaskForceProblems(config, List.of(moderator)).isEmpty(), "the agent moderator plans here");
        }

        @Test
        @DisplayName("EXECUTE alone has no planner or verifier to worry about")
        void executeOnly() {
            var config = group(human("h1"));
            config.setModeratorAgentId("h1");
            assertTrue(GroupGapsValidation.humanTaskForceProblems(config,
                    List.of(new AgentGroupConfiguration.DiscussionPhase("Execute", AgentGroupConfiguration.PhaseType.EXECUTE))).isEmpty());
        }
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
