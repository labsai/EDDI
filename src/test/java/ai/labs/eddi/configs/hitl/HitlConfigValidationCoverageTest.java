/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.engine.hitl.lint.ReservedActionLint;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional branch-coverage tests for the pure HITL validation/lint logic:
 * {@link HitlConfigValidation}, {@link ReservedActionLint} and
 * {@link ToolApprovalPatterns}. These target uncovered branches (guards, catch
 * blocks, boolean conditionals, early returns) not already exercised by the
 * existing tests. Strictly additive — mirrors the existing test patterns.
 */
@DisplayName("HitlConfigValidation coverage (branches)")
class HitlConfigValidationCoverageTest {

    // =====================================================================
    // HitlConfigValidation.validate(AgentConfiguration.HitlConfig)
    // =====================================================================

    @Nested
    @DisplayName("agent-level validate() branches")
    class AgentLevelBranches {

        @Test
        @DisplayName("null timeoutPolicy => finitePolicy=false, null timeout is a no-op")
        void nullTimeoutPolicyNoTimeoutValid() {
            var config = new AgentConfiguration.HitlConfig();
            // timeoutPolicy left null; getTimeoutPolicy() != null is false -> finitePolicy
            // false
            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("pauseReason exactly at MAX length is accepted")
        void pauseReasonAtBoundaryAccepted() {
            var config = new AgentConfiguration.HitlConfig();
            config.setPauseReason("x".repeat(HitlConfigValidation.MAX_PAUSE_REASON_LENGTH));
            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("pauseReason over MAX length is rejected with an actionable message")
        void pauseReasonOverMaxRejected() {
            var config = new AgentConfiguration.HitlConfig();
            config.setPauseReason("x".repeat(HitlConfigValidation.MAX_PAUSE_REASON_LENGTH + 1));

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> HitlConfigValidation.validate(config));
            assertTrue(ex.getMessage().contains("pauseReason"), ex.getMessage());
            assertTrue(ex.getMessage().contains("maximum length"), ex.getMessage());
        }

        @Test
        @DisplayName("null pauseReason skips the length check")
        void nullPauseReasonNoOp() {
            var config = new AgentConfiguration.HitlConfig();
            config.setPauseReason(null);
            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("blank approvalTimeout with WAIT_INDEFINITELY policy is a no-op")
        void blankTimeoutWithoutFinitePolicyNoOp() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            config.setApprovalTimeout("   "); // blank -> isBlank() true, finitePolicy false -> return
            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }
    }

    // =====================================================================
    // warnIfToolApprovalsInheritsAutoApprove (private; exercised via validate)
    // Each of these must NOT throw — the WARN path is a log-only branch.
    // =====================================================================

    @Nested
    @DisplayName("warnIfToolApprovalsInheritsAutoApprove branches")
    class AutoApproveInheritanceWarning {

