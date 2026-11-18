/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import ai.labs.eddi.engine.hitl.review.HitlDecisionRecords;
import ai.labs.eddi.engine.hitl.tools.MandatoryApprovalAttribution.ActingAs;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The decision side of the mandatory approval hook (WP-0.2): who may approve a
 * mandatorily gated call ({@link MandatoryApproverCheck}) and how an
 * administrator's approval on a user's behalf is recorded.
 */
@DisplayName("Mandatory approvals — who may approve, and the record of it")
class MandatoryApprovalResumeTest {

    private static final String USER_ID = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String OTHER_ID = "64b7f0c2a1d3e4f5a6b7c8e0";
    private static final String ADMIN_ID = "64b7f0c2a1d3e4f5a6b7c8e1";

    private static PendingToolCallBatch.PendingToolCall call(String callId, ApproverRule rule) {
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setCallId(callId);
        call.setToolName("sendToPeer");
        call.setSource("a2a");
        call.setArgumentsRaw("{\"file\":\"report.pdf\"}");
        if (rule != null) {
            call.setMandatoryReasonCode("file-egress");
            call.setMandatoryApprover(rule.name());
            call.setMandatorySummary("Sends 1 file out of this deployment");
        }
        return call;
    }

    private static PendingToolCallBatch batch(PendingToolCallBatch.PendingToolCall... calls) {
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setCalls(new ArrayList<>(List.of(calls)));
        return batch;
    }

    private static HitlDecision decision(HitlVerdict verdict, String decidedBy, String note) {
        var decision = new HitlDecision();
        decision.setVerdict(verdict);
        decision.setDecidedBy(decidedBy);
        decision.setNote(note);
        return decision;
    }

    private static CallerIdentity user(String id) {
        return new CallerIdentity("token", id, "test");
    }

    private static CallerIdentity admin(String id) {
        return new CallerIdentity("token", id, "test", Map.of(), true);
    }

    @Nested
    @DisplayName("CONVERSATION_USER")
    class ConversationUser {

        private final PendingToolCallBatch consent = batch(call("c1", ApproverRule.CONVERSATION_USER));

        @Test
        @DisplayName("the conversation's verified user is accepted, as USER")
        void verifiedUser() {
            var accepted = MandatoryApproverCheck.enforce(consent, decision(HitlVerdict.APPROVED, USER_ID, null), user(USER_ID), USER_ID,
                    Provenance.VERIFIED);

            assertEquals(new MandatoryApprovalAttribution(USER_ID, null, ActingAs.USER), accepted);
        }

        @Test
        @DisplayName("the conversation's user on a self-asserted conversation is refused")
        void selfAssertedUser() {
            var refused = assertThrows(MandatoryApprovalRefusedException.class, () -> MandatoryApproverCheck.enforce(consent,
                    decision(HitlVerdict.APPROVED, USER_ID, null), user(USER_ID), USER_ID, Provenance.SELF_ASSERTED));
            assertEquals(MandatoryApproverCheck.REFUSED_NOT_USER, refused.getMessage());
        }

        @Test
        @DisplayName("another, non-admin approver (an approver-role user, a delegate) is refused")
        void otherApproverRefused() {
            var refused = assertThrows(MandatoryApprovalRefusedException.class, () -> MandatoryApproverCheck.enforce(consent,
                    decision(HitlVerdict.APPROVED, OTHER_ID, "looks fine"), user(OTHER_ID), USER_ID, Provenance.VERIFIED));
            assertEquals(MandatoryApproverCheck.REFUSED_NOT_USER, refused.getMessage());
        }

        @Test
        @DisplayName("no approver identity (auth off, an unauthenticated channel) is refused")
        void noIdentityRefused() {
            assertThrows(MandatoryApprovalRefusedException.class, () -> MandatoryApproverCheck.enforce(consent,
                    decision(HitlVerdict.APPROVED, "slack:U123", null), null, USER_ID, Provenance.VERIFIED));
        }

