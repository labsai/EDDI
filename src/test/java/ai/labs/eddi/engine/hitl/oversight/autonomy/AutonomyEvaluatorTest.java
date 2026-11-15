/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.hitl.model.AutonomyPolicyConfig;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision.DeciderKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.AGENT;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.CATEGORY;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decision;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decisions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AutonomyEvaluator")
class AutonomyEvaluatorTest {

    private static AutonomyPolicyConfig.Resolved policy() {
        var category = new AutonomyPolicyConfig.CategoryPolicy();
        category.setDemoteOnReasonCodes(List.of("FALSE_CLAIM"));
        var config = new AutonomyPolicyConfig();
        config.setCategories(Map.of(CATEGORY, category));
        return config.resolve(CATEGORY);
    }

    private static AutonomyEvaluator.Result evaluate(List<ApprovalDecision> rows, AutonomyState state) {
        return AutonomyEvaluator.evaluate(policy(), rows, state, NOW);
    }

    @Test
    @DisplayName("exactly promoteAfter (30) unedited approvals reach the threshold; 29 do not")
    void promoteAfterBoundary() {
        assertTrue(evaluate(decisions(30, 0, "APPROVED"), null).eligible());
        AutonomyEvaluator.Result short1 = evaluate(decisions(29, 0, "APPROVED"), null);
        assertFalse(short1.eligible());
        assertEquals(29, short1.evidence().n());
    }

    @Test
    @DisplayName("minUneditedRate 0.9: 27 of 30 promotes, 26 of 30 does not")
    void rateBoundary() {
        assertTrue(evaluate(decisions(27, 3, "REJECTED"), null).eligible());
        AutonomyEvaluator.Result result = evaluate(decisions(26, 4, "REJECTED"), null);
        assertFalse(result.eligible());
        assertTrue(result.reason().contains("unedited approval rate"), result.reason());
    }

    @Test
    @DisplayName("edited approvals count as decisions but lower the unedited rate")
    void editsLowerTheRate() {
        AutonomyEvaluator.Result result = evaluate(decisions(26, 4, "APPROVED_EDITED"), null);
        assertFalse(result.eligible());
        assertEquals(30, result.evidence().n());
        assertEquals(4, result.evidence().edited());
    }

    @Test
    @DisplayName("system, unverified, MCP, timeout-channel and legacy decisions are never evidence")
    void excludedDeciders() {
        List<ApprovalDecision> rows = new ArrayList<>(decisions(29, 0, "APPROVED"));
        rows.add(decision("APPROVED", NOW.minusSeconds(10), "system:timeout", DeciderKind.SYSTEM, "TIMEOUT", null));
        rows.add(decision("APPROVED", NOW.minusSeconds(11), "unknown", DeciderKind.UNVERIFIED, "REST", null));
        rows.add(decision("APPROVED", NOW.minusSeconds(12), "mcp:alice", DeciderKind.HUMAN_VERIFIED, "MCP", null));
        rows.add(decision("AUTO_APPROVED", NOW.minusSeconds(13), "alice", DeciderKind.HUMAN_VERIFIED, "MANAGER", null));
        ApprovalDecision legacy = decision("APPROVED", NOW.minusSeconds(14));
        rows.add(new ApprovalDecision(legacy.id() + "L", legacy.decisionId(), 0, legacy.surface(), legacy.pauseType(), legacy.unitKind(),
                legacy.unitId(), AGENT, 3, null, null, null, null, CATEGORY, null, "APPROVED", null, null, null, "alice",
                DeciderKind.HUMAN_VERIFIED, "MANAGER", null, legacy.decidedAt(), null, false, null, true));

        AutonomyEvaluator.Result result = evaluate(rows, null);

        assertEquals(29, result.evidence().n(), "only the 29 verified Manager approvals count");
        assertFalse(result.eligible());
    }

    @Test
    @DisplayName("a decision on a verified channel by a human counts (Slack)")
    void slackCounts() {
        List<ApprovalDecision> rows = new ArrayList<>(decisions(29, 0, "APPROVED"));
        rows.add(decision("APPROVED", NOW.minusSeconds(10), "slack:U1", DeciderKind.HUMAN_VERIFIED, "SLACK", null));
        assertTrue(evaluate(rows, null).eligible());
    }

    @Test
    @DisplayName("evidence before evidenceResetAt does not count")
    void evidenceReset() {
        var state = AutonomyState.initial(AGENT, CATEGORY).withEvidenceResetAt(NOW.minusSeconds(3600L + 15 * 60L));

        AutonomyEvaluator.Result result = evaluate(decisions(30, 0, "APPROVED"), state);

        assertEquals(16, result.evidence().n(), "the window starts at the reset");
        assertFalse(result.eligible());
    }

    @Test
    @DisplayName("evidence within the cooldown after a demotion does not count")
    void cooldown() {
        var state = AutonomyState.initial(AGENT, CATEGORY).withLastDemotionAt(NOW.minus(Duration.ofDays(14)).minusSeconds(3600L + 9 * 60L));

        AutonomyEvaluator.Result result = evaluate(decisions(30, 0, "APPROVED"), state);

        assertEquals(10, result.evidence().n());
    }

    @Test
    @DisplayName("evidence older than the window does not count")
    void window() {
        List<ApprovalDecision> rows = new ArrayList<>(decisions(29, 0, "APPROVED"));
        rows.add(decision("APPROVED", NOW.minus(Duration.ofDays(31))));
        assertFalse(evaluate(rows, null).eligible());
    }

    @Test
    @DisplayName("a rejection carrying a demote reason code blocks promotion")
    void demoteCodeBlocks() {
        List<ApprovalDecision> rows = new ArrayList<>(decisions(40, 0, "APPROVED"));
        rows.add(decision("REJECTED", NOW.minusSeconds(5), "alice", DeciderKind.HUMAN_VERIFIED, "MANAGER", "FALSE_CLAIM"));

        AutonomyEvaluator.Result result = evaluate(rows, null);

        assertFalse(result.eligible());
        assertTrue(result.reason().contains("demote reason code"), result.reason());
    }

    @Test
    @DisplayName("after a dismissal, no recommendation until the evidence count reaches suppressUntilCount")
    void suppressedAfterDismissal() {
        var state = AutonomyState.initial(AGENT, CATEGORY).withSuppressUntilCount(60);
        assertFalse(evaluate(decisions(59, 0, "APPROVED"), state).eligible());
        assertTrue(evaluate(decisions(60, 0, "APPROVED"), state).eligible());
    }
}
