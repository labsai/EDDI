/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate side of the mandatory, argument-aware approval hook (WP-0.2): a call
 * a {@link MandatoryApprovalRule} matches is gated before the pattern
 * precedence (P1 exempt, P3 empty requireApproval) is evaluated.
 */
@DisplayName("ToolApprovalGate — mandatory, argument-aware approvals")
class ToolApprovalGateMandatoryTest {

    private static final String USER_ID = "64b7f0c2a1d3e4f5a6b7c8d9";

    private final ToolApprovalGate gate = new ToolApprovalGate();

    /**
     * A rule that asks for the user's consent whenever the arguments carry a file.
     */
    private static final MandatoryApprovalRule FILE_EGRESS = request -> request.arguments() != null && request.arguments().contains("\"file\"")
            ? Optional.of(new MandatoryApproval("file-egress", ApproverRule.CONVERSATION_USER, "Sends 1 file out of this deployment"))
            : Optional.empty();

    private static ToolExecutionRequest call(String id, String name, String args) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(args).build();
    }

    private static ToolApprovalsConfig config(List<String> require, List<String> exempt) {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(require);
        cfg.setExempt(exempt);
        return cfg;
    }

    @Nested
    @DisplayName("gated before P1-P3")
    class BeforePatterns {

        @Test
        @DisplayName("P1: a tool in exempt is still gated when a rule matches its arguments")
        void exemptDoesNotApply() {
            var send = call("c1", "sendToPeer", "{\"file\":\"report.pdf\"}");
            var cfg = config(List.of("*"), List.of("sendToPeer"));

            var result = gate.classify(List.of(send), Map.of(), Map.of(), cfg, ClearedToolCalls.none(), List.of(FILE_EGRESS));

            assertEquals(List.of(send), result.gated());
            assertTrue(result.allowed().isEmpty());
            assertEquals("file-egress", result.mandatoryOf(send).reasonCode());
            assertEquals(ApproverRule.CONVERSATION_USER, result.mandatoryOf(send).approver());
            assertEquals(MandatoryApprovals.GATE_REASON_PREFIX + "file-egress", result.gateReasonByCallId().get("c1"));
        }

        @Test
        @DisplayName("P3: an empty requireApproval does not disable the gate for a mandatory call")
        void emptyRequireApproval() {
            var send = call("c1", "sendToPeer", "{\"file\":\"report.pdf\"}");

            var result = gate.classify(List.of(send), Map.of(), Map.of(), config(List.of(), null), ClearedToolCalls.none(),
                    List.of(FILE_EGRESS));

            assertEquals(List.of(send), result.gated());
            assertNotNull(result.mandatoryOf(send));
        }

        @Test
        @DisplayName("no config at all: a mandatory call is gated, a plain call allowed")
        void nullConfig() {
            var send = call("c1", "sendToPeer", "{\"file\":\"report.pdf\"}");
            var plain = call("c2", "sendToPeer", "{\"text\":\"hello\"}");

            var result = gate.classify(List.of(send, plain), Map.of(), Map.of(), null, ClearedToolCalls.none(), List.of(FILE_EGRESS));

            assertEquals(List.of(send), result.gated());
            assertEquals(List.of(plain), result.allowed());
            assertNull(result.mandatoryOf(plain));
        }
    }

    @Nested
    @DisplayName("calls the rules leave alone")
    class Untouched {

        @Test
        @DisplayName("no rules: the result equals the overload without rules, for every config shape")
        void noRulesIsIdentical() {
            var a = call("c1", "deleteAgent", "{}");
            var b = call("c2", "calculate", "{\"expression\":\"1+1\"}");
            for (ToolApprovalsConfig cfg : new ToolApprovalsConfig[]{null, config(List.of(), null), config(List.of("delete*"), null),
                    config(List.of("*"), List.of("calculate"))}) {
                var without = gate.classify(List.of(a, b), Map.of(), Map.of(), cfg, ClearedToolCalls.none());
                var withEmpty = gate.classify(List.of(a, b), Map.of(), Map.of(), cfg, ClearedToolCalls.none(), List.of());
                var withNonMatching = gate.classify(List.of(a, b), Map.of(), Map.of(), cfg, ClearedToolCalls.none(), List.of(FILE_EGRESS));
                assertEquals(without, withEmpty);
                assertEquals(without, withNonMatching);
            }
        }

        @Test
        @DisplayName("a mixed batch keeps the model's order among gated calls and pattern reasons for pattern-gated ones")
        void mixedBatch() {
            var deleteIt = call("c1", "deleteAgent", "{}");
            var send = call("c2", "sendToPeer", "{\"file\":\"x.pdf\"}");
            var calc = call("c3", "calculate", "{}");

            var result = gate.classify(List.of(deleteIt, send, calc), Map.of(), Map.of(), config(List.of("delete*"), null),
                    ClearedToolCalls.none(), List.of(FILE_EGRESS));

            assertEquals(List.of(deleteIt, send), result.gated());
            assertEquals(List.of(calc), result.allowed());
            assertEquals("delete*", result.gateReasonByCallId().get("c1"));
            assertNull(result.mandatoryOf(deleteIt));
            assertNotNull(result.mandatoryOf(send));
        }
    }

    @Nested
    @DisplayName("rules")
    class Rules {

        @Test
        @DisplayName("several rules: the strictest approver wins")
        void strictestWins() {
            MandatoryApprovalRule oversight = r -> Optional.of(new MandatoryApproval("audit", ApproverRule.ANY_PERMITTED_APPROVER, "Reviewed"));
            var send = call("c1", "sendToPeer", "{\"file\":\"x.pdf\"}");

            var result = gate.classify(List.of(send), Map.of(), Map.of(), null, ClearedToolCalls.none(), List.of(oversight, FILE_EGRESS));

            assertEquals(ApproverRule.CONVERSATION_USER, result.mandatoryOf(send).approver());
            assertEquals("file-egress", result.mandatoryOf(send).reasonCode());
        }

        @Test
        @DisplayName("a rule that throws gates the call for the conversation user — never allows it")
        void throwingRuleFailsClosed() {
            MandatoryApprovalRule broken = r -> {
                throw new IllegalStateException("boom");
            };
            var send = call("c1", "sendToPeer", "{}");

            var result = gate.classify(List.of(send), Map.of(), Map.of(), null, ClearedToolCalls.none(), List.of(broken));

            assertEquals(List.of(send), result.gated());
            assertEquals(MandatoryApproval.REASON_RULE_FAILED, result.mandatoryOf(send).reasonCode());
            assertEquals(ApproverRule.CONVERSATION_USER, result.mandatoryOf(send).approver());
        }

        @Test
        @DisplayName("a reissue of an approved, executed mandatory call is gated as a repeat and keeps its approver")
        void repeatKeepsMandatory() {
            var send = call("c1", "sendToPeer", "{\"file\":\"x.pdf\"}");
            var cleared = new ClearedToolCalls();
            cleared.clear("c1", "sendToPeer", "{\"file\":\"x.pdf\"}");

            var result = gate.classify(List.of(send), Map.of(), Map.of(), config(List.of(), List.of("*")), cleared, List.of(FILE_EGRESS));

            assertEquals(List.of(send), result.gated());
            assertEquals(ToolApprovalGate.REPEAT_REASON, result.gateReasonByCallId().get("c1"));
            assertEquals(ApproverRule.CONVERSATION_USER, result.mandatoryOf(send).approver());
        }

        @Test
        @DisplayName("withGated keeps the mandatory markers")
        void withGatedKeepsMarkers() {
            var send = call("c1", "sendToPeer", "{\"file\":\"x.pdf\"}");
            var result = gate.classify(List.of(send), Map.of(), Map.of(), null, ClearedToolCalls.none(), List.of(FILE_EGRESS));

            assertNotNull(result.withGated(List.of(send)).mandatoryOf(send));
        }

        @Test
        @DisplayName("an approval needs a reason code and an approver; the summary is capped")
        void approvalValidation() {
            assertThrows(IllegalArgumentException.class, () -> new MandatoryApproval(" ", ApproverRule.CONVERSATION_USER, null));
            assertThrows(IllegalArgumentException.class, () -> new MandatoryApproval("x", null, null));
            var capped = new MandatoryApproval("x", ApproverRule.CONVERSATION_USER, "s".repeat(2_000));
            assertEquals(MandatoryApproval.SUMMARY_MAX_CHARS, capped.summaryForApprover().length());
        }
    }

    @Nested
    @DisplayName("unpausable contexts — refused, never allowed")
    class Unpausable {

        private final MandatoryApproval consent = new MandatoryApproval("file-egress", ApproverRule.CONVERSATION_USER, "s");
        private final MandatoryApproval oversight = new MandatoryApproval("audit", ApproverRule.ANY_PERMITTED_APPROVER, "s");

        @Test
        @DisplayName("a group member's turn cannot pause")
        void groupTurn() {
            assertEquals(MandatoryApprovals.UNPAUSABLE_GROUP_TURN,
                    MandatoryApprovals.unpausableReason(consent, config(List.of("*"), null), USER_ID, "64b7f0c2a1d3e4f5a6b7c8e0"));
        }

        @Test
        @DisplayName("consent without a conversation user cannot pause")
        void noUser() {
            assertEquals(MandatoryApprovals.UNPAUSABLE_NO_USER, MandatoryApprovals.unpausableReason(consent, null, null, null));
            assertEquals(MandatoryApprovals.UNPAUSABLE_NO_USER, MandatoryApprovals.unpausableReason(consent, null, " ", null));
        }

        @Test
        @DisplayName("consent with a user pauses even without any approval gate")
        void consentWithoutGate() {
            assertNull(MandatoryApprovals.unpausableReason(consent, null, USER_ID, null));
        }

        @Test
        @DisplayName("oversight without an active gate cannot pause; a suspension or the UNDETERMINED sentinel is an active gate")
        void oversightNeedsGate() {
            assertEquals(MandatoryApprovals.UNPAUSABLE_NO_GATE, MandatoryApprovals.unpausableReason(oversight, null, USER_ID, null));
            assertEquals(MandatoryApprovals.UNPAUSABLE_NO_GATE,
                    MandatoryApprovals.unpausableReason(oversight, config(List.of(), List.of("*")), USER_ID, null));
            assertNull(MandatoryApprovals.unpausableReason(oversight, config(List.of("x"), null), USER_ID, null));
            var suspended = config(List.of(), null);
            suspended.setSuspendedPatterns(List.of("sendToPeer"));
            assertNull(MandatoryApprovals.unpausableReason(oversight, suspended, USER_ID, null));
            assertNull(MandatoryApprovals.unpausableReason(oversight, ToolApprovalsConfig.UNDETERMINED, USER_ID, null));
        }

        @Test
        @DisplayName("ApproverRule: stricter and lenient parse")
        void approverRule() {
            assertEquals(ApproverRule.CONVERSATION_USER, ApproverRule.stricter(ApproverRule.ANY_PERMITTED_APPROVER, ApproverRule.CONVERSATION_USER));
            assertEquals(ApproverRule.ANY_PERMITTED_APPROVER, ApproverRule.stricter(null, ApproverRule.ANY_PERMITTED_APPROVER));
            assertNull(ApproverRule.parse("nobody"));
            assertEquals(ApproverRule.CONVERSATION_USER, ApproverRule.parse("CONVERSATION_USER"));
        }
    }
}