        @Test
        @DisplayName("agent AUTO_APPROVE + toolApprovals without its own timeoutPolicy => WARN branch (no throw)")
        void warnBranchTaken() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            config.setApprovalTimeout("PT30M"); // required for finite policy
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setRequireApproval(List.of("mcp:*"));
            // toolApprovals.timeoutPolicy left null -> inherits, triggers warn
            config.setToolApprovals(toolApprovals);

            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("agent AUTO_APPROVE but toolApprovals sets its own timeoutPolicy => no warn (no throw)")
        void noWarnWhenToolApprovalsHasOwnPolicy() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            config.setApprovalTimeout("PT30M");
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setRequireApproval(List.of("mcp:*"));
            toolApprovals.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY); // own policy -> not null
            config.setToolApprovals(toolApprovals);

            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("null toolApprovals => warn guard short-circuits (no throw)")
        void noWarnWhenToolApprovalsNull() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            config.setApprovalTimeout("PT30M");
            config.setToolApprovals(null);

            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("agent policy not AUTO_APPROVE + inheriting toolApprovals => no warn (no throw)")
        void noWarnWhenAgentPolicyNotAutoApprove() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY); // not AUTO_APPROVE
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setRequireApproval(List.of("mcp:*"));
            config.setToolApprovals(toolApprovals);

            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }
    }

    // =====================================================================
    // validateToolApprovals extra branches
    // =====================================================================

    @Nested
    @DisplayName("validateToolApprovals extra branches")
    class ToolApprovalsBranches {

        @Test
        @DisplayName("null requireApproval + null exempt => both empty, no throw")
        void bothListsNullNoOp() {
            var c = new ToolApprovalsConfig();
            // both lists null -> validatePatternList returns empty set for each
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("exempt empty AND require non-empty => passes the no-effect guard")
        void exemptEmptyRequireNonEmptyOk() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setExempt(List.of()); // empty exempt, require non-empty -> guard not triggered
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("null maxPausesPerTurn skips the range check")
        void nullMaxPausesPerTurnNoOp() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            // maxPausesPerTurn left null -> validateRange short-circuits on null
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("maxPausesPerTurn at lower bound (1) is accepted")
        void maxPausesLowerBoundAccepted() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setMaxPausesPerTurn(1);
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("maxPausesPerTurn at upper bound (10) is accepted")
        void maxPausesUpperBoundAccepted() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setMaxPausesPerTurn(10);
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("maxAutoApprovalsPerTurn at lower bound (0) is accepted")
        void maxAutoApprovalsLowerBoundAccepted() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setMaxAutoApprovalsPerTurn(0);
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("maxAutoApprovalsPerTurn below range (-1) is rejected")
        void maxAutoApprovalsBelowRangeRejected() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setMaxAutoApprovalsPerTurn(-1);
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> HitlConfigValidation.validateToolApprovals(c, "x"));
            assertTrue(ex.getMessage().contains("maxAutoApprovalsPerTurn"), ex.getMessage());
        }

        @Test
        @DisplayName("null onNoProgress skips the enum check")
        void nullOnNoProgressNoOp() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            // onNoProgress null -> skipped
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("onNoProgress AUTO_REJECT (valid enum arm) is accepted")
        void onNoProgressAutoRejectAccepted() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setOnNoProgress("AUTO_REJECT");
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("onNoProgress ABORT (valid enum arm) is accepted")
        void onNoProgressAbortAccepted() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setOnNoProgress("ABORT");
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("null inGroupTurns skips both branches")
        void nullInGroupTurnsNoOp() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            // inGroupTurns null -> whole block skipped
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("null timeoutPolicy => tool-pause timeout treated as non-finite (no throw on null timeout)")
        void nullTimeoutPolicyNoOp() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            // timeoutPolicy null -> finitePolicy false; null approvalTimeout -> returns
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("finite tool timeoutPolicy with valid duration is accepted")
        void finiteToolTimeoutValidAccepted() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            c.setApprovalTimeout("PT15M");
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("finite tool timeoutPolicy without approvalTimeout is rejected")
        void finiteToolTimeoutMissingRejected() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            // approvalTimeout null -> finite policy requires it
            assertThrows(IllegalArgumentException.class,
                    () -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("null pauseReason / pendingMessage skip the length checks")
        void nullReasonAndPendingNoOp() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setPauseReason(null);
            c.setPendingMessage(null);
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }

        @Test
        @DisplayName("pauseReason at MAX length is accepted (boundary)")
        void pauseReasonBoundaryAccepted() {
            var c = new ToolApprovalsConfig();
            c.setRequireApproval(List.of("mcp:*"));
            c.setPauseReason("x".repeat(HitlConfigValidation.MAX_PAUSE_REASON_LENGTH));
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "x"));
        }
    }

    // =====================================================================
    // Group-level validate branches
    // =====================================================================

    @Nested
    @DisplayName("group-level validate() branches")
    class GroupLevelBranches {

        @Test
        @DisplayName("null timeoutPolicy => finitePolicy false, no timeout no-op")
        void nullTimeoutPolicyNoOp() {
            var config = new AgentGroupConfiguration.HitlConfig();
            // timeoutPolicy null
            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("garbage duration with WAIT_INDEFINITELY is still format-checked and rejected")
        void garbageDurationRejectedGroup() {
            var config = new AgentGroupConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            config.setApprovalTimeout("not-a-duration");
            assertThrows(IllegalArgumentException.class,
                    () -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("zero duration is rejected at group level")
        void zeroDurationRejectedGroup() {
            var config = new AgentGroupConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            config.setApprovalTimeout("PT0S");
            assertThrows(IllegalArgumentException.class,
                    () -> HitlConfigValidation.validate(config));
        }
    }

    // =====================================================================
    // ToolApprovalPatterns branches
    // =====================================================================

    @Nested
    @DisplayName("ToolApprovalPatterns branches")
    class PatternBranches {

        @Test
        @DisplayName("null pattern is rejected as blank")
        void nullPatternRejected() {
            var err = ToolApprovalPatterns.validate(null);
            assertTrue(err.isPresent());
            assertTrue(err.get().contains("blank"), err.get());
        }

        @Test
        @DisplayName("blank (whitespace) pattern is rejected")
        void whitespacePatternRejected() {
            var err = ToolApprovalPatterns.validate("   ");
            assertTrue(err.isPresent());
            assertTrue(err.get().contains("blank"), err.get());
        }

        @Test
        @DisplayName("pattern at MAX length (256) is accepted")
        void maxLengthAccepted() {
            var err = ToolApprovalPatterns.validate("a".repeat(256));
            assertTrue(err.isEmpty(), () -> "256 chars should pass: " + err);
        }

        @Test
        @DisplayName("leading colon is rejected")
        void leadingColonRejected() {
            var err = ToolApprovalPatterns.validate(":tool");
            assertTrue(err.isPresent());
            assertTrue(err.get().contains("colon"), err.get());
        }

        @Test
        @DisplayName("trailing colon is rejected")
        void trailingColonRejected() {
            var err = ToolApprovalPatterns.validate("mcp:");
            assertTrue(err.isPresent());
            assertTrue(err.get().contains("colon"), err.get());
        }

        @Test
        @DisplayName("wildcard source prefix (colon>0, prefix contains '*') is accepted without source check")
        void wildcardPrefixAccepted() {
            // prefix "m*" contains '*' -> KNOWN_SOURCES check is bypassed
            var err = ToolApprovalPatterns.validate("m*:read_tool");
            assertTrue(err.isEmpty(), () -> "wildcard prefix should bypass source check: " + err);
        }

        @Test
        @DisplayName("unknown prefix far from any known source => no suggestion appended, still rejected")
        void unknownPrefixNoSuggestion() {
            // "zzzzzz" is > distance 2 from every known source -> suggestionFor returns ""
            var err = ToolApprovalPatterns.validate("zzzzzz:tool");
            assertTrue(err.isPresent());
            assertTrue(err.get().contains("unknown tool source prefix"), err.get());
            assertFalse(err.get().contains("did you mean"),
                    "no close source should mean no suggestion: " + err.get());
        }

        @Test
        @DisplayName("known source prefix (e.g. builtin) is accepted")
        void knownPrefixAccepted() {
            assertTrue(ToolApprovalPatterns.validate("builtin:do_thing").isEmpty());
            assertTrue(ToolApprovalPatterns.validate("recall:*").isEmpty());
        }

        @Test
        @DisplayName("bare tool name without a colon skips the prefix logic")
        void bareToolNameAccepted() {
            // indexOf(':') == -1 -> colon > 0 is false -> prefix block skipped
            assertTrue(ToolApprovalPatterns.validate("delete_account").isEmpty());
        }

        @Test
        @DisplayName("compile with no wildcard matches only the exact literal")
        void compileNoWildcard() {
            var p = ToolApprovalPatterns.compile("exact_tool");
            assertTrue(p.matcher("exact_tool").matches());
            assertFalse(p.matcher("exact_tool_x").matches());
        }

        @Test
        @DisplayName("compile with leading/trailing wildcards produces empty-part segments")
        void compileLeadingTrailingWildcard() {
            var p = ToolApprovalPatterns.compile("*mid*");
            assertTrue(p.matcher("xmidy").matches());
            assertTrue(p.matcher("mid").matches());
            assertFalse(p.matcher("mad").matches());
        }

        @Test
        @DisplayName("levenshtein of identical strings is 0, empty vs non-empty is length")
        void levenshteinEdgeCases() {
            assertEquals(0, ToolApprovalPatterns.levenshtein("abc", "abc"));
            assertEquals(3, ToolApprovalPatterns.levenshtein("", "abc"));
            assertEquals(3, ToolApprovalPatterns.levenshtein("abc", ""));
            assertEquals(1, ToolApprovalPatterns.levenshtein("abc", "abx"));
        }
    }

    // =====================================================================
    // ReservedActionLint near-miss traversal branches (null-guard paths)
    // =====================================================================

    @Nested
    @DisplayName("ReservedActionLint traversal null-guard branches")
    class LintTraversalBranches {

        @Test
        @DisplayName("null behaviorGroups is a no-op")
        void nullBehaviorGroupsNoOp() {
            var ruleSet = new RuleSetConfiguration();
            ruleSet.setBehaviorGroups(null);
            assertTrue(ReservedActionLint.checkReservedActionNearMisses(ruleSet).isEmpty());
        }

        @Test
        @DisplayName("null group in the list is skipped")
        void nullGroupSkipped() {
            var ruleSet = new RuleSetConfiguration();
            var groups = new ArrayList<RuleGroupConfiguration>();
            groups.add(null);
            ruleSet.setBehaviorGroups(groups);
            assertTrue(ReservedActionLint.checkReservedActionNearMisses(ruleSet).isEmpty());
        }

        @Test
        @DisplayName("group with null rules is skipped")
        void groupWithNullRulesSkipped() {
            var ruleSet = new RuleSetConfiguration();
            var group = new RuleGroupConfiguration();
            group.setRules(null);
            ruleSet.setBehaviorGroups(List.of(group));
            assertTrue(ReservedActionLint.checkReservedActionNearMisses(ruleSet).isEmpty());
        }

        @Test
        @DisplayName("null rule in the list is skipped")
        void nullRuleSkipped() {
            var ruleSet = new RuleSetConfiguration();
            var group = new RuleGroupConfiguration();
            var rules = new ArrayList<RuleConfiguration>();
            rules.add(null);
            group.setRules(rules);
            ruleSet.setBehaviorGroups(List.of(group));
            assertTrue(ReservedActionLint.checkReservedActionNearMisses(ruleSet).isEmpty());
        }

        @Test
        @DisplayName("rule with null actions is skipped")
        void ruleWithNullActionsSkipped() {
            var ruleSet = new RuleSetConfiguration();
            var group = new RuleGroupConfiguration();
            var rule = new RuleConfiguration();
            rule.setName("r1");
            rule.setActions(null);
            group.setRules(List.of(rule));
            ruleSet.setBehaviorGroups(List.of(group));
            assertTrue(ReservedActionLint.checkReservedActionNearMisses(ruleSet).isEmpty());
        }

        @Test
        @DisplayName("null action element is skipped; a real near-miss beside it is still flagged")
        void nullActionElementSkipped() {
            var ruleSet = new RuleSetConfiguration();
            var group = new RuleGroupConfiguration();
            var rule = new RuleConfiguration();
            rule.setName("r1");
            rule.setActions(Arrays.asList(null, "PAUSE_CONVERSATON"));
            group.setRules(List.of(rule));
            ruleSet.setBehaviorGroups(List.of(group));

            List<String> warnings = ReservedActionLint.checkReservedActionNearMisses(ruleSet);
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("PAUSE_CONVERSATON"), warnings.toString());
        }

        @Test
        @DisplayName("case-variant near-miss (short-circuits before distance) is flagged")
        void caseVariantShortCircuitFlagged() {
            var ruleSet = new RuleSetConfiguration();
            var group = new RuleGroupConfiguration();
            var rule = new RuleConfiguration();
            rule.setName("r1");
            // equalsIgnoreCase true but not exact -> caseVariant branch true
            rule.setActions(List.of("Stop_Conversation"));
            group.setRules(List.of(rule));
            ruleSet.setBehaviorGroups(List.of(group));

            List<String> warnings = ReservedActionLint.checkReservedActionNearMisses(ruleSet);
            assertEquals(1, warnings.size());
        }
    }
}