        @Test
        @DisplayName("an administrator with a reason is accepted, on the user's behalf")
        void adminWithReason() {
            var accepted = MandatoryApproverCheck.enforce(consent, decision(HitlVerdict.APPROVED, ADMIN_ID, "user asked by phone"),
                    admin(ADMIN_ID), USER_ID, Provenance.VERIFIED);

            assertEquals(new MandatoryApprovalAttribution(ADMIN_ID, USER_ID, ActingAs.ADMIN_ON_BEHALF), accepted);
        }

        @Test
        @DisplayName("an administrator without a reason is refused")
        void adminWithoutReason() {
            var refused = assertThrows(MandatoryApprovalRefusedException.class, () -> MandatoryApproverCheck.enforce(consent,
                    decision(HitlVerdict.APPROVED, ADMIN_ID, "  "), admin(ADMIN_ID), USER_ID, Provenance.VERIFIED));
            assertEquals(MandatoryApproverCheck.REFUSED_ADMIN_WITHOUT_REASON, refused.getMessage());
        }

        @Test
        @DisplayName("an administrator's per-call note counts as the reason")
        void adminPerCallNote() {
            var decision = decision(HitlVerdict.APPROVED, ADMIN_ID, null);
            var cd = new ToolCallDecision();
            cd.setVerdict(HitlVerdict.APPROVED);
            cd.setNote("confirmed with the user");
            decision.setToolDecisions(Map.of("c1", cd));

            assertEquals(ActingAs.ADMIN_ON_BEHALF,
                    MandatoryApproverCheck.enforce(consent, decision, admin(ADMIN_ID), USER_ID, Provenance.VERIFIED).actingAs());
        }

        @Test
        @DisplayName("an unknown persisted approver value is enforced as CONVERSATION_USER")
        void unknownRuleIsStrictest() {
            var unknown = call("c1", ApproverRule.CONVERSATION_USER);
            unknown.setMandatoryApprover("SOMEONE_NEW");

            assertThrows(MandatoryApprovalRefusedException.class, () -> MandatoryApproverCheck.enforce(batch(unknown),
                    decision(HitlVerdict.APPROVED, OTHER_ID, null), user(OTHER_ID), USER_ID, Provenance.VERIFIED));
        }
    }

    @Nested
    @DisplayName("every rule")
    class EveryRule {

        @Test
        @DisplayName("an automatic decision (timeout policy) never approves a mandatory call")
        void systemDecisionRefused() {
            for (ApproverRule rule : ApproverRule.values()) {
                var refused = assertThrows(MandatoryApprovalRefusedException.class, () -> MandatoryApproverCheck.enforce(
                        batch(call("c1", rule)), decision(HitlVerdict.APPROVED, "system:timeout", null), admin(ADMIN_ID), USER_ID,
                        Provenance.VERIFIED));
                assertEquals(MandatoryApproverCheck.REFUSED_AUTOMATED, refused.getMessage());
            }
        }

        @Test
        @DisplayName("a rejection is never refused — by anyone, including a timeout")
        void rejectionAlwaysAllowed() {
            var consent = batch(call("c1", ApproverRule.CONVERSATION_USER));
            assertNull(MandatoryApproverCheck.enforce(consent, decision(HitlVerdict.REJECTED, OTHER_ID, null), user(OTHER_ID), USER_ID,
                    Provenance.VERIFIED));
            assertNull(MandatoryApproverCheck.enforce(consent, decision(HitlVerdict.REJECTED, "system:timeout", null), null, USER_ID,
                    Provenance.VERIFIED));
        }

        @Test
        @DisplayName("a per-call rejection of the mandatory call under a top-level approval needs no special approver")
        void perCallRejection() {
            var decision = decision(HitlVerdict.APPROVED, OTHER_ID, null);
            var cd = new ToolCallDecision();
            cd.setVerdict(HitlVerdict.REJECTED);
            decision.setToolDecisions(Map.of("c1", cd));

            assertNull(MandatoryApproverCheck.enforce(batch(call("c1", ApproverRule.CONVERSATION_USER), call("c2", null)), decision,
                    user(OTHER_ID), USER_ID, Provenance.VERIFIED));
        }

