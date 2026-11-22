/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.engine.security.TurnOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The email approval gate's timeout (email plan v3 §8.1): a pause the gate
 * caused is {@code AUTO_REJECT} after the configured expiry, whatever the
 * agent's tool approvals or rules said; any other pause of a mail turn never
 * auto-approves; a turn without the gate is left alone.
 */
class ConversationHitlServiceEmailGateTimeoutTest {

    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv1", "agent1", 1, "email:int1:alice@example.com");
        memory.setOrigin(TurnOrigin.EMAIL);
        memory.getCurrentStep().storeData(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY,
                EmailApprovalGate.contextValue("everyTurn", List.of(), true, null, "held")));
    }

    private void toolPause(String gateReason, HitlTimeoutPolicy agentPolicy) {
        memory.setHitlPauseType("TOOL_CALL");
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setToolName("createOrder");
        call.setGateReason(gateReason);
        var batch = new PendingToolCallBatch();
        batch.setCalls(List.of(call));
        memory.setHitlPendingToolCalls(batch);
        memory.setHitlTimeoutPolicy(agentPolicy);
        memory.setHitlApprovalTimeout("PT1M");
    }

    @Test
    @DisplayName("a pause before the agent runs is AUTO_REJECT after the expiry, with the gate's reason")
    void preRunPause() {
        memory.setHitlPauseType("RULE");
        memory.setHitlPauseReason("the agent designer's reason");
        memory.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
        memory.getCurrentStep().storeData(new Data<>(EmailApprovalGate.PRE_RUN_MARKER, Boolean.TRUE));

        ConversationHitlService.applyEmailGateTimeout(memory, "PT72H");

        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy());
        assertEquals("PT72H", memory.getHitlApprovalTimeout());
        assertEquals("held", memory.getHitlPauseReason());
    }

    @Test
    @DisplayName("a tool pause holding an email-gated call beats an AUTO_APPROVE rule of the agent")
    void emailGatedToolPause() {
        toolPause(EmailApprovalGate.GATE_REASON, HitlTimeoutPolicy.AUTO_APPROVE);

        ConversationHitlService.applyEmailGateTimeout(memory, "PT72H");

        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy());
        assertEquals("PT72H", memory.getHitlApprovalTimeout());
    }

    @Test
    @DisplayName("a pause the agent's gate caused on a mail turn never auto-approves, and keeps its timeout")
    void agentPauseDemoted() {
        toolPause("createOrder", HitlTimeoutPolicy.AUTO_APPROVE);

        ConversationHitlService.applyEmailGateTimeout(memory, "PT72H");

        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy());
        assertEquals("PT1M", memory.getHitlApprovalTimeout());
    }

    @Test
    @DisplayName("an untainted turn keeps the agent's timeout")
    void untainted() {
        memory = new ConversationMemory("conv2", "agent1", 1, "user1");
        memory.getCurrentStep().storeData(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY,
                EmailApprovalGate.contextValue("actions", List.of(), false, null, null)));
        toolPause(EmailApprovalGate.GATE_REASON, HitlTimeoutPolicy.AUTO_APPROVE);

        ConversationHitlService.applyEmailGateTimeout(memory, "PT72H");

        assertEquals(HitlTimeoutPolicy.AUTO_APPROVE, memory.getHitlTimeoutPolicy());
    }

    @Test
    @DisplayName("the expiry: a configured positive duration, else the default")
    void expiry() {
        assertEquals("PT24H", ConversationHitlService.emailGateExpiry(" PT24H "));
        assertEquals(ConversationHitlService.DEFAULT_EMAIL_GATE_EXPIRY, ConversationHitlService.emailGateExpiry(null));
        assertEquals(ConversationHitlService.DEFAULT_EMAIL_GATE_EXPIRY, ConversationHitlService.emailGateExpiry("PT0S"));
        assertEquals(ConversationHitlService.DEFAULT_EMAIL_GATE_EXPIRY, ConversationHitlService.emailGateExpiry("three days"));
    }

    private void heldWithMail(String excerpt) {
        memory = new ConversationMemory("conv3", "agent1", 1, "email:int1:alice@example.com");
        memory.setOrigin(TurnOrigin.EMAIL);
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("from", "alice@example.com");
        mail.put("fromName", "Alice");
        mail.put("subject", "Refund order 7");
        mail.put("authenticated", true);
        mail.put("attachments", List.of("receipt.pdf"));
        mail.put("excerpt", excerpt);
        memory.getCurrentStep().storeData(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY,
                EmailApprovalGate.contextValue("everyTurn", List.of(), true, null, "held", mail)));
        memory.getCurrentStep().storeData(new Data<>(EmailApprovalGate.PRE_RUN_MARKER, EmailApprovalGate.PRE_RUN_START));
    }

    @Test
    @DisplayName("a held mail is the first item of the approval context: sender, subject, attachments, text; credentials redacted")
    void heldMailInApprovalContext() {
        heldWithMail("Please refund order 7. Status: https://x/y?api_key=abcdefghijklmnop");
        var agentItem = new RenderedApprovalContext.Item("Draft", "the agent's block", false, RenderedApprovalContext.SOURCE_AGENT, null, null);
        memory.setHitlApprovalContext(RenderedApprovalContext.of(List.of(agentItem), false));

        ConversationHitlService.addHeldMailContext(memory);

        var items = memory.getHitlApprovalContext().getItems();
        assertEquals(2, items.size(), "the designer's blocks stay");
        assertEquals(EmailApprovalGate.HELD_MAIL_SOURCE, items.get(0).getSource());
        String text = items.get(0).getText();
        assertTrue(text.contains("Alice <alice@example.com> (authenticated)"), text);
        assertTrue(text.contains("Subject: Refund order 7"), text);
        assertTrue(text.contains("Attachments: receipt.pdf"), text);
        assertTrue(text.contains("Please refund order 7."), text);
        assertFalse(text.contains("abcdefghijklmnop"), "credential shapes are redacted: " + text);
        assertEquals(RenderedApprovalContext.digestOf(items), memory.getHitlApprovalContext().getDigest());
        assertFalse(memory.getHitlPauseReason() != null && memory.getHitlPauseReason().contains("Refund"),
                "the pause reason, which the audit ledger keeps, has none of it");
    }

    @Test
    @DisplayName("no held mail: a pause that is not the gate's pre-run pause, or a gate without the mail, adds nothing")
    void noHeldMail() {
        heldWithMail("x");
        memory.getCurrentStep().removeData(EmailApprovalGate.PRE_RUN_MARKER);
        ConversationHitlService.addHeldMailContext(memory);
        assertNull(memory.getHitlApprovalContext());

        memory = new ConversationMemory("conv4", "agent1", 1, "email:int1:alice@example.com");
        memory.setOrigin(TurnOrigin.EMAIL);
        memory.getCurrentStep().storeData(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY,
                EmailApprovalGate.contextValue("everyTurn", List.of(), true, null, "held")));
        memory.getCurrentStep().storeData(new Data<>(EmailApprovalGate.PRE_RUN_MARKER, Boolean.TRUE));
        ConversationHitlService.addHeldMailContext(memory);
        assertNull(memory.getHitlApprovalContext());
    }
}
