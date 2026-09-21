/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.lint;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReservedActionLint} — non-fatal save-time WARNs for (1)
 * action names that near-miss a reserved action name in a ruleset, and (2) a
 * deployed agent whose hitlConfig can never actually trigger a pause.
 */
@DisplayName("ReservedActionLint")
class ReservedActionLintTest {

    // =====================================================================
    // 1. Near-miss lint (rules save path)
    // =====================================================================

    @Nested
    @DisplayName("checkReservedActionNearMisses")
    class NearMissLint {

        @Test
        @DisplayName("exact match to a reserved action is NOT flagged")
        void exactMatchNotFlagged() {
            var ruleSet = ruleSetWithActions("PAUSE_CONVERSATION");

            List<String> warnings = ReservedActionLint.checkReservedActionNearMisses(ruleSet);

            assertTrue(warnings.isEmpty(), "exact match must never be flagged: " + warnings);
        }

        @Test
        @DisplayName("all four exact reserved actions are NOT flagged")
        void allReservedExactMatchesNotFlagged() {
            var ruleSet = ruleSetWithActions(
                    "PAUSE_CONVERSATION", "STOP_CONVERSATION", "CONVERSATION_START", "CONVERSATION_END");

            List<String> warnings = ReservedActionLint.checkReservedActionNearMisses(ruleSet);

            assertTrue(warnings.isEmpty(), "exact matches must never be flagged: " + warnings);
        }

        @Test
        @DisplayName("typo within Levenshtein distance 2 is flagged")
        void typoIsFlagged() {
            var ruleSet = ruleSetWithActions("PAUSE_CONVERSATON");

            List<String> warnings = ReservedActionLint.checkReservedActionNearMisses(ruleSet);

            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("PAUSE_CONVERSATON"));
            assertTrue(warnings.get(0).contains("PAUSE_CONVERSATION"));
        }

