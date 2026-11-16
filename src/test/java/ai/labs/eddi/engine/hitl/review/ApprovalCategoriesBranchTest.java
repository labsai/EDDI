/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.review;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.hitl.model.GroupHitlReviewConfig;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.review.ApprovalCategories.ApprovalCategory;
import ai.labs.eddi.engine.hitl.review.ApprovalCategories.Source;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The category every decision is filed under in the decision audit: the
 * configured one when there is one, else a name derived from what paused.
 */
class ApprovalCategoriesBranchTest {

    private static ToolApprovalsConfig.ApprovalRule rule(String match, String category) {
        var rule = new ToolApprovalsConfig.ApprovalRule();
        rule.setMatch(match);
        rule.setApprovalCategory(category);
        return rule;
    }

    private static ToolApprovalsConfig approvals(ToolApprovalsConfig.ApprovalRule... rules) {
        var config = new ToolApprovalsConfig();
        config.setRules(new ArrayList<>(Arrays.asList(rules)));
        return config;
    }

    @Test
    void theMatchedRulesCategoryWins() {
        var config = approvals(null, rule("send_*", " "), rule("pay_*", "payments"), rule("send_*", "outbound-mail"));
        assertEquals(new ApprovalCategory("outbound-mail", Source.CONFIG), ApprovalCategories.forToolCall(config, "send_*", "send_*"),
                "a blank category on the first matching rule is skipped");
        assertEquals(new ApprovalCategory("payments", Source.CONFIG), ApprovalCategories.forToolCall(config, null, "pay_*"));
    }

    @Test
    void withoutAConfiguredCategoryTheGatingPatternIsUsed() {
        var config = approvals(rule("send_*", null));
        assertEquals(new ApprovalCategory("tool:send_email", Source.DERIVED), ApprovalCategories.forToolCall(config, "send_email", "send_*"));
        assertEquals(new ApprovalCategory("tool:send_*", Source.DERIVED), ApprovalCategories.forToolCall(config, " ", "send_*"),
                "then the matched rule");
        assertEquals(new ApprovalCategory("tool:*", Source.DERIVED), ApprovalCategories.forToolCall(null, null, null), "then everything");
        assertEquals(new ApprovalCategory("tool:*", Source.DERIVED), ApprovalCategories.forToolCall(new ToolApprovalsConfig(), null, " "));
        assertEquals(new ApprovalCategory("tool:x", Source.DERIVED), ApprovalCategories.forToolCall(config, "x", null));
    }

    @Test
    void aRulePauseUsesTheReviewCategoryOrTheAgent() {
        var hitl = new AgentConfiguration.HitlConfig();
        assertEquals(new ApprovalCategory("rule:agent-1", Source.DERIVED), ApprovalCategories.forRule(hitl, "agent-1"));
        assertEquals(new ApprovalCategory("rule:agent-1", Source.DERIVED), ApprovalCategories.forRule(null, "agent-1"));
        var review = new HitlReviewConfig();
        hitl.setReview(review);
        assertEquals(new ApprovalCategory("rule:agent-1", Source.DERIVED), ApprovalCategories.forRule(hitl, "agent-1"));
        review.setApprovalCategory("refunds");
        assertEquals(new ApprovalCategory("refunds", Source.CONFIG), ApprovalCategories.forRule(hitl, "agent-1"));
    }

    @Test
    void groupPausesUseThePhaseCategoryOrTheGroupAndPhase() {
        var hitl = new AgentGroupConfiguration.HitlConfig();
        assertEquals(new ApprovalCategory("group:g-1:phase:Draft", Source.DERIVED), ApprovalCategories.forGroupPhase(hitl, "g-1", "Draft"));
        assertEquals(new ApprovalCategory("group:g-1:task:unknown", Source.DERIVED), ApprovalCategories.forGroupTask(hitl, "g-1", null));
        assertEquals(new ApprovalCategory("group:g-1:task:Draft", Source.DERIVED), ApprovalCategories.forGroupTask(null, "g-1", "Draft"));

        var review = new GroupHitlReviewConfig();
        hitl.setReview(review);
        assertEquals(Source.DERIVED, ApprovalCategories.forGroupPhase(hitl, "g-1", "Draft").source());
        Map<String, String> categories = new HashMap<>();
        categories.put("Draft", "drafts");
        categories.put("Publish", " ");
        review.setPhaseCategories(categories);
        assertEquals(new ApprovalCategory("drafts", Source.CONFIG), ApprovalCategories.forGroupPhase(hitl, "g-1", "Draft"));
        assertEquals(new ApprovalCategory("group:g-1:task:Publish", Source.DERIVED), ApprovalCategories.forGroupTask(hitl, "g-1", "Publish"));
    }
}
