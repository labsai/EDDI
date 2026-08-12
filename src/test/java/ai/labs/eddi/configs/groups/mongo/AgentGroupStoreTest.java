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
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OptionsSource;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteMethod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    // I14 — VOTE phase validation: independence enforced, not advised
    // =================================================================

    private DiscussionPhase votePhase(TurnOrder turnOrder, ContextScope scope, VoteConfig voteConfig) {
        return new DiscussionPhase("Ballot", PhaseType.VOTE, "ALL", turnOrder, scope, false, null, 1, false, null, false, voteConfig);
    }

    private AgentGroupConfiguration voteGroup(DiscussionPhase phase) {
        return config(DiscussionStyle.CUSTOM, List.of(phase), "mod");
    }

    @Test
    void votePhase_parallelAndNone_isAccepted() {
        assertDoesNotThrow(() -> AgentGroupStore.validateVotePhases(voteGroup(
                votePhase(TurnOrder.PARALLEL, ContextScope.NONE,
                        new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, List.of("A", "B"), 0.5, Map.of(), false,
                                TiePolicy.MODERATOR_DECIDES)))));
        // A null scope behaves as NONE throughout the scope filter, so it is accepted.
        assertDoesNotThrow(() -> AgentGroupStore.validateVotePhases(voteGroup(votePhase(TurnOrder.PARALLEL, null, null))));
    }

    @Test
    void votePhase_sequential_isRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateVotePhases(voteGroup(
                votePhase(TurnOrder.SEQUENTIAL, ContextScope.NONE, null))));
        assertTrue(ex.getMessage().contains("PARALLEL"), ex.getMessage());
    }

    @Test
    void votePhase_contextfulScope_isRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateVotePhases(voteGroup(
                votePhase(TurnOrder.PARALLEL, ContextScope.FULL, null))));
        assertTrue(ex.getMessage().contains("NONE"), ex.getMessage());
    }

    @Test
    void votePhase_explicitWithTooFewOptions_isRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateVotePhases(voteGroup(
                votePhase(TurnOrder.PARALLEL, ContextScope.NONE,
                        new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, List.of("only one"), 0.5, Map.of(), false,
                                TiePolicy.NO_DECISION)))));
        assertTrue(ex.getMessage().contains("2 options"), ex.getMessage());
    }

    /**
     * The rejection stands, but its stated reason must not: HUMAN members ship (I6
     * — this same class validates them), so blaming their absence tells a config
     * author a shipped feature is missing. What is actually absent is the resume
     * path a paused tie-break would need. Asserting on the alternatives rather than
     * on prose keeps this test from pinning the wording again.
     */
    @Test
    void votePhase_humanDecides_isRejectedPendingResumePath() {
        var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateVotePhases(voteGroup(
                votePhase(TurnOrder.PARALLEL, ContextScope.NONE,
                        new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, List.of("A", "B"), 0.5, Map.of(), false,
                                TiePolicy.HUMAN_DECIDES)))));
        assertTrue(ex.getMessage().contains("HUMAN_DECIDES"), ex.getMessage());
        assertTrue(ex.getMessage().contains("MODERATOR_DECIDES"), ex.getMessage());
        assertTrue(ex.getMessage().contains("NO_DECISION"), ex.getMessage());
        assertFalse(ex.getMessage().contains("not available yet"),
                "must not claim HUMAN members are unavailable — they ship: " + ex.getMessage());
    }

    @Test
    void votePhase_negativeWeight_isRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateVotePhases(voteGroup(
                votePhase(TurnOrder.PARALLEL, ContextScope.NONE,
                        new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, List.of("A", "B"), 0.5,
                                Map.of("a1", -1.0), false, TiePolicy.NO_DECISION)))));
        assertTrue(ex.getMessage().contains("weights"), ex.getMessage());
    }

    @Test
    void votePhase_nonFiniteWeight_isRejected() {
        // NaN passes every < comparison; infinity would decide every vote alone.
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateVotePhases(voteGroup(
                    votePhase(TurnOrder.PARALLEL, ContextScope.NONE,
                            new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, List.of("A", "B"), 0.5,
                                    Map.of("a1", bad), false, TiePolicy.NO_DECISION)))),
                    "weight " + bad + " must be rejected");
            assertTrue(ex.getMessage().contains("finite"), ex.getMessage());
        }
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
    void nonVotePhases_areNeverTouchedByVoteValidation() {
        assertDoesNotThrow(() -> AgentGroupStore.validateVotePhases(config(DiscussionStyle.CUSTOM,
                List.of(phase("Open", "ALL")), null)));
        assertDoesNotThrow(() -> AgentGroupStore.validateVotePhases(config(DiscussionStyle.DEBATE, null, null)));
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

    // =================================================================
    // I12 — facilitator save-time matrix
    // =================================================================

    private AgentGroupConfiguration facilitatorConfig(AgentGroupConfiguration.FacilitatorConfig facilitator) {
        var c = config(DiscussionStyle.CUSTOM, List.of(phase("Open", "ALL")), null);
        c.setFacilitator(facilitator);
        return c;
    }

    @Test
    void facilitator_enabledWithoutAgentId_isRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateFacilitator(
                facilitatorConfig(new AgentGroupConfiguration.FacilitatorConfig(true, "  ", null, null, 0, null))));
        assertTrue(ex.getMessage().contains("agentId"), ex.getMessage());
    }

    @Test
    void facilitator_midPhaseMovesWithPhaseBoundaryCadence_isRejected() {
        // END_PHASE/EXTEND_PHASE act on remaining repeats; an EACH_PHASE checkpoint
        // has none — the config could only ever produce rejected attempts.
        for (var move : new AgentGroupConfiguration.FacilitatorMove[]{
                AgentGroupConfiguration.FacilitatorMove.END_PHASE,
                AgentGroupConfiguration.FacilitatorMove.EXTEND_PHASE}) {
            var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateFacilitator(
                    facilitatorConfig(new AgentGroupConfiguration.FacilitatorConfig(true, "fac", List.of(move),
                            AgentGroupConfiguration.FacilitatorCheckpoint.EACH_PHASE, 10, null))),
                    move.name());
            assertTrue(ex.getMessage().contains("EACH_REPEAT"), ex.getMessage());
        }
        assertDoesNotThrow(() -> AgentGroupStore.validateFacilitator(
                facilitatorConfig(new AgentGroupConfiguration.FacilitatorConfig(true, "fac",
                        List.of(AgentGroupConfiguration.FacilitatorMove.END_PHASE),
                        AgentGroupConfiguration.FacilitatorCheckpoint.EACH_REPEAT, 10, null))));
    }

    @Test
    void facilitator_escalateWithoutPrincipal_isRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateFacilitator(
                facilitatorConfig(new AgentGroupConfiguration.FacilitatorConfig(true, "fac",
                        List.of(AgentGroupConfiguration.FacilitatorMove.ESCALATE_HUMAN),
                        AgentGroupConfiguration.FacilitatorCheckpoint.EACH_PHASE, 10, "  "))));
        assertTrue(ex.getMessage().contains("escalateTo"), ex.getMessage());

        assertDoesNotThrow(() -> AgentGroupStore.validateFacilitator(
                facilitatorConfig(new AgentGroupConfiguration.FacilitatorConfig(true, "fac",
                        List.of(AgentGroupConfiguration.FacilitatorMove.ESCALATE_HUMAN),
                        AgentGroupConfiguration.FacilitatorCheckpoint.EACH_PHASE, 10, "boss@example.com"))));
    }

    @Test
    void facilitator_excessiveMoveBudget_isRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateFacilitator(
                facilitatorConfig(new AgentGroupConfiguration.FacilitatorConfig(true, "fac", null, null, 101, null))));
        assertTrue(ex.getMessage().contains("100"), ex.getMessage());
    }

    @Test
    void facilitator_disabledOrAbsent_isNeverValidated() {
        assertDoesNotThrow(() -> AgentGroupStore.validateFacilitator(facilitatorConfig(null)));
        // Disabled: even a shape that would be rejected when enabled passes —
        // an operator may stage a config before switching it on.
        assertDoesNotThrow(() -> AgentGroupStore.validateFacilitator(
                facilitatorConfig(new AgentGroupConfiguration.FacilitatorConfig(false, null,
                        List.of(AgentGroupConfiguration.FacilitatorMove.ESCALATE_HUMAN),
                        AgentGroupConfiguration.FacilitatorCheckpoint.EACH_PHASE, 10, null))));
    }
}
