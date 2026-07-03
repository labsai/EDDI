/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SlackHitlSupport} — approver parsing/authz
 * (fail-closed), Block Kit builders (buttons omitted without approvers), and
 * the shared Slack-friendly response extraction.
 */
class SlackHitlSupportTest {

    // ─── Approver parsing / authorization ───

    @Test
    void parseApproverUserIds_null_returnsEmpty() {
        assertTrue(SlackHitlSupport.parseApproverUserIds(null).isEmpty());
        assertTrue(SlackHitlSupport.parseApproverUserIds("").isEmpty());
        assertTrue(SlackHitlSupport.parseApproverUserIds("  ").isEmpty());
    }

    @Test
    void parseApproverUserIds_trimsAndFilters() {
        var ids = SlackHitlSupport.parseApproverUserIds(" U1 , U2 ,, U3 ");
        assertEquals(3, ids.size());
        assertTrue(ids.contains("U1"));
        assertTrue(ids.contains("U2"));
        assertTrue(ids.contains("U3"));
    }

    @Test
    void isAuthorizedApprover_failsClosed_whenListUnset() {
        assertFalse(SlackHitlSupport.isAuthorizedApprover("U1", null));
        assertFalse(SlackHitlSupport.isAuthorizedApprover("U1", ""));
    }

    @Test
    void isAuthorizedApprover_rejectsNonMember() {
        assertFalse(SlackHitlSupport.isAuthorizedApprover("UEVIL", "U1,U2"));
    }

    @Test
    void isAuthorizedApprover_acceptsMember() {
        assertTrue(SlackHitlSupport.isAuthorizedApprover("U2", "U1,U2,U3"));
    }

    @Test
    void isAuthorizedApprover_nullUser_rejected() {
        assertFalse(SlackHitlSupport.isAuthorizedApprover(null, "U1"));
        assertFalse(SlackHitlSupport.isAuthorizedApprover("", "U1"));
    }

    // ─── Block Kit builders ───

    @Test
    void buildApprovalBlocks_withApprovers_includesButtons() {
        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "⏸️ Conversation awaiting approval", "Conversation", "conv-1",
                "agent-1", "Delete production DB", "WAIT (PT1H)", "conv-1", true);

        // section title, fields section, actions section
        assertEquals(3, blocks.size());
        var actions = blocks.get(2);
        assertEquals("actions", actions.get("type"));
        @SuppressWarnings("unchecked")
        var elements = (List<Map<String, Object>>) actions.get("elements");
        assertEquals(2, elements.size());
        assertEquals(SlackHitlSupport.ACTION_APPROVE, elements.get(0).get("action_id"));
        assertEquals(SlackHitlSupport.ACTION_REJECT, elements.get(1).get("action_id"));
        assertEquals("conv-1", elements.get(0).get("value"));
        assertEquals("conv-1", elements.get(1).get("value"));
    }

    @Test
    void buildApprovalBlocks_withoutApprovers_omitsButtons() {
        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "⏸️ Conversation awaiting approval", "Conversation", "conv-1",
                "agent-1", "reason", null, "conv-1", false);

        // No actions block — the third block is a context note, not buttons
        boolean anyActions = blocks.stream().anyMatch(b -> "actions".equals(b.get("type")));
        assertFalse(anyActions, "buttons must be omitted when no approver list is configured");
    }

    @Test
    void buildApprovalBlocks_groupValue_carriesGroupPrefix() {
        String value = SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-9";
        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "⏸️ Discussion awaiting approval", "Discussion", "gc-9",
                "phase 1", "reason", null, value, true);
        var actions = blocks.get(blocks.size() - 1);
        @SuppressWarnings("unchecked")
        var elements = (List<Map<String, Object>>) actions.get("elements");
        assertEquals(value, elements.get(0).get("value"));
        assertTrue(((String) elements.get(0).get("value")).startsWith("group:"));
    }

    @Test
    void buildResolvedBlocks_singleSection() {
        var blocks = SlackHitlSupport.buildResolvedBlocks("✅ Approved by <@U1>");
        assertEquals(1, blocks.size());
        assertEquals("section", blocks.get(0).get("type"));
    }

    // ─── Action value (integration-bound button payload) ───

    @Test
    void buildActionValue_withIntegration_prefixesName() {
        assertEquals("my-int|conv-1", SlackHitlSupport.buildActionValue("my-int", "conv-1"));
        assertEquals("my-int|group:gc-9",
                SlackHitlSupport.buildActionValue("my-int", SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-9"));
    }

    @Test
    void buildActionValue_noIntegration_returnsBareSubject() {
        assertEquals("conv-1", SlackHitlSupport.buildActionValue(null, "conv-1"));
        assertEquals("conv-1", SlackHitlSupport.buildActionValue("", "conv-1"));
    }

    @Test
    void parseActionValue_integrationBound_conversation() {
        var v = SlackHitlSupport.parseActionValue("my-int|conv-1");
        assertNotNull(v);
        assertEquals("my-int", v.integrationName());
        assertEquals("conv-1", v.subject());
        assertFalse(v.isGroup());
        assertNull(v.groupConversationId());
    }

    @Test
    void parseActionValue_integrationBound_group() {
        var v = SlackHitlSupport.parseActionValue("my-int|group:gc-9");
        assertNotNull(v);
        assertEquals("my-int", v.integrationName());
        assertTrue(v.isGroup());
        assertEquals("gc-9", v.groupConversationId());
    }

    @Test
    void parseActionValue_legacyBareValue_hasNoIntegration() {
        var v = SlackHitlSupport.parseActionValue("conv-1");
        assertNotNull(v);
        assertNull(v.integrationName());
        assertEquals("conv-1", v.subject());
    }

    @Test
    void parseActionValue_nullOrBlank_returnsNull() {
        assertNull(SlackHitlSupport.parseActionValue(null));
        assertNull(SlackHitlSupport.parseActionValue(""));
        assertNull(SlackHitlSupport.parseActionValue("  "));
    }

    @Test
    void parseActionValue_roundTrip() {
        String built = SlackHitlSupport.buildActionValue("int", SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-1");
        var v = SlackHitlSupport.parseActionValue(built);
        assertEquals("int", v.integrationName());
        assertEquals("gc-1", v.groupConversationId());
    }

    // ─── Response extraction ───

    @Test
    void extractSlackResponseText_nullSnapshot_placeholder() {
        assertEquals("_No response from agent._", SlackHitlSupport.extractSlackResponseText(null));
    }

    @Test
    void extractSlackResponseText_emptyOutputs_placeholder() {
        var snapshot = new SimpleConversationMemorySnapshot();
        assertEquals("_No response from agent._", SlackHitlSupport.extractSlackResponseText(snapshot));
    }

    @Test
    void extractSlackResponseText_nestedOutputArray_extractsText() {
        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("output", List.of(Map.of("text", "Hello from agent")));
        snapshot.setConversationOutputs(List.of(output));

        assertEquals("Hello from agent", SlackHitlSupport.extractSlackResponseText(snapshot));
    }

    @Test
    void extractSlackResponseText_noTextOutput_placeholder() {
        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("actions", List.of("some_action"));
        snapshot.setConversationOutputs(List.of(output));

        assertEquals("_Agent completed but produced no text output._",
                SlackHitlSupport.extractSlackResponseText(snapshot));
    }
}
