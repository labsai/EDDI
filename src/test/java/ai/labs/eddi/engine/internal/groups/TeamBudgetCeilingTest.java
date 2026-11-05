/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.TeamBudgetState;
import ai.labs.eddi.engine.groups.budget.TeamBudgetCeilings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The team ceiling as the cost ledger and the nested-remainder computation see
 * it (F6): the tighter of the group's own ceiling and the team's, with the
 * team's policy when it binds.
 */
class TeamBudgetCeilingTest {

    private static ProtocolConfig protocol(Double max, CostPolicy policy) {
        return new ProtocolConfig(60, null, 1, null, 0, max, policy);
    }

    private static GroupConversation gcWithTeam(Double ceiling, CostPolicy policy, double total) {
        GroupConversation gc = new GroupConversation();
        TeamBudgetState state = new TeamBudgetState();
        state.setCeilingUsd(ceiling);
        state.setPolicy(policy);
        state.setMonth("2026-11");
        state.setLimitedBy("TEAM");
        gc.setTeamBudgetState(state);
        gc.setTotalCost(total);
        return gc;
    }

    private static final DiscussionPhase DISCUSS = new DiscussionPhase("Opinions", PhaseType.OPINION);
    private static final DiscussionPhase SYNTH = new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS);

    @Test
    void theTighterOfTheTwoCeilingsWins() {
        var gc = gcWithTeam(3.0, CostPolicy.ABORT, 0);
        var e = TeamBudgetCeilings.effective(gc, protocol(10.0, CostPolicy.SYNTHESIZE_NOW));
        assertEquals(3.0, e.ceiling());
        assertTrue(e.teamBound());
        assertEquals(CostPolicy.ABORT, e.policy(), "the team's policy applies when the team binds");

        var own = TeamBudgetCeilings.effective(gcWithTeam(30.0, CostPolicy.ABORT, 0), protocol(10.0, CostPolicy.SYNTHESIZE_NOW));
        assertEquals(10.0, own.ceiling());
        assertFalse(own.teamBound());
        assertEquals(CostPolicy.SYNTHESIZE_NOW, own.policy());
    }

    @Test
    void aTieGoesToTheGroupsOwnProtocol() {
        var e = TeamBudgetCeilings.effective(gcWithTeam(5.0, CostPolicy.ABORT, 0), protocol(5.0, CostPolicy.SYNTHESIZE_NOW));
        assertFalse(e.teamBound());
        assertEquals(CostPolicy.SYNTHESIZE_NOW, e.policy());
    }

    @Test
    void noGroupCeilingMeansTheTeamCeilingAlone() {
        var e = TeamBudgetCeilings.effective(gcWithTeam(4.0, null, 0), protocol(null, CostPolicy.ABORT));
        assertEquals(4.0, e.ceiling());
        assertEquals(CostPolicy.ABORT, e.policy(), "no team policy resolved: the protocol's");
    }

    @Test
    void noTeamStateOrNoCeilingChangesNothing() {
        assertNull(TeamBudgetCeilings.effective(new GroupConversation(), protocol(null, null)).ceiling());
        assertEquals(7.0, TeamBudgetCeilings.effective(new GroupConversation(), protocol(7.0, null)).ceiling());
        assertEquals(7.0, TeamBudgetCeilings.effective(gcWithTeam(null, null, 0), protocol(7.0, null)).ceiling());
        assertNull(TeamBudgetCeilings.effective(null, null).ceiling());
    }

    @Test
    void enforceStopsAtTheTeamCeilingWithTheTeamMessage() {
        var gc = gcWithTeam(2.0, CostPolicy.SYNTHESIZE_NOW, 2.5);
        assertTrue(GroupCostLedger.enforceCeiling(gc, protocol(null, CostPolicy.ABORT), 0, DISCUSS));
        assertEquals(CostPolicy.SYNTHESIZE_NOW, gc.getCostCeilingOutcome(), "the team's policy, not the protocol's ABORT");
        String text = gc.getTranscript().get(gc.getTranscript().size() - 1).errorReason();
        assertTrue(text.startsWith("Team budget reached (2026-11)"), text);
        assertTrue(text.contains("SYNTHESIZE_NOW"), text);
    }

    @Test
    void theSynthesisExemptionHoldsWhenTheTeamCeilingBinds() {
        var gc = gcWithTeam(2.0, CostPolicy.SYNTHESIZE_NOW, 2.5);
        assertFalse(GroupCostLedger.enforceCeiling(gc, protocol(null, null), 0, SYNTH), "SYNTHESIZE_NOW still delivers its synthesis");
        var abort = gcWithTeam(2.0, CostPolicy.ABORT, 2.5);
        assertTrue(GroupCostLedger.enforceCeiling(abort, protocol(null, null), 0, SYNTH), "ABORT has no exemption");
    }

    @Test
    void spendingExactlyTheCeilingIsStillWithinBudgetButZeroIsFullySpent() {
        assertFalse(GroupCostLedger.enforceCeiling(gcWithTeam(2.0, null, 2.0), protocol(null, null), 0, DISCUSS));
        assertTrue(GroupCostLedger.enforceCeiling(gcWithTeam(0.0, null, 0.0), protocol(null, null), 0, DISCUSS), "a zero remainder stops at once");
    }

    @Test
    void wouldExceedAgreesWithEnforce() {
        for (double total : new double[]{0.0, 1.9, 2.0, 2.1}) {
            for (double ceiling : new double[]{0.0, 2.0}) {
                var gc = gcWithTeam(ceiling, CostPolicy.ABORT, total);
                assertEquals(GroupCostLedger.wouldExceedCeiling(gc, protocol(null, null)),
                        GroupCostLedger.enforceCeiling(gc, protocol(null, null), 0, DISCUSS), "total " + total + " ceiling " + ceiling);
            }
        }
    }

    @Test
    void theGroupsOwnCeilingMessageIsUnchangedWithoutATeam() {
        var gc = new GroupConversation();
        gc.setTotalCost(3.0);
        assertTrue(GroupCostLedger.enforceCeiling(gc, protocol(2.0, CostPolicy.ABORT), 0, DISCUSS));
        assertTrue(gc.getTranscript().get(gc.getTranscript().size() - 1).errorReason().startsWith("Cost ceiling reached: $3.00 of $2.00"));
    }
}
