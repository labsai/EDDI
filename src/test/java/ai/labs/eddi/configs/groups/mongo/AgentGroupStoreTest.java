/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
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

    @Test
    void votePhase_humanDecides_isRejectedUntilI6() {
        var ex = assertThrows(IllegalArgumentException.class, () -> AgentGroupStore.validateVotePhases(voteGroup(
                votePhase(TurnOrder.PARALLEL, ContextScope.NONE,
                        new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, List.of("A", "B"), 0.5, Map.of(), false,
                                TiePolicy.HUMAN_DECIDES)))));
        assertTrue(ex.getMessage().contains("I6"), ex.getMessage());
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

    @Test
    void nonVotePhases_areNeverTouchedByVoteValidation() {
        assertDoesNotThrow(() -> AgentGroupStore.validateVotePhases(config(DiscussionStyle.CUSTOM,
                List.of(phase("Open", "ALL")), null)));
        assertDoesNotThrow(() -> AgentGroupStore.validateVotePhases(config(DiscussionStyle.DEBATE, null, null)));
    }
}
