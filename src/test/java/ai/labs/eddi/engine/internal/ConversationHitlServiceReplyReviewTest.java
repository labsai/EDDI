/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.engine.hitl.review.HitlDecisionValidator;
import ai.labs.eddi.engine.hitl.review.HitlReviewDetails;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.engine.security.EmailReplyReview;
import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HITL side of reply review (email plan v3 §8.2): the pause times out as a
 * rejection whatever the agent and the target say, the approver sees the whole
 * reply and may edit it on every agent, and the edit is checked before the
 * pause is consumed.
 */
class ConversationHitlServiceReplyReviewTest {

    private static final String DRAFT = "Your refund is on its way.";

    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv1", "agent1", 1, "email:int1:alice@example.com");
        memory.setOrigin(TurnOrigin.EMAIL);
        var step = memory.getCurrentStep();
        step.storeData(new Data<>(EmailReplyReview.STEP_KEY, EmailReplyReview.contextValue("always", "The reply waits.")));
        // approvalPolicy none: the email gate itself is off for this target.
        step.storeData(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, EmailApprovalGate.contextValue("none", null, false, null, null)));
        step.storeData(new Data<>(EmailReplyReview.MARKER, EmailReplyReview.marker(DRAFT)));
        memory.setHitlPauseType("RULE");
    }

    private ConversationMemorySnapshot snapshot() {
        return ConversationMemoryUtilities.convertConversationMemory(memory);
    }

    private static HitlDecision approve(String edit) {
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setEditedAnswer(edit);
        return decision;
    }

    @Test
    @DisplayName("an expired review is a rejection, also under approvalPolicy none and an agent that auto-approves")
    void expiryIsARejection() {
        memory.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
        memory.setHitlApprovalTimeout("PT1M");

        ConversationHitlService.applyEmailGateTimeout(memory, "PT72H");

        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy());
        assertEquals("PT72H", memory.getHitlApprovalTimeout());
        assertEquals("The reply waits.", memory.getHitlPauseReason());
    }

    @Test
    @DisplayName("the approver sees the reply as the first approval-context item, through the secret filter")
    void replyIsShownToTheApprover() {
        ConversationHitlService.addHeldMailContext(memory);

        var item = memory.getHitlApprovalContext().getItems().get(0);
        assertEquals(EmailReplyReview.REPLY_LABEL, item.getLabel());
        assertEquals(EmailReplyReview.REPLY_SOURCE, item.getSource());
        assertEquals(SecretRedactionFilter.redact(DRAFT), item.getText());
    }

    @Test
    @DisplayName("the audit's draft is the whole reply")
    void auditDraftIsTheReply() {
        assertEquals(DRAFT, ConversationHitlService.ruleDraftOf(memory));
    }

    @Test
    @DisplayName("approval-status serves the reply as editableAnswer on an agent without answer edits")
    void editableOnEveryAgent() {
        Map<String, Object> details = new LinkedHashMap<>();
        HitlReviewDetails.forRule(details, snapshot(), new AgentConfiguration.HitlConfig());

        assertEquals(Boolean.TRUE, details.get("replyReview"));
        @SuppressWarnings("unchecked")
        Map<String, Object> editable = (Map<String, Object>) details.get("editableAnswer");
        assertEquals(DRAFT, editable.get("draft"));
        assertEquals("text", editable.get("format"));
        assertEquals(HitlReviewConfig.DEFAULT_MAX_EDIT_CHARS, editable.get("maxChars"));
    }

    @Test
    @DisplayName("an edit is valid on an agent whose allowAnswerEdits is off; a blank or placeholder edit is not")
    void editValidation() {
        var snapshot = snapshot();
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(approve("Your refund left today."), snapshot, null));
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(approve("   "), snapshot, null));
        assertThrows(IllegalArgumentException.class,
                () -> HitlDecisionValidator.validate(approve("Key " + SecretRedactionFilter.REDACTED), snapshot, null),
                "the served view's placeholder would be mailed");
        var reject = new HitlDecision();
        reject.setVerdict(HitlVerdict.REJECTED);
        reject.setEditedAnswer("x");
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(reject, snapshot, null));
    }

    @Test
    @DisplayName("without a pending review the agent's own policy decides: no edit without allowAnswerEdits")
    void noReviewNoEdit() {
        memory.getCurrentStep().removeData(EmailReplyReview.MARKER);
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(approve("x"), snapshot(), null));
        Map<String, Object> details = new LinkedHashMap<>();
        HitlReviewDetails.forRule(details, snapshot(), null);
        assertNull(details.get("editableAnswer"));
    }

    @Test
    @DisplayName("a turn is armed only when tainted; an unreadable value still arms it")
    void armedNeedsTaint() {
        assertTrue(EmailReplyReview.armed(memory));
        memory.getCurrentStep().storeData(new Data<>(EmailReplyReview.STEP_KEY, new Context(Context.ContextType.string, "garbage")));
        assertTrue(EmailReplyReview.armed(memory), "a value of the wrong shape must not send a reply unreviewed");
        var untainted = new ConversationMemory("conv2", "agent1", 1, "u");
        untainted.getCurrentStep().storeData(new Data<>(EmailReplyReview.STEP_KEY, EmailReplyReview.contextValue("always", null)));
        assertTrue(!EmailReplyReview.armed(untainted), "only a mail turn is reviewed");
    }
}
