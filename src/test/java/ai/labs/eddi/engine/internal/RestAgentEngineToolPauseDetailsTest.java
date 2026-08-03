/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the structured {@code pauseDetails} object added to
 * {@link RestAgentEngine#getApprovalStatus(String, String)} (Task 11).
 */
class RestAgentEngineToolPauseDetailsTest {

    private static final String CONVERSATION_ID = "conv-pause-details";
    private static final String USER_ID = "test-user";
    private static final int AGENT_TIMEOUT = 30;
    private static final String RAW_SECRET = "sk-super-secret-raw-value";

    @Mock
    private IConversationService conversationService;
    @Mock
    private IConversationDescriptorStore conversationDescriptorStore;
    @Mock
    private SecurityIdentity identity;
    @Mock
    private OwnershipValidator ownershipValidator;
    @Mock
    private Principal principal;
    @Mock
    private IHitlToolJournalStore hitlToolJournalStore;

    private RestAgentEngine restAgentEngine;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        var descriptor = new ConversationDescriptor();
        descriptor.setUserId(USER_ID);
        doReturn(descriptor).when(conversationDescriptorStore).readDescriptor(anyString(), anyInt());
        doNothing().when(ownershipValidator).requireOwnerOrAdmin(any(), any(), any());

        doReturn(principal).when(identity).getPrincipal();
        doReturn(USER_ID).when(principal).getName();

        var hitlAccessGuard = new HitlAccessGuard(
                identity, ownershipValidator, conversationDescriptorStore, conversationService,
                mock(ai.labs.eddi.engine.api.IGroupConversationService.class));
        var conversationAccessGuard = new ConversationAccessGuard(
                identity, ownershipValidator, conversationDescriptorStore);
        restAgentEngine = new RestAgentEngine(
                conversationService, mock(IConversationMemoryStore.class), identity, ownershipValidator,
                conversationAccessGuard, hitlAccessGuard, hitlToolJournalStore, AGENT_TIMEOUT);
    }

    private ConversationMemorySnapshot snapshotInState(ConversationState state) throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(state);
        doReturn(snapshot).when(conversationService).getConversationMemorySnapshot(CONVERSATION_ID);
        return snapshot;
    }

    private PendingToolCall toolCall(String callId, String toolName, String source,
                                     String argumentsRaw, String argumentsRedacted,
                                     boolean argsTruncated, String gateReason) {
        var call = new PendingToolCall();
        call.setCallId(callId);
        call.setToolName(toolName);
        call.setSource(source);
        call.setArgumentsRaw(argumentsRaw);
        call.setArgumentsRedacted(argumentsRedacted);
        call.setArgsTruncated(argsTruncated);
        call.setGateReason(gateReason);
        return call;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryOf(Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    private PendingToolCallBatch.ResolvedRequestPreview preview(String method, String uri, String body) {
        var preview = new PendingToolCallBatch.ResolvedRequestPreview();
        preview.setMethod(method);
        preview.setUri(uri);
        preview.setQueryParams(Map.of("version", "1"));
        preview.setHeaders(Map.of("Authorization", "<REDACTED>"));
        preview.setBody(body);
        preview.setBodyTruncated(false);
        return preview;
    }

    @Nested
    @DisplayName("pauseDetails — TOOL_CALL")
    class ToolCallPauseDetails {

        @Test
        @DisplayName("carries redacted args only — the raw argumentsRaw value never appears in the response")
        void redactedArgsOnlyNeverRawValue() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("TOOL_CALL");

            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            batch.setCalls(List.of(
                    toolCall("call-1", "sendEmail", "mcp", RAW_SECRET, "{\"to\":\"[REDACTED]\"}", false, "mcp:*")));
            batch.setExecutedUngatedCallNames(List.of("getCurrentDateTime"));
            snapshot.setHitlPendingToolCalls(batch);

            doReturn(Optional.empty()).when(hitlToolJournalStore).find(anyString(), anyString(), anyString());

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            assertEquals(200, response.getStatus());
            Map<String, Object> summary = summaryOf(response);
            String serialized = summary.toString();

            assertFalse(serialized.contains(RAW_SECRET),
                    "Raw tool-call arguments must NEVER appear in the approval-status response");

            var pauseDetails = (Map<String, Object>) summary.get("pauseDetails");
            assertNotNull(pauseDetails, "pauseDetails must be present for a TOOL_CALL pause");
            assertEquals("TOOL_CALL", pauseDetails.get("type"));

            var calls = (List<Map<String, Object>>) pauseDetails.get("calls");
            assertEquals(1, calls.size());
            var call = calls.get(0);
            assertEquals("call-1", call.get("callId"));
            assertEquals("sendEmail", call.get("toolName"));
            assertEquals("mcp", call.get("source"));
            assertEquals("{\"to\":\"[REDACTED]\"}", call.get("arguments"));
            assertEquals(false, call.get("argsTruncated"));
            assertEquals("mcp:*", call.get("gateReason"));

            assertEquals(List.of("getCurrentDateTime"), pauseDetails.get("executedUngatedCalls"));
        }

        @Test
        @DisplayName("a pinned http call exposes its resolved-request preview, so an approver sees the real request")
        void pinnedCallExposesRequestPreview() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("TOOL_CALL");

            var call = toolCall("call-1", "deployAgent", "http", RAW_SECRET, "{}", false, "http.post:*");
            call.setRequestFingerprint("deadbeef");
            call.setRequestPreview(preview("POST", "https://eddi.example/administration/production/deploy/a1", "{}"));

            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            batch.setCalls(List.of(call));
            snapshot.setHitlPendingToolCalls(batch);
            doReturn(Optional.empty()).when(hitlToolJournalStore).find(anyString(), anyString(), anyString());

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            var pauseDetails = (Map<String, Object>) summaryOf(response).get("pauseDetails");
            var callView = ((List<Map<String, Object>>) pauseDetails.get("calls")).get(0);

            assertEquals(true, callView.get("requestPinned"));
            var previewView = (Map<String, Object>) callView.get("requestPreview");
            assertNotNull(previewView, "a pinned call must expose its preview");
            assertEquals("POST", previewView.get("method"));
            assertEquals("https://eddi.example/administration/production/deploy/a1", previewView.get("uri"));
            assertEquals(Map.of("version", "1"), previewView.get("queryParams"));
            assertEquals(Map.of("Authorization", "<REDACTED>"), previewView.get("headers"));
            assertEquals("{}", previewView.get("body"));
            assertEquals(false, previewView.get("bodyTruncated"));
        }

        @Test
        @DisplayName("an unpinned call (every non-http tool) has no preview, honestly, rather than a fabricated one")
        void unpinnedCallHasNoPreview() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("TOOL_CALL");

            var call = toolCall("call-1", "sendEmail", "mcp", RAW_SECRET, "{\"to\":\"[REDACTED]\"}", false, "mcp:*");
            // requestFingerprint / requestPreview left unset, exactly as the gate
            // leaves them for a non-http tool.

            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            batch.setCalls(List.of(call));
            snapshot.setHitlPendingToolCalls(batch);
            doReturn(Optional.empty()).when(hitlToolJournalStore).find(anyString(), anyString(), anyString());

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            var pauseDetails = (Map<String, Object>) summaryOf(response).get("pauseDetails");
            var callView = ((List<Map<String, Object>>) pauseDetails.get("calls")).get(0);

            assertEquals(false, callView.get("requestPinned"));
            assertNull(callView.get("requestPreview"));
        }

        @Test
        @DisplayName("the fingerprint itself never appears in the response — it is an internal comparison value, not approver-facing")
        void fingerprintNeverAppearsInResponse() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("TOOL_CALL");

            var call = toolCall("call-1", "deployAgent", "http", RAW_SECRET, "{}", false, "http.post:*");
            String secretFingerprint = "fingerprint-must-not-leak-abc123";
            call.setRequestFingerprint(secretFingerprint);
            call.setRequestPreview(preview("POST", "https://eddi.example/deploy/a1", "{}"));

            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            batch.setCalls(List.of(call));
            snapshot.setHitlPendingToolCalls(batch);
            doReturn(Optional.empty()).when(hitlToolJournalStore).find(anyString(), anyString(), anyString());

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            assertFalse(summaryOf(response).toString().contains(secretFingerprint),
                    "the raw fingerprint value must never appear in the approval-status response");
        }

        @Test
        @DisplayName("detail=full strips the fingerprint too — it digests the RAW body the preview redacts")
        void fingerprintNeverAppearsInTheFullSnapshot() throws Exception {
            // The gap the summary test above did NOT cover: detail=full returns the
            // whole snapshot object, and the getter carries no @JsonIgnore (it cannot
            // — the persistence mapper shares the same configuration, so ignoring it
            // would drop the field from the stored document and disable pinning).
            // Without a read-time strip, an approver received a SHA-256 over a
            // canonical string containing the raw body and raw query values — i.e.
            // exactly the credential material RequestRedactor removed from the
            // preview sitting beside it.
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("TOOL_CALL");

            var call = toolCall("call-1", "deployAgent", "http", RAW_SECRET, "{}", false, "http.post:*");
            String secretFingerprint = "fingerprint-must-not-leak-abc123";
            call.setRequestFingerprint(secretFingerprint);
            call.setRequestPreview(preview("POST", "https://eddi.example/deploy/a1", "{}"));

            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            batch.setCalls(List.of(call));
            snapshot.setHitlPendingToolCalls(batch);

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "full");

            var returned = (ConversationMemorySnapshot) response.getEntity();
            var returnedCall = returned.getHitlPendingToolCalls().getCalls().getFirst();
            assertNotEquals(secretFingerprint, returnedCall.getRequestFingerprint(),
                    "detail=full must not carry the real request fingerprint");
            // The approver still gets everything they need to decide — stripping the
            // digest must not cost them the preview it was derived from.
            assertNotNull(returnedCall.getRequestPreview(), "the redacted request preview must survive the strip");
            // And must not cost them the PINNED signal either. isRequestPinned() is
            // derived from the fingerprint field, so clearing it outright silently
            // reported every pinned call as unpinned — telling the approver the
            // request is not re-checked before execution when it is, and
            // contradicting what detail=summary says about the same conversation.
            assertTrue(returnedCall.isRequestPinned(),
                    "stripping the digest must not flip the documented requestPinned contract field");
        }

        @Test
        @DisplayName("no journal entries → outcomeUnknown is empty")
        void noJournalEntriesMeansEmptyOutcomeUnknown() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("TOOL_CALL");
            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            batch.setCalls(List.of(toolCall("call-1", "sendEmail", "mcp", RAW_SECRET, "redacted", false, "mcp:*")));
            snapshot.setHitlPendingToolCalls(batch);

            doReturn(Optional.empty()).when(hitlToolJournalStore).find(anyString(), anyString(), anyString());

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            var pauseDetails = (Map<String, Object>) summaryOf(response).get("pauseDetails");
            assertEquals(List.of(), pauseDetails.get("outcomeUnknown"));
        }

        @Test
        @DisplayName("an EXECUTING journal entry for a callId surfaces it in outcomeUnknown")
        void executingJournalEntrySurfacesOutcomeUnknown() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("TOOL_CALL");
            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            batch.setCalls(List.of(
                    toolCall("call-1", "sendEmail", "mcp", RAW_SECRET, "redacted-1", false, "mcp:*"),
                    toolCall("call-2", "chargeCard", "http", RAW_SECRET, "redacted-2", false, "http:*")));
            snapshot.setHitlPendingToolCalls(batch);

            doReturn(Optional.of(new IHitlToolJournalStore.JournalEntry(
                    CONVERSATION_ID, "epoch-1", "call-1", "sendEmail",
                    IHitlToolJournalStore.Status.EXECUTING, null, null, "reviewer-1")))
                    .when(hitlToolJournalStore).find(CONVERSATION_ID, "epoch-1", "call-1");
            doReturn(Optional.empty()).when(hitlToolJournalStore).find(CONVERSATION_ID, "epoch-1", "call-2");

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            var pauseDetails = (Map<String, Object>) summaryOf(response).get("pauseDetails");
            assertEquals(List.of("call-1"), pauseDetails.get("outcomeUnknown"));
        }
    }

    @Nested
    @DisplayName("pauseDetails — RULE")
    class RulePauseDetails {

        @Test
        @DisplayName("RULE snapshot → type RULE with reason + actions of the paused step")
        void rulePauseHasReasonAndActions() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("RULE");
            snapshot.setHitlPauseReason("needs manager sign-off");

            var step = new ConversationMemorySnapshot.ConversationStepSnapshot();
            var workflow = new ConversationMemorySnapshot.WorkflowRunSnapshot();
            var actionsResult = new ConversationMemorySnapshot.ResultSnapshot(
                    "actions", List.of("PAUSE_CONVERSATION", "notify_manager"), null, null, "wf-1", true);
            workflow.getLifecycleTasks().add(actionsResult);
            step.getWorkflows().add(workflow);
            snapshot.getConversationSteps().add(step);

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            var pauseDetails = (Map<String, Object>) summaryOf(response).get("pauseDetails");
            assertNotNull(pauseDetails);
            assertEquals("RULE", pauseDetails.get("type"));
            assertEquals("needs manager sign-off", pauseDetails.get("reason"));
            assertEquals(List.of("PAUSE_CONVERSATION", "notify_manager"), pauseDetails.get("actions"));
        }

        @Test
        @DisplayName("with multiple steps, actions come from the MOST RECENT step "
                + "(getConversationSteps() is reverse-chronological — index 0 is newest)")
        void rulePauseUsesMostRecentStepAmongMultiple() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("RULE");
            snapshot.setHitlPauseReason("needs manager sign-off");

            // Index 0 = most recent step (matches
            // ConversationMemoryUtilities.convertConversationMemory's reverse
            // insertion order) — this is the one whose actions must win.
            var newestStep = new ConversationMemorySnapshot.ConversationStepSnapshot();
            var newestWorkflow = new ConversationMemorySnapshot.WorkflowRunSnapshot();
            newestWorkflow.getLifecycleTasks().add(new ConversationMemorySnapshot.ResultSnapshot(
                    "actions", List.of("PAUSE_CONVERSATION", "notify_manager"), null, null, "wf-2", true));
            newestStep.getWorkflows().add(newestWorkflow);

            var olderStep = new ConversationMemorySnapshot.ConversationStepSnapshot();
            var olderWorkflow = new ConversationMemorySnapshot.WorkflowRunSnapshot();
            olderWorkflow.getLifecycleTasks().add(new ConversationMemorySnapshot.ResultSnapshot(
                    "actions", List.of("some_older_action"), null, null, "wf-1", true));
            olderStep.getWorkflows().add(olderWorkflow);

            snapshot.getConversationSteps().add(newestStep);
            snapshot.getConversationSteps().add(olderStep);

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            var pauseDetails = (Map<String, Object>) summaryOf(response).get("pauseDetails");
            assertEquals(List.of("PAUSE_CONVERSATION", "notify_manager"), pauseDetails.get("actions"),
                    "must use the newest step's actions, not the oldest");
        }

        @Test
        @DisplayName("within a step, the LAST actions entry wins (matches IConversationStep.getLatestData semantics)")
        void rulePauseUsesLastActionsEntryWithinStep() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType("RULE");

            var step = new ConversationMemorySnapshot.ConversationStepSnapshot();
            var workflow = new ConversationMemorySnapshot.WorkflowRunSnapshot();
            // Same step can accumulate multiple "actions" writes as the pipeline
            // progresses (e.g. behavior rules re-evaluated) — the LAST one written
            // reflects the actual paused state.
            workflow.getLifecycleTasks().add(new ConversationMemorySnapshot.ResultSnapshot(
                    "actions", List.of("first_write"), null, null, "wf-1", true));
            workflow.getLifecycleTasks().add(new ConversationMemorySnapshot.ResultSnapshot(
                    "actions", List.of("PAUSE_CONVERSATION"), null, null, "wf-1", true));
            step.getWorkflows().add(workflow);
            snapshot.getConversationSteps().add(step);

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            var pauseDetails = (Map<String, Object>) summaryOf(response).get("pauseDetails");
            assertEquals(List.of("PAUSE_CONVERSATION"), pauseDetails.get("actions"));
        }

        @Test
        @DisplayName("legacy snapshot with null pauseType → treated as RULE")
        void legacyNullPauseTypeTreatedAsRule() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);
            snapshot.setHitlPauseType(null);
            snapshot.setHitlPauseReason("legacy pause");

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            var pauseDetails = (Map<String, Object>) summaryOf(response).get("pauseDetails");
            assertNotNull(pauseDetails);
            assertEquals("RULE", pauseDetails.get("type"));
            assertEquals("legacy pause", pauseDetails.get("reason"));
        }

        @Test
        @DisplayName("not paused → pauseDetails is absent/null")
        void notPausedMeansNoPauseDetails() throws Exception {
            snapshotInState(ConversationState.READY);

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            Map<String, Object> summary = summaryOf(response);
            assertNull(summary.get("pauseDetails"), "A non-paused conversation must not expose pauseDetails");
        }
    }
}
