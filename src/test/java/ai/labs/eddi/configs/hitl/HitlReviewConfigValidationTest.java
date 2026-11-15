/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.hitl.model.GroupHitlReviewConfig;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("hitlConfig.review validation")
class HitlReviewConfigValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("a config without review loads unchanged and validates")
    void legacyConfig() throws Exception {
        var hitl = mapper.readValue("{\"approvalTimeout\":\"PT5M\",\"timeoutPolicy\":\"AUTO_REJECT\"}", AgentConfiguration.HitlConfig.class);
        assertNull(hitl.getReview());
        assertDoesNotThrow(() -> HitlConfigValidation.validate(hitl));
    }

    @Test
    @DisplayName("review deserializes with safe defaults")
    void defaults() throws Exception {
        var hitl = mapper.readValue("{\"review\":{}}", AgentConfiguration.HitlConfig.class);
        HitlReviewConfig review = hitl.getReview();
        assertFalse(review.answerEditsAllowed());
        assertEquals(HitlReviewConfig.ScreenEdits.OUTPUT_GUARDRAILS, review.getScreenEdits());
        assertEquals(16_000, review.effectiveMaxEditChars());
        var explicitNull = mapper.readValue("{\"review\":{\"screenEdits\":null}}", AgentConfiguration.HitlConfig.class);
        assertEquals(HitlReviewConfig.ScreenEdits.OUTPUT_GUARDRAILS, explicitNull.getReview().getScreenEdits());
    }

    @Nested
    @DisplayName("agent review")
    class AgentReview {

        private AgentConfiguration.HitlConfig hitl(HitlReviewConfig review) {
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setReview(review);
            return hitl;
        }

        @Test
        void validConfigPasses() {
            var review = new HitlReviewConfig();
            review.setAllowAnswerEdits(true);
            review.setMaxEditChars(65_536);
            review.setApprovalCategory("github.reply");
            review.setReasonCodes(Map.of("FALSE_CLAIM", "States something untrue", "TONE", "Wrong tone"));
            assertDoesNotThrow(() -> HitlConfigValidation.validate(hitl(review)));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 65_537})
        void maxEditCharsOutOfRange(int value) {
            var review = new HitlReviewConfig();
            review.setMaxEditChars(value);
            var e = assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(review)));
            assertTrue(e.getMessage().contains("hitlConfig.review.maxEditChars"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"GitHub.Reply", "", "-leading", "has space", "tool:http.post:*"})
        void invalidCategory(String category) {
            var review = new HitlReviewConfig();
            review.setApprovalCategory(category);
            var e = assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(review)));
            assertTrue(e.getMessage().contains("approvalCategory"));
        }

        @Test
        void categoryLength() {
            assertDoesNotThrow(() -> HitlReviewConfigValidation.validateCategory("x".repeat(128), "x"));
            assertThrows(IllegalArgumentException.class, () -> HitlReviewConfigValidation.validateCategory("x".repeat(129), "x"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"a", "github.reply", "rule:agent-1", "group:g:phase:exec", "a/b-c_d"})
        void validCategories(String category) {
            assertDoesNotThrow(() -> HitlReviewConfigValidation.validateCategory(category, "x"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"lower", "A", "1ABC", "BAD-CODE"})
        void invalidReasonCode(String code) {
            var review = new HitlReviewConfig();
            review.setReasonCodes(Map.of(code, "label"));
            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(review)));
        }

        @Test
        void reasonCodeLimits() {
            var empty = new HitlReviewConfig();
            empty.setReasonCodes(Map.of());
            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(empty)));

            var tooMany = new HitlReviewConfig();
            var codes = new LinkedHashMap<String, String>();
            for (int i = 0; i < 31; i++) {
                codes.put("CODE_" + i, "label");
            }
            tooMany.setReasonCodes(codes);
            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(tooMany)));

            var longLabel = new HitlReviewConfig();
            longLabel.setReasonCodes(Map.of("TONE", "x".repeat(121)));
            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(longLabel)));

            var blankLabel = new HitlReviewConfig();
            blankLabel.setReasonCodes(Map.of("TONE", " "));
            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(blankLabel)));
        }
    }

    @Nested
    @DisplayName("tool rules")
    class ToolRules {

        @Test
        void ruleCategoryIsValidated() {
            var cfg = new ToolApprovalsConfig();
            cfg.setRequireApproval(List.of("http.post:*"));
            var rule = new ToolApprovalsConfig.ApprovalRule();
            rule.setMatch("http.post:*");
            rule.setApprovalCategory("github.reply");
            cfg.setRules(List.of(rule));
            assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(cfg, "hitlConfig.toolApprovals"));

            rule.setApprovalCategory("Not Valid");
            var e = assertThrows(IllegalArgumentException.class,
                    () -> HitlConfigValidation.validateToolApprovals(cfg, "hitlConfig.toolApprovals"));
            assertTrue(e.getMessage().contains("hitlConfig.toolApprovals.rules[0].approvalCategory"));
        }
    }

    @Nested
    @DisplayName("group review")
    class GroupReview {

        private AgentGroupConfiguration.HitlConfig hitl(GroupHitlReviewConfig review) {
            var hitl = new AgentGroupConfiguration.HitlConfig();
            hitl.setReview(review);
            return hitl;
        }

        @Test
        void validConfigPasses() throws Exception {
            var hitl = mapper.readValue("{\"review\":{\"allowResultEdits\":true,\"maxEditChars\":100,"
                    + "\"phaseCategories\":{\"Execution\":\"directory.submission\"},\"reasonCodes\":{\"OTHER\":\"Other\"}}}",
                    AgentGroupConfiguration.HitlConfig.class);
            assertTrue(hitl.getReview().resultEditsAllowed());
            assertEquals(100, hitl.getReview().effectiveMaxEditChars());
            assertDoesNotThrow(() -> HitlConfigValidation.validate(hitl));
        }

        @Test
        void invalidPhaseCategory() {
            var review = new GroupHitlReviewConfig();
            review.setPhaseCategories(Map.of("Execution", "UPPER"));
            var e = assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(review)));
            assertTrue(e.getMessage().contains("phaseCategories['Execution']"));

            var blankKey = new GroupHitlReviewConfig();
            blankKey.setPhaseCategories(Map.of(" ", "ok"));
            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(blankKey)));
        }

        @Test
        void maxEditCharsAndReasonCodes() {
            var review = new GroupHitlReviewConfig();
            review.setMaxEditChars(0);
            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(review)));
            var codes = new GroupHitlReviewConfig();
            codes.setReasonCodes(Map.of("bad", "x"));
            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl(codes)));
        }
    }
}
