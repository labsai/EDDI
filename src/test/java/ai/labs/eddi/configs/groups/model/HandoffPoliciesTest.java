/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptPolicy;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.DecisionRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.GroupHandoffConfig;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.HandoffTarget;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.IntakeMode;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.TeamHandoffConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandoffPoliciesTest {

    @Test
    void emptyConfigGetsDefaults() {
        var cfg = new GroupHandoffConfig(null, null, 0, 0, 0, null, null);
        assertTrue(cfg.targets().isEmpty());
        assertEquals("MODERATOR", cfg.toolParticipants());
        assertEquals(HandoffPolicies.DEFAULT_MAX_PER_TURN, cfg.maxPerTurn());
        assertEquals(HandoffPolicies.DEFAULT_MAX_PER_DISCUSSION, cfg.maxPerDiscussion());
        assertEquals(HandoffPolicies.DEFAULT_MAX_DESCRIPTION_CHARS, cfg.maxDescriptionChars());
        assertTrue(cfg.onDecision().isEmpty());
        assertFalse(cfg.sends());
    }

    @Test
    void valuesAboveTheHardCeilingAreClamped() {
        var cfg = new GroupHandoffConfig(null, "ALL", 999, 999, 99_999, null, null);
        assertEquals(HandoffPolicies.HARD_MAX_PER_TURN, cfg.maxPerTurn());
        assertEquals(HandoffPolicies.HARD_MAX_PER_RUN, cfg.maxPerDiscussion());
        assertEquals(HandoffPolicies.HARD_MAX_DESCRIPTION_CHARS, cfg.maxDescriptionChars());
        var task = new TeamHandoffConfig(null, -3, 500, 5000);
        assertEquals(HandoffPolicies.DEFAULT_MAX_PER_TURN, task.maxPerTurn(), "a negative value is unset, never unlimited");
        assertEquals(HandoffPolicies.HARD_MAX_PER_RUN, task.maxPerConversation());
        assertEquals(HandoffPolicies.HARD_MAX_DESCRIPTION_CHARS, task.maxDescriptionChars());
    }

    @Test
    void acceptPolicyDefaultsAndClamps() {
        var accept = new AcceptPolicy(null, 0, 0);
        assertTrue(accept.from().isEmpty());
        assertEquals(HandoffPolicies.DEFAULT_ACCEPT_MAX_PER_DAY, accept.maxPerDay());
        assertEquals(HandoffPolicies.DEFAULT_MAX_INBOX, accept.maxInbox());
        var big = new AcceptPolicy(null, 10_000, 10_000);
        assertEquals(HandoffPolicies.HARD_MAX_PER_DAY, big.maxPerDay());
        assertEquals(HandoffPolicies.HARD_MAX_INBOX, big.maxInbox());
    }

    @Test
    void acceptRuleDefaultsToReviewAndClampsDailyCap() {
        var rule = new AcceptRule(" group:abc ", null, 100_000);
        assertEquals("group:abc", rule.source());
        assertEquals(IntakeMode.REVIEW, rule.mode());
        assertEquals(HandoffPolicies.HARD_MAX_PER_DAY, rule.maxPerDay());
        assertNull(new AcceptRule("group:abc", IntakeMode.AUTO, null).maxPerDay());
    }

    @Test
    void ruleForIsAnExactMatch() {
        var accept = new AcceptPolicy(List.of(new AcceptRule("group:a", IntakeMode.AUTO, null)), 0, 0);
        assertNotNull(accept.ruleFor("group:a"));
        assertNull(accept.ruleFor("group:A"));
        assertNull(accept.ruleFor("agent:a"));
        assertNull(accept.ruleFor(null));
    }

    @Test
    void targetPriorityDefaultsAndClamps() {
        assertEquals(5, new HandoffTarget("a", "g", null, null).defaultPriority());
        assertEquals(10, new HandoffTarget("a", "g", null, 99).defaultPriority());
        assertEquals(0, new HandoffTarget("a", "g", null, -4).defaultPriority());
    }

    @Test
    void decisionRuleRequiresAWinnerByDefault() {
        var rule = new DecisionRule("t", null, null, "s", null, null);
        assertTrue(rule.requireWinner());
        assertTrue(rule.decisionTypes().isEmpty());
        assertNull(rule.priority());
        assertEquals(10, new DecisionRule("t", null, false, "s", null, 77).priority());
    }

    @Test
    void aliasGrammar() {
        assertTrue(HandoffPolicies.ALIAS.matcher("account-desk").matches());
        assertFalse(HandoffPolicies.ALIAS.matcher("Account").matches());
        assertFalse(HandoffPolicies.ALIAS.matcher("").matches());
        assertFalse(HandoffPolicies.ALIAS.matcher("a".repeat(41)).matches());
    }

    @Test
    void jacksonRoundTripAppliesDefaultsToPartialJson() throws Exception {
        var mapper = new ObjectMapper();
        var cfg = mapper.readValue("""
                {"targets":[{"alias":"desk","groupId":"g1"}],
                 "accept":{"from":[{"source":"agent:x"}]}}""", GroupHandoffConfig.class);
        assertEquals(5, cfg.targets().get(0).defaultPriority());
        assertEquals(IntakeMode.REVIEW, cfg.accept().from().get(0).mode());
        assertEquals("MODERATOR", cfg.toolParticipants());
        var again = mapper.readValue(mapper.writeValueAsString(cfg), GroupHandoffConfig.class);
        assertEquals(cfg, again);
    }
}
