/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOpsConfigValidatorTest {

    private static String message(AgentOpsConfig config) {
        return assertThrows(IllegalArgumentException.class, () -> AgentOpsConfigValidator.validate(config, "task")).getMessage();
    }

    @Test
    void absentAndEmptyConfigsAreValidAndReadAsDefaults() {
        assertDoesNotThrow(() -> AgentOpsConfigValidator.validate(null, "task"));
        assertDoesNotThrow(() -> AgentOpsConfigValidator.validate(new AgentOpsConfig(), "task"));
        assertEquals(30, AgentOpsConfig.followUpOf(null).effectiveMaxHorizonDays());
        assertEquals(0.5, AgentOpsConfig.followUpOf(null).effectiveMaxCostPerFireUsd(), "never unlimited by default");
        assertEquals(1, AgentOpsConfig.budgetProposalOf(null).effectiveMaxPerTurn());
        assertEquals(4, AgentOpsConfig.scorecardOf(null).effectiveWeeks());
    }

    @Test
    void theFollowUpHorizonCannotExceedNinetyDays() {
        var config = new AgentOpsConfig();
        var followUp = new AgentOpsConfig.FollowUp();
        followUp.setMaxHorizonDays(90);
        config.setFollowUp(followUp);
        assertDoesNotThrow(() -> AgentOpsConfigValidator.validate(config, "task"));
        followUp.setMaxHorizonDays(91);
        assertTrue(message(config).contains("task.followUp.maxHorizonDays"), message(config));
        followUp.setMaxHorizonDays(0);
        assertTrue(message(config).contains("maxHorizonDays"));
    }

    @Test
    void dollarCeilingsMustBePositiveAndFinite() {
        for (double bad : new double[]{0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            var config = new AgentOpsConfig();
            var followUp = new AgentOpsConfig.FollowUp();
            followUp.setMaxCostPerFireUsd(bad);
            config.setFollowUp(followUp);
            assertTrue(message(config).contains("maxCostPerFireUsd"), "cost " + bad);
            config.setFollowUp(null);
            var proposal = new AgentOpsConfig.BudgetProposal();
            proposal.setMaxAmountUsd(bad);
            config.setBudgetProposal(proposal);
            assertTrue(message(config).contains("budgetProposal.maxAmountUsd"), "amount " + bad);
        }
    }

    @Test
    void countsAreBounded() {
        var config = new AgentOpsConfig();
        var proposal = new AgentOpsConfig.BudgetProposal();
        proposal.setMaxPerDay(0);
        config.setBudgetProposal(proposal);
        assertTrue(message(config).contains("budgetProposal.maxPerDay"));
        proposal.setMaxPerDay(null);
        proposal.setMaxPerTurn(AgentOpsConfig.MAX_PROPOSALS_PER_TURN + 1);
        assertTrue(message(config).contains("budgetProposal.maxPerTurn"));
        config.setBudgetProposal(null);
        var followUp = new AgentOpsConfig.FollowUp();
        followUp.setMaxPending(AgentOpsConfig.MAX_PENDING + 1);
        config.setFollowUp(followUp);
        assertTrue(message(config).contains("followUp.maxPending"));
    }

    @Test
    void scorecardGroupIdsAreCheckedForBlankDuplicateAndCount() {
        var config = new AgentOpsConfig();
        var scorecard = new AgentOpsConfig.Scorecard();
        config.setScorecard(scorecard);
        scorecard.setGroupIds(List.of("a", " "));
        assertTrue(message(config).contains("blank"));
        scorecard.setGroupIds(List.of("a", "a "));
        assertTrue(message(config).contains("twice"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= AgentOpsConfig.MAX_EXTRA_GROUPS; i++) {
            many.add("g" + i);
        }
        scorecard.setGroupIds(many);
        assertTrue(message(config).contains("more than"));
        scorecard.setGroupIds(List.of("a", "b"));
        scorecard.setWeeks(13);
        assertTrue(message(config).contains("scorecard.weeks"));
    }
}