        @Test
        @DisplayName("case-variant is flagged")
        void caseVariantIsFlagged() {
            var ruleSet = ruleSetWithActions("pause_conversation");

            List<String> warnings = ReservedActionLint.checkReservedActionNearMisses(ruleSet);

            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("pause_conversation"));
        }

        @Test
        @DisplayName("unrelated action name is NOT flagged")
        void unrelatedNameNotFlagged() {
            var ruleSet = ruleSetWithActions("greet_user");

            List<String> warnings = ReservedActionLint.checkReservedActionNearMisses(ruleSet);

            assertTrue(warnings.isEmpty(), "unrelated action must not be flagged: " + warnings);
        }

        @Test
        @DisplayName("distance-3 name is NOT flagged (outside the <=2 threshold)")
        void distanceThreeNotFlagged() {
            // "PAUSE_CONVERSATION" -> "PAUSE_CONVERZATOM" is edit distance 3 (Z, dropped
            // trailing character replaced twice) — chosen to safely exceed the threshold.
            var ruleSet = ruleSetWithActions("PAUSE_CONVERZATOM");

            List<String> warnings = ReservedActionLint.checkReservedActionNearMisses(ruleSet);

            assertTrue(warnings.isEmpty(), "distance > 2 must not be flagged: " + warnings);
        }

        @Test
        @DisplayName("multiple near-miss actions across groups all get flagged")
        void multipleNearMissesAcrossGroups() {
            var ruleSet = new RuleSetConfiguration();
            var group1 = ruleGroup(rule("r1", "PAUSE_CONVERSATON"));
            var group2 = ruleGroup(rule("r2", "STOP_CONVERSATIONS"));
            ruleSet.setBehaviorGroups(List.of(group1, group2));

            List<String> warnings = ReservedActionLint.checkReservedActionNearMisses(ruleSet);

            assertEquals(2, warnings.size());
        }

        @Test
        @DisplayName("same near-miss action repeated across rules is only flagged once")
        void duplicateNearMissFlaggedOnce() {
            var ruleSet = new RuleSetConfiguration();
            var group = ruleGroup(rule("r1", "PAUSE_CONVERSATON"), rule("r2", "PAUSE_CONVERSATON"));
            ruleSet.setBehaviorGroups(List.of(group));

            List<String> warnings = ReservedActionLint.checkReservedActionNearMisses(ruleSet);

            assertEquals(1, warnings.size());
        }

        @Test
        @DisplayName("null ruleset is a no-op")
        void nullRuleSetNoOp() {
            assertTrue(ReservedActionLint.checkReservedActionNearMisses(null).isEmpty());
        }

        @Test
        @DisplayName("empty behaviorGroups is a no-op")
        void emptyBehaviorGroupsNoOp() {
            var ruleSet = new RuleSetConfiguration();
            ruleSet.setBehaviorGroups(List.of());

            assertTrue(ReservedActionLint.checkReservedActionNearMisses(ruleSet).isEmpty());
        }

        private RuleSetConfiguration ruleSetWithActions(String... actions) {
            var ruleSet = new RuleSetConfiguration();
            ruleSet.setBehaviorGroups(List.of(ruleGroup(rule("r1", actions))));
            return ruleSet;
        }

        private RuleGroupConfiguration ruleGroup(RuleConfiguration... rules) {
            var group = new RuleGroupConfiguration();
            group.setRules(List.of(rules));
            return group;
        }

        private RuleConfiguration rule(String name, String... actions) {
            var rule = new RuleConfiguration();
            rule.setName(name);
            rule.setActions(List.of(actions));
            return rule;
        }
    }

    // =====================================================================
    // 2. Inert hitlConfig lint (deployment path)
    // =====================================================================

    @Nested
    @DisplayName("checkInertHitlConfig")
    class InertHitlConfigLint {

        @Test
        @DisplayName("absent hitlConfig never warns")
        void absentHitlConfigNoWarning() {
            Optional<String> warning = ReservedActionLint.checkInertHitlConfig("agent1", null, false);

            assertTrue(warning.isEmpty());
        }

        @Test
        @DisplayName("hitlConfig set, no ruleset emits PAUSE_CONVERSATION, no requireApproval -> warns")
        void configuredButInertWarns() {
            var hitlConfig = new AgentConfiguration.HitlConfig();

            Optional<String> warning = ReservedActionLint.checkInertHitlConfig("agent1", hitlConfig, false);

            assertTrue(warning.isPresent());
            assertTrue(warning.get().contains("agent1"));
            assertTrue(warning.get().contains("hitlConfig is configured but nothing in this agent can trigger a pause"));
        }

        @Test
        @DisplayName("hitlConfig set AND a ruleset emits PAUSE_CONVERSATION -> no warning")
        void doesNotWarnWhenRulesetEmitsPause() {
            var hitlConfig = new AgentConfiguration.HitlConfig();

            Optional<String> warning = ReservedActionLint.checkInertHitlConfig("agent1", hitlConfig, true);

            assertTrue(warning.isEmpty());
        }

        @Test
        @DisplayName("hitlConfig set AND requireApproval is non-empty -> no warning")
        void doesNotWarnWhenRequireApprovalSet() {
            var hitlConfig = new AgentConfiguration.HitlConfig();
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setRequireApproval(List.of("mcp:*"));
            hitlConfig.setToolApprovals(toolApprovals);

            Optional<String> warning = ReservedActionLint.checkInertHitlConfig("agent1", hitlConfig, false);

            assertTrue(warning.isEmpty());
        }

        @Test
        @DisplayName("hitlConfig set with toolApprovals present but requireApproval empty list -> still warns")
        void warnsWhenRequireApprovalIsEmptyList() {
            var hitlConfig = new AgentConfiguration.HitlConfig();
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setRequireApproval(List.of());
            hitlConfig.setToolApprovals(toolApprovals);

            Optional<String> warning = ReservedActionLint.checkInertHitlConfig("agent1", hitlConfig, false);

            assertTrue(warning.isPresent());
        }

        @Test
        @DisplayName("hitlConfig set with null toolApprovals block -> still warns (no requireApproval possible)")
        void warnsWhenToolApprovalsAbsent() {
            var hitlConfig = new AgentConfiguration.HitlConfig();
            hitlConfig.setToolApprovals(null);

            Optional<String> warning = ReservedActionLint.checkInertHitlConfig("agent1", hitlConfig, false);

            assertTrue(warning.isPresent());
        }
    }
}
