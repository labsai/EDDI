/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.HumanMemberConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OnHumanTimeout;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentGroupStore}'s save-time config checks (I3).
 * <p>
 * The preset case is the one that matters. A group saved as
 * {@code {"style":"DEBATE"}} stores no phases at all — the engine expands the
 * preset at discussion time — so a check that only reads {@code getPhases()} is
 * silent for exactly the configs that go on to hit the moderator-less fallback,
 * which is the substitution this warning exists to announce.
 *
 * @author tests
 */
class AgentGroupStoreTest {

    private AgentGroupConfiguration config(DiscussionStyle style, List<DiscussionPhase> phases, String moderator) {
        var c = new AgentGroupConfiguration();
        c.setName("G");
        c.setStyle(style);
        c.setPhases(phases);
        c.setModeratorAgentId(moderator);
        return c;
    }

    private DiscussionPhase phase(String name, String participants) {
        return new DiscussionPhase(name, PhaseType.SYNTHESIS, participants, TurnOrder.SEQUENTIAL,
                ContextScope.FULL, false, null, 1, false);
    }

    @Test
    void presetStyleWithoutModerator_warns() {
        // Every one of the six presets ends in a participants="MODERATOR" phase.
        for (DiscussionStyle style : DiscussionStyle.values()) {
            if (style == DiscussionStyle.CUSTOM) {
                continue;
            }
            assertFalse(AgentGroupStore.moderatorlessPhaseNames(config(style, null, null)).isEmpty(),
                    style + " expands to a MODERATOR phase and must be reported");
        }
    }

    @Test
    void presetStyleWithModerator_isSilent() {
        assertTrue(AgentGroupStore.moderatorlessPhaseNames(config(DiscussionStyle.DEBATE, null, "judge")).isEmpty());
    }

    @Test
    void blankModerator_countsAsAbsent() {
        assertFalse(AgentGroupStore.moderatorlessPhaseNames(config(DiscussionStyle.DEBATE, null, "   ")).isEmpty());
    }

    @Test
    void explicitPhases_areCheckedAsWritten() {
        var phases = List.of(phase("Open", "ALL"), phase("Wrap", "MODERATOR"), phase("Extra", "moderator"));

        var reported = AgentGroupStore.moderatorlessPhaseNames(config(DiscussionStyle.CUSTOM, phases, null));

        assertEquals(List.of("Wrap", "Extra"), reported, "the check is case-insensitive and skips non-moderator phases");
    }

    @Test
    void customStyleWithNoPhases_reportsNothing() {
        // CUSTOM expands to no phases; there is nothing to warn about, and falling
        // through to a default preset here would invent phases the author never wrote.
        assertTrue(AgentGroupStore.moderatorlessPhaseNames(config(DiscussionStyle.CUSTOM, List.of(), null)).isEmpty());
    }

    @Test
    void noModeratorPhasesAtAll_isSilent() {
        var phases = List.of(phase("Open", "ALL"), phase("Roles", "ROLE:PRO"));

        assertTrue(AgentGroupStore.moderatorlessPhaseNames(config(DiscussionStyle.CUSTOM, phases, null)).isEmpty());
    }

    // =================================================================
    // I6 — HUMAN member save-time matrix
    // =================================================================

    private GroupMember human(String id, String name) {
        return new GroupMember(id, name, 1, null, MemberType.HUMAN);
    }

    private AgentGroupConfiguration humanConfig(DiscussionStyle style, List<DiscussionPhase> phases, GroupMember... members) {
        var c = config(style, phases, null);
        c.setMembers(List.of(members));
        return c;
    }

    @Test
    void humanMember_withoutDisplayName_isRejected() {
        var problems = AgentGroupStore.humanMemberProblems(humanConfig(DiscussionStyle.CUSTOM,
                List.of(phase("Open", "ALL")), human("h-1", "  ")));

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("displayName"), problems.toString());
    }

    @Test
    void humanMember_inTaskForceGroup_isRejected_presetExpanded() {
        // TASK_FORCE stores NO phases — the preset expansion is what makes this
        // check reach PLAN/EXECUTE/VERIFY at all.
        var problems = AgentGroupStore.humanMemberProblems(humanConfig(DiscussionStyle.TASK_FORCE,
                null, human("h-1", "Hannah")));

        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("task-force")), problems.toString());
    }

    @Test
    void humanMember_inTargetEachPeerPhase_isRejected() {
        var peerPhase = new DiscussionPhase("Critique", PhaseType.CRITIQUE, "ALL", TurnOrder.SEQUENTIAL,
                ContextScope.FULL, true, null, 1, false);
        var problems = AgentGroupStore.humanMemberProblems(humanConfig(DiscussionStyle.CUSTOM,
                List.of(peerPhase), human("h-1", "Hannah")));

        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("targetEachPeer")), problems.toString());
    }

    @Test
    void humanMember_inPlainSequentialGroup_isAccepted() {
        var config = humanConfig(DiscussionStyle.CUSTOM, List.of(phase("Open", "ALL")),
                human("h-1", "Hannah"), new GroupMember("a-1", "Agent", 2, null));
        config.setHumanMemberConfig(new HumanMemberConfig("PT4H", OnHumanTimeout.SKIP_TURN));

        assertTrue(AgentGroupStore.humanMemberProblems(config).isEmpty());
    }

    @Test
    void humanTimeout_notIso8601_isRejected() {
        var config = humanConfig(DiscussionStyle.CUSTOM, List.of(phase("Open", "ALL")), human("h-1", "Hannah"));
        config.setHumanMemberConfig(new HumanMemberConfig("4 hours", null));

        var problems = AgentGroupStore.humanMemberProblems(config);

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("ISO-8601"), problems.toString());
    }

    @Test
    void humanTimeout_zeroOrNegative_isRejected() {
        // Duration.parse accepts both; armed, they would fire effectively
        // immediately and silently skip every human turn.
        for (String bad : new String[]{"PT0S", "PT-4H"}) {
            var config = humanConfig(DiscussionStyle.CUSTOM, List.of(phase("Open", "ALL")), human("h-1", "Hannah"));
            config.setHumanMemberConfig(new HumanMemberConfig(bad, null));

            var problems = AgentGroupStore.humanMemberProblems(config);

            assertEquals(1, problems.size(), bad);
            assertTrue(problems.get(0).contains("positive"), problems.toString());
        }
    }

    @Test
    void humanValidation_nullMembersList_neverNPEs() {
        var config = config(DiscussionStyle.CUSTOM, List.of(phase("Open", "ALL")), "mod");
        config.setMembers(null);

        assertTrue(AgentGroupStore.humanMemberProblems(config).isEmpty());
        assertFalse(AgentGroupStore.hasHumanMembers(config));
    }

    @Test
    void agentOnlyGroups_produceNoHumanProblems() {
        assertTrue(AgentGroupStore.humanMemberProblems(config(DiscussionStyle.TASK_FORCE, null, null)).isEmpty(),
                "the whole matrix only applies when a HUMAN member exists");
        assertFalse(AgentGroupStore.hasHumanMembers(config(DiscussionStyle.CUSTOM, null, null)));
        assertTrue(AgentGroupStore.hasHumanMembers(humanConfig(DiscussionStyle.CUSTOM, null, human("h", "H"))));
    }
}