        @Test
        @DisplayName("ANY_PERMITTED_APPROVER accepts whoever the endpoint admitted, as APPROVER")
        void anyPermittedApprover() {
            var accepted = MandatoryApproverCheck.enforce(batch(call("c1", ApproverRule.ANY_PERMITTED_APPROVER)),
                    decision(HitlVerdict.APPROVED, OTHER_ID, null), user(OTHER_ID), USER_ID, Provenance.SELF_ASSERTED);

            assertEquals(new MandatoryApprovalAttribution(OTHER_ID, null, ActingAs.APPROVER), accepted);
        }

        @Test
        @DisplayName("a batch without mandatory calls is not enforced at all")
        void noMandatoryCalls() {
            assertNull(MandatoryApproverCheck.enforce(batch(call("c1", null)), decision(HitlVerdict.APPROVED, "system:timeout", null), null,
                    USER_ID, Provenance.VERIFIED));
        }

        @Test
        @DisplayName("amended arguments of a mandatory call are refused before the pause is consumed")
        void amendmentRefused() {
            var decision = decision(HitlVerdict.APPROVED, USER_ID, null);
            var cd = new ToolCallDecision();
            cd.setVerdict(HitlVerdict.APPROVED);
            cd.setAmendedArguments("{\"file\":\"other.pdf\"}");
            decision.setToolDecisions(Map.of("c1", cd));

            var refused = assertThrows(IllegalArgumentException.class,
                    () -> MandatoryApproverCheck.refuseAmendments(batch(call("c1", ApproverRule.CONVERSATION_USER)), decision));
            assertEquals(MandatoryApproverCheck.REFUSED_AMENDMENT, refused.getMessage());
            assertDoesNotThrow(() -> MandatoryApproverCheck.refuseAmendments(batch(call("c1", null)), decision));
        }
    }

    @Test
    @DisplayName("an administrator's approval on the user's behalf is written to the ledger entry")
    void adminOnBehalfInLedger() {
        var ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        var decision = decision(HitlVerdict.APPROVED, ADMIN_ID, "user asked by phone");
        decision.setMandatoryApproval(new MandatoryApprovalAttribution(ADMIN_ID, USER_ID, ActingAs.ADMIN_ON_BEHALF));

        new HitlApprovalAudit(ledger).write(HitlDecisionRecords.regularDecision("64b7f0c2a1d3e4f5a6b7c8ff", "agent-1", 1, USER_ID,
                "production", decision, true, batch(call("c1", ApproverRule.CONVERSATION_USER)), null, null, List.of(), null));

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(ledger).submit(captor.capture());
        Map<String, Object> output = captor.getValue().output();
        assertEquals("ADMIN_ON_BEHALF", output.get("actingAs"));
        assertEquals(ADMIN_ID, output.get("approvedBy"));
        assertEquals(USER_ID, output.get("onBehalfOf"));
        assertEquals("user asked by phone", output.get("note"));
    }

    @Test
    @DisplayName("a decision without a mandatory approval writes none of the on-behalf fields")
    void noOnBehalfFieldsOtherwise() {
        var ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);

        new HitlApprovalAudit(ledger).write(HitlDecisionRecords.regularDecision("64b7f0c2a1d3e4f5a6b7c8ff", "agent-1", 1, USER_ID,
                "production", decision(HitlVerdict.APPROVED, USER_ID, null), true, batch(call("c1", null)), null, null, List.of(), null));

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(ledger).submit(captor.capture());
        assertNull(captor.getValue().output().get("actingAs"));
        assertNull(captor.getValue().output().get("onBehalfOf"));
    }
}
