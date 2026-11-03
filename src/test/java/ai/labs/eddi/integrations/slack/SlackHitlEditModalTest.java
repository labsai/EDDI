/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore;
import ai.labs.eddi.integrations.slack.hitl.InMemorySlackApprovalRecordStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Slack — Edit & approve and Reject with reason")
class SlackHitlEditModalTest {

    private static final String INT_NAME = "acme-int";
    private static final Instant PAUSED_AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final String PAUSE_ID = HitlDecision.pauseIdOf(PAUSED_AT);

    private static ConversationMemorySnapshot ruleSnapshot(String draft, boolean editable) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPausedAt(PAUSED_AT);
        snapshot.setHitlPauseType("RULE");
        var candidate = new HitlRevisionCandidate();
        candidate.setDraft(draft);
        candidate.setEditable(editable);
        var run = new WorkflowRunSnapshot();
        run.setLifecycleTasks(List.of(new ResultSnapshot(HitlRevisionCandidate.STEP_KEY, candidate.toMap(), null, new Date(), "wf", false)));
        var step = new ConversationStepSnapshot();
        step.setWorkflows(List.of(run));
        snapshot.setConversationSteps(List.of(step));
        return snapshot;
    }

    private static PendingToolCallBatch.PendingToolCall call(String id, String raw, String redacted) {
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setCallId(id);
        call.setToolName("post_comment");
        call.setArgumentsRaw(raw);
        call.setArgumentsRedacted(redacted);
        return call;
    }

    private static ConversationMemorySnapshot toolSnapshot(PendingToolCallBatch.PendingToolCall... calls) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPausedAt(PAUSED_AT);
        snapshot.setHitlPauseType("TOOL_CALL");
        var batch = new PendingToolCallBatch();
        batch.setCalls(List.of(calls));
        snapshot.setHitlPendingToolCalls(batch);
        return snapshot;
    }

    @Nested
    @DisplayName("which card gets an edit button")
    class Card {

        @Test
        void singleToolCallIsEditable() {
            var snapshot = toolSnapshot(call("c1", "{\"body\":\"hi\"}", "{\"body\":\"hi\"}"));
            var edits = SlackHitlEditModal.forCard("TOOL_CALL", snapshot.getHitlPendingToolCalls(), snapshot, Map.of());
            assertTrue(edits.edit());
            assertTrue(edits.rejectWithReason());
        }

        @Test
        void severalCallsOrTooLongAreNot() {
            var two = toolSnapshot(call("c1", "{}", "{}"), call("c2", "{}", "{}"));
            assertFalse(SlackHitlEditModal.forCard("TOOL_CALL", two.getHitlPendingToolCalls(), two, Map.of()).edit());
            String big = "{\"body\":\"" + "x".repeat(3001) + "\"}";
            var tooLong = toolSnapshot(call("c1", big, big));
            assertFalse(SlackHitlEditModal.forCard("TOOL_CALL", tooLong.getHitlPendingToolCalls(), tooLong, Map.of()).edit(),
                    "the 3000-character gate hides the edit button");
            var truncated = call("c1", "{}", "{}");
            truncated.setArgsTruncated(true);
            var truncatedSnapshot = toolSnapshot(truncated);
            assertFalse(SlackHitlEditModal.forCard("TOOL_CALL", truncatedSnapshot.getHitlPendingToolCalls(), truncatedSnapshot, Map.of()).edit());
        }

        @Test
        void ruleDraftNeedsTheOptInAndEditsAllowed() {
            var snapshot = ruleSnapshot("The release is Friday.", true);
            var withoutOptIn = SlackHitlEditModal.forCard("RULE", null, snapshot, Map.of());
            assertFalse(withoutOptIn.edit(), "RULE drafts reach Slack only with hitlShareRuleDrafts");
            assertNotNull(withoutOptIn.hint());
            assertTrue(SlackHitlEditModal.forCard("RULE", null, snapshot, Map.of(SlackHitlEditModal.CFG_HITL_SHARE_RULE_DRAFTS, "true")).edit());
            assertFalse(SlackHitlEditModal.forCard("RULE", null, ruleSnapshot("draft", false),
                    Map.of(SlackHitlEditModal.CFG_HITL_SHARE_RULE_DRAFTS, "true")).edit(), "edits not allowed by the agent");
            assertFalse(SlackHitlEditModal.forCard("RULE", null, ruleSnapshot("{\"password\":\"fixturefixture\"}", true),
                    Map.of(SlackHitlEditModal.CFG_HITL_SHARE_RULE_DRAFTS, "true")).edit(), "a draft with a secret stays out of Slack");
        }

        @Test
        void buttonsKeepTheActionsBlockLast() {
            var blocks = SlackHitlSupport.buildApprovalBlocks("t", "Conversation", "c1", "a", "r", null, "v", true, "TOOL_CALL", null,
                    new SlackHitlEditModal.CardEdits(true, true, "hint"));
            Map<String, Object> last = blocks.get(blocks.size() - 1);
            assertEquals("actions", last.get("type"));
            @SuppressWarnings("unchecked")
            var elements = (List<Map<String, Object>>) last.get("elements");
            assertEquals(List.of("hitl_approve", "hitl_edit", "hitl_reject", "hitl_reject_reason"),
                    elements.stream().map(e -> e.get("action_id")).toList());
            var legacy = SlackHitlSupport.buildApprovalBlocks("t", "Conversation", "c1", "a", "r", null, "v", true, "RULE", null);
            @SuppressWarnings("unchecked")
            var legacyElements = (List<Map<String, Object>>) legacy.get(legacy.size() - 1).get("elements");
            assertEquals(2, legacyElements.size(), "the old signature renders the pre-6.7 card");
        }
    }

    @Nested
    @DisplayName("redaction-aware merge of edited arguments")
    class Merge {

        @Test
        @DisplayName("an object or array cannot be submitted in place of a redacted text value")
        void containerInPlaceOfRedactedLeaf() {
            String raw = "{\"token\":\"real-secret\",\"body\":\"hi\"}";
            String shown = "{\"token\":\"<REDACTED>\",\"body\":\"hi\"}";
            assertThrows(SlackRedactedArgumentsMerge.MergeException.class,
                    () -> SlackRedactedArgumentsMerge.merge(raw, shown, "{\"token\":{\"a\":1},\"body\":\"hi\"}"));
            assertThrows(SlackRedactedArgumentsMerge.MergeException.class,
                    () -> SlackRedactedArgumentsMerge.merge(raw, shown, "{\"token\":[\"x\"],\"body\":\"hi\"}"));
        }

        @Test
        void unchangedRedactedLeafKeepsTheRawValue() throws Exception {
            String merged = SlackRedactedArgumentsMerge.merge("{\"token\":\"real-secret\",\"body\":\"hi\"}",
                    "{\"token\":\"<REDACTED>\",\"body\":\"hi\"}", "{\"token\":\"<REDACTED>\",\"body\":\"hello\"}");
            assertEquals("{\"token\":\"real-secret\",\"body\":\"hello\"}", merged);
        }

        @Test
        void aRedactedLeafCannotBeChanged() {
            var e = assertThrows(SlackRedactedArgumentsMerge.MergeException.class, () -> SlackRedactedArgumentsMerge.merge(
                    "{\"token\":\"real-secret\"}", "{\"token\":\"<REDACTED>\"}", "{\"token\":\"other\"}"));
            assertTrue(e.getMessage().contains("$.token"));
        }

        @Test
        void anArrayWithRedactedItemsCannotChangeLength() throws Exception {
            String raw = "{\"keys\":[\"s1\",\"s2\"]}";
            String shown = "{\"keys\":[\"<REDACTED>\",\"<REDACTED>\"]}";
            var e = assertThrows(SlackRedactedArgumentsMerge.MergeException.class,
                    () -> SlackRedactedArgumentsMerge.merge(raw, shown, "{\"keys\":[\"<REDACTED>\"]}"));
            assertTrue(e.getMessage().contains("$.keys"));
            // same length still merges
            assertEquals(raw, SlackRedactedArgumentsMerge.merge(raw, shown, shown));
        }

        @Test
        void aSecretBearingArrayItemCannotChangeItsVisibleIdentity() throws Exception {
            String raw = "{\"servers\":[{\"name\":\"A\",\"token\":\"tokA\"},{\"name\":\"B\",\"token\":\"tokB\"}]}";
            String shown = "{\"servers\":[{\"name\":\"A\",\"token\":\"<REDACTED>\"},{\"name\":\"B\",\"token\":\"<REDACTED>\"}]}";
            String renamed = "{\"servers\":[{\"name\":\"C\",\"token\":\"<REDACTED>\"},{\"name\":\"B\",\"token\":\"<REDACTED>\"}]}";
            var e = assertThrows(SlackRedactedArgumentsMerge.MergeException.class, () -> SlackRedactedArgumentsMerge.merge(raw, shown, renamed));
            assertTrue(e.getMessage().contains("$.servers[0]"));
            // left as shown, the secrets are restored
            assertEquals(raw, SlackRedactedArgumentsMerge.merge(raw, shown, shown));
        }

        @Test
        void aNewMarkerIsRefused() {
            assertThrows(SlackRedactedArgumentsMerge.MergeException.class, () -> SlackRedactedArgumentsMerge.merge("{\"a\":\"x\"}",
                    "{\"a\":\"x\"}", "{\"a\":\"x\",\"b\":\"<REDACTED>\"}"));
            assertThrows(SlackRedactedArgumentsMerge.MergeException.class,
                    () -> SlackRedactedArgumentsMerge.merge("{}", "{}", "not json"));
            assertThrows(SlackRedactedArgumentsMerge.MergeException.class, () -> SlackRedactedArgumentsMerge.merge("{}", "{}", "[1]"));
        }
    }

    @Nested
    @DisplayName("the interaction flow")
    class Flow {

        private ChannelTargetRouter router;
        private IConversationService conversationService;
        private SlackWebApiClient slackApi;
        private InMemorySlackApprovalRecordStore records;
        private SlackInteractivityHandler handler;
        private String cardId;
        private final ObjectMapper mapper = new ObjectMapper();

        @BeforeEach
        void setUp() throws Exception {
            router = mock(ChannelTargetRouter.class);
            conversationService = mock(IConversationService.class);
            slackApi = mock(SlackWebApiClient.class);
            records = new InMemorySlackApprovalRecordStore();
            handler = new SlackInteractivityHandler(router, conversationService, mock(IGroupConversationService.class), slackApi, mapper,
                    records);
            cardId = ISlackApprovalRecordStore.newCardId();
            records.tryRecord(INT_NAME, "conv-1", ISlackApprovalRecordStore.pauseEpochOf(PAUSED_AT), cardId, "C_APPROVAL");
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setName(INT_NAME);
            cfg.setChannelType("slack");
            var pc = new HashMap<String, String>();
            pc.put("botToken", "xoxb-token");
            pc.put("signingSecret", "owning-secret");
            pc.put(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS, "U_APPROVER");
            pc.put(SlackHitlEditModal.CFG_HITL_SHARE_RULE_DRAFTS, "true");
            cfg.setPlatformConfig(pc);
            when(router.getIntegrationByName("slack", INT_NAME)).thenReturn(Optional.of(cfg));
        }

        private String buttonPayload(String user, String actionId) {
            return """
                    {"type":"block_actions","trigger_id":"trig-1","user":{"id":"%s"},"channel":{"id":"C_APPROVAL"},
                     "message":{"ts":"1700000000.000100"},"actions":[{"action_id":"%s","value":"%s"}]}
                    """.formatted(user, actionId, SlackHitlSupport.buildActionValue(INT_NAME, "conv-1", cardId));
        }

        private String submission(String user, String callbackId, String pauseId, String callId, String edit, String reason) throws Exception {
            var metadata = new SlackHitlEditModal.Metadata(INT_NAME, "conv-1", cardId, pauseId, "C_APPROVAL", "1700000000.000100", callId);
            var values = new HashMap<String, Object>();
            if (edit != null) {
                values.put("edit", Map.of("value", Map.of("value", edit)));
            }
            if (reason != null) {
                values.put("reason", Map.of("value", Map.of("selected_option", Map.of("value", reason))));
            }
            var payload = Map.of("type", "view_submission", "user", Map.of("id", user),
                    "view", Map.of("callback_id", callbackId, "private_metadata", metadata.encode(), "state", Map.of("values", values)));
            return mapper.writeValueAsString(payload);
        }

        @Test
        @DisplayName("Edit & approve opens a modal prefilled with the draft")
        void opensTheEditModal() throws Exception {
            when(conversationService.getConversationMemorySnapshot("conv-1")).thenReturn(ruleSnapshot("The release is Friday.", true));

            handler.handlePayload(buttonPayload("U_APPROVER", SlackHitlEditModal.ACTION_EDIT));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> view = ArgumentCaptor.forClass(Map.class);
            verify(slackApi).openView(eq("Bearer xoxb-token"), eq("trig-1"), view.capture());
            assertEquals(SlackHitlEditModal.CALLBACK_EDIT, view.getValue().get("callback_id"));
            assertTrue(view.getValue().toString().contains("The release is Friday."));
            var metadata = SlackHitlEditModal.Metadata.decode((String) view.getValue().get("private_metadata"));
            assertEquals(PAUSE_ID, metadata.pauseId(), "the modal is bound to the pause");
        }

        @Test
        @DisplayName("a non-approver can neither open nor submit")
        void nonApprover() throws Exception {
            when(conversationService.getConversationMemorySnapshot("conv-1")).thenReturn(ruleSnapshot("draft", true));

            handler.handlePayload(buttonPayload("U_STRANGER", SlackHitlEditModal.ACTION_EDIT));
            verify(slackApi, never()).openView(any(), any(), any());

            String submit = submission("U_STRANGER", SlackHitlEditModal.CALLBACK_EDIT, PAUSE_ID, null, "Better", null);
            assertNotNull(handler.validateSubmission(submit), "refused synchronously");
            handler.handlePayload(submit);
            verify(conversationService, never()).resumeConversation(anyString(), any(), any());
        }

        @Test
        @DisplayName("the signing secret of a submission comes from private_metadata")
        void secretFromMetadata() throws Exception {
            assertEquals("owning-secret", handler.resolveSigningSecretForDecision(
                    submission("U_APPROVER", SlackHitlEditModal.CALLBACK_EDIT, PAUSE_ID, null, "x", null)));
        }

        @Test
        @DisplayName("a valid RULE edit resumes with editedAnswer, attributed to the Slack user, and the card says so")
        void ruleEditResumes() throws Exception {
            when(conversationService.getConversationMemorySnapshot("conv-1")).thenReturn(ruleSnapshot("draft", true));
            String submit = submission("U_APPROVER", SlackHitlEditModal.CALLBACK_EDIT, PAUSE_ID, null, "The release is Monday.", "FACTUAL_ERROR");

            assertNull(handler.validateSubmission(submit));
            handler.handlePayload(submit);

            var decision = ArgumentCaptor.forClass(HitlDecision.class);
            verify(conversationService).resumeConversation(eq("conv-1"), decision.capture(), isNull());
            assertEquals("The release is Monday.", decision.getValue().getEditedAnswer());
            assertEquals("FACTUAL_ERROR", decision.getValue().getReasonCode());
            assertEquals("slack:U_APPROVER", decision.getValue().getDecidedBy());
            assertEquals(PAUSE_ID, decision.getValue().getPauseId());
            verify(slackApi).updateMessage(eq("Bearer xoxb-token"), eq("C_APPROVAL"), eq("1700000000.000100"),
                    eq("✅ Approved (edit submitted) by <@U_APPROVER>"), any());
        }

        @Test
        @DisplayName("an engine refusal comes back synchronously as response errors")
        void engineRefusal() throws Exception {
            when(conversationService.getConversationMemorySnapshot("conv-1")).thenReturn(ruleSnapshot("draft", true));
            doThrow(new IllegalArgumentException("editedAnswer has 9000 characters; at most 16 are allowed"))
                    .when(conversationService).validateHitlDecision(eq("conv-1"), any());

            Map<String, String> errors = handler.validateSubmission(
                    submission("U_APPROVER", SlackHitlEditModal.CALLBACK_EDIT, PAUSE_ID, null, "x", null));

            assertTrue(errors.get("edit").contains("at most"));
        }

        @Test
        @DisplayName("a tool edit is merged against the raw arguments server-side")
        void toolEdit() throws Exception {
            when(conversationService.getConversationMemorySnapshot("conv-1")).thenReturn(toolSnapshot(
                    call("call-1", "{\"token\":\"real-secret\",\"body\":\"hi\"}", "{\"token\":\"<REDACTED>\",\"body\":\"hi\"}")));
            String submit = submission("U_APPROVER", SlackHitlEditModal.CALLBACK_EDIT, PAUSE_ID, "call-1",
                    "{\"token\":\"<REDACTED>\",\"body\":\"hello\"}", null);

            handler.handlePayload(submit);

            var decision = ArgumentCaptor.forClass(HitlDecision.class);
            verify(conversationService).resumeConversation(eq("conv-1"), decision.capture(), isNull());
            assertEquals("{\"token\":\"real-secret\",\"body\":\"hello\"}",
                    decision.getValue().getToolDecisions().get("call-1").getAmendedArguments());
        }

        @Test
        @DisplayName("reject with reason records the code and shows it on the card")
        void rejectWithReason() throws Exception {
            when(conversationService.getConversationMemorySnapshot("conv-1")).thenReturn(ruleSnapshot("draft", true));

            handler.handlePayload(submission("U_APPROVER", SlackHitlEditModal.CALLBACK_REJECT, PAUSE_ID, null, null, "TONE"));

            var decision = ArgumentCaptor.forClass(HitlDecision.class);
            verify(conversationService).resumeConversation(eq("conv-1"), decision.capture(), isNull());
            assertEquals(HitlVerdict.REJECTED, decision.getValue().getVerdict());
            assertEquals("TONE", decision.getValue().getReasonCode());
            verify(slackApi).updateMessage(any(), any(), any(), eq("⛔ Rejected (TONE) by <@U_APPROVER>"), any());
        }

        @Test
        @DisplayName("a submission for an earlier pause is not applied")
        void stalePause() throws Exception {
            when(conversationService.getConversationMemorySnapshot("conv-1")).thenReturn(ruleSnapshot("draft", true));

            handler.handlePayload(submission("U_APPROVER", SlackHitlEditModal.CALLBACK_EDIT, "12345", null, "x", null));

            verify(conversationService, never()).resumeConversation(anyString(), any(), any());
        }
    }
}
