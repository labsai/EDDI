/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore.ResourceModifiedException;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.CancelOutcome;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Additive branch-coverage tests for {@link McpHitlTools}. Each test drives one
 * previously-uncovered branch (error path / catch block / guard) and asserts on
 * the observable JSON errorCode (or status). No production code is touched.
 */
class McpHitlToolsCoverageTest {

    IConversationService conversationService;
    IGroupConversationService groupConversationService;
    HitlAccessGuard guard;
    OwnershipValidator ownershipValidator;
    IJsonSerialization json;
    SecurityIdentity identity;
    McpHitlTools tools;

    McpHitlTools build(boolean authEnabled, boolean mutationsEnabled) {
        return new McpHitlTools(conversationService, groupConversationService, guard,
                ownershipValidator, json, identity, new SimpleMeterRegistry(),
                authEnabled, mutationsEnabled);
    }

    @BeforeEach
    void setup() {
        conversationService = mock(IConversationService.class);
        groupConversationService = mock(IGroupConversationService.class);
        guard = mock(HitlAccessGuard.class);
        ownershipValidator = mock(OwnershipValidator.class);
        json = mock(IJsonSerialization.class);
        identity = mock(SecurityIdentity.class);
        Principal p = mock(Principal.class);
        lenient().when(p.getName()).thenReturn("alice");
        lenient().when(identity.getPrincipal()).thenReturn(p);
        tools = build(true, true);
    }

    // ==================================================================
    // listPendingApprovals
    // ==================================================================

    @Test
    void listPendingApprovals_guardThrows_returnsInternal() throws Exception {
        when(guard.listScopedPendingApprovals(anyInt())).thenThrow(new RuntimeException("boom"));
        String out = tools.listPendingApprovals("50");
        assertTrue(out.contains("\"errorCode\":\"INTERNAL\""), out);
    }

    @Test
    void listPendingApprovals_nullLimit_usesDefaultAndSucceeds() throws Exception {
        when(guard.listScopedPendingApprovals(anyInt())).thenReturn(java.util.List.of());
        when(json.serialize(any())).thenReturn("[]");
        String out = tools.listPendingApprovals(null);
        assertTrue(out.equals("[]"), out);
    }

    // ==================================================================
    // getApprovalStatus
    // ==================================================================

    @Test
    void getApprovalStatus_blankConversationId_returnsBadRequest() {
        String out = tools.getApprovalStatus("  ", "summary");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void getApprovalStatus_nullConversationId_returnsBadRequest() {
        String out = tools.getApprovalStatus(null, "summary");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void getApprovalStatus_forbidden_returnsForbidden() {
        doThrow(new ForbiddenException("no")).when(guard).requireConversationHitlAccess("c1");
        String out = tools.getApprovalStatus("c1", "summary");
        assertTrue(out.contains("\"errorCode\":\"FORBIDDEN\""), out);
    }

    @Test
    void getApprovalStatus_notFound_returnsNotFound() throws Exception {
        when(guard.requireConversationHitlAccess("c1")).thenReturn("alice");
        when(conversationService.getConversationMemorySnapshot("c1"))
                .thenThrow(new ResourceNotFoundException("gone"));
        String out = tools.getApprovalStatus("c1", "summary");
        assertTrue(out.contains("\"errorCode\":\"NOT_FOUND\""), out);
    }

    @Test
    void getApprovalStatus_genericException_returnsInternal() throws Exception {
        when(guard.requireConversationHitlAccess("c1")).thenReturn("alice");
        when(conversationService.getConversationMemorySnapshot("c1"))
                .thenThrow(new RuntimeException("boom"));
        String out = tools.getApprovalStatus("c1", "summary");
        assertTrue(out.contains("\"errorCode\":\"INTERNAL\""), out);
    }

    @Test
    void getApprovalStatus_detailFull_ownerNotPaused_returnsSnapshot() throws Exception {
        when(guard.requireConversationHitlAccess("c1")).thenReturn("alice");
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY); // not paused
        when(conversationService.getConversationMemorySnapshot("c1")).thenReturn(snapshot);
        when(ownershipValidator.isOwner(identity, "alice")).thenReturn(true); // owner => gate passes
        when(json.serialize(any())).thenReturn("{\"full\":true}");
        String out = tools.getApprovalStatus("c1", "full");
        assertTrue(out.contains("full"), out);
    }

    @Test
    void getApprovalStatus_summary_nullState_serializesEmptyState() throws Exception {
        when(guard.requireConversationHitlAccess("c1")).thenReturn("alice");
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        // conversationState left null => the "" branch of state + not-paused fields
        when(conversationService.getConversationMemorySnapshot("c1")).thenReturn(snapshot);
        when(json.serialize(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        String out = tools.getApprovalStatus("c1", "summary");
        assertTrue(out.contains("conversationId=c1"), out);
    }

    // ==================================================================
    // resumeConversation
    // ==================================================================

    @Test
    void resume_nullConversationId_returnsBadRequest() {
        String out = tools.resumeConversation(null, "APPROVED", null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void resume_nullVerdict_returnsBadRequest() {
        String out = tools.resumeConversation("c1", null, null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void resume_blankVerdict_returnsBadRequest() {
        String out = tools.resumeConversation("c1", "   ", null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void resume_wrongState_lookupThrows_currentStateUnknown() throws Exception {
        doThrow(new IllegalStateException("bad state"))
                .when(conversationService).resumeConversation(eq("c1"), any(), isNull());
        when(conversationService.getConversationState("c1")).thenThrow(new RuntimeException("nope"));
        String out = tools.resumeConversation("c1", "APPROVED", null);
        assertTrue(out.contains("\"errorCode\":\"WRONG_STATE\""), out);
        assertTrue(out.contains("unknown"), out);
    }

    @Test
    void resume_resourceStoreException_returnsInternal() throws Exception {
        doThrow(new ResourceStoreException("db"))
                .when(conversationService).resumeConversation(eq("c1"), any(), isNull());
        String out = tools.resumeConversation("c1", "APPROVED", null);
        assertTrue(out.contains("\"errorCode\":\"INTERNAL\""), out);
    }

    // ==================================================================
    // cancelConversation
    // ==================================================================

    @Test
    void cancel_blankConversationId_returnsBadRequest() {
        String out = tools.cancelConversation("  ");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void cancel_notFoundOutcome_returnsNotFound() throws Exception {
        when(conversationService.cancelConversation(eq("c1"), any(), eq("mcp:alice")))
                .thenReturn(CancelOutcome.NOT_FOUND);
        String out = tools.cancelConversation("c1");
        assertTrue(out.contains("\"errorCode\":\"NOT_FOUND\""), out);
    }

    @Test
    void cancel_nothingToCancelOutcome_returnsWrongState() throws Exception {
        when(conversationService.cancelConversation(eq("c1"), any(), eq("mcp:alice")))
                .thenReturn(CancelOutcome.NOTHING_TO_CANCEL);
        String out = tools.cancelConversation("c1");
        assertTrue(out.contains("\"errorCode\":\"WRONG_STATE\""), out);
    }

    @Test
    void cancel_forbidden_returnsForbidden() {
        doThrow(new ForbiddenException("no")).when(guard).requireConversationHitlAccess("c1");
        String out = tools.cancelConversation("c1");
        assertTrue(out.contains("\"errorCode\":\"FORBIDDEN\""), out);
    }

    @Test
    void cancel_resourceStoreException_returnsInternal() throws Exception {
        when(conversationService.cancelConversation(eq("c1"), any(), eq("mcp:alice")))
                .thenThrow(new ResourceStoreException("db"));
        String out = tools.cancelConversation("c1");
        assertTrue(out.contains("\"errorCode\":\"INTERNAL\""), out);
    }

    @Test
    void cancel_anonymousPrincipal_attributesAnonymous() throws Exception {
        when(identity.getPrincipal()).thenReturn(null); // name resolution => "anonymous"
        when(conversationService.cancelConversation(eq("c1"), any(), eq("mcp:anonymous")))
                .thenReturn(CancelOutcome.CANCELLED);
        String out = tools.cancelConversation("c1");
        assertTrue(out.contains("CANCELLED"), out);
    }

    // ==================================================================
    // listGroupPendingApprovals
    // ==================================================================

    @Test
    void listGroupPending_blankGroupId_returnsBadRequest() {
        String out = tools.listGroupPendingApprovals("  ", "100");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void listGroupPending_nullGroupId_returnsBadRequest() {
        String out = tools.listGroupPendingApprovals(null, "100");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void listGroupPending_guardThrows_returnsInternal() throws Exception {
        when(guard.listScopedGroupPendingApprovals(eq("g1"), anyInt()))
                .thenThrow(new RuntimeException("boom"));
        String out = tools.listGroupPendingApprovals("g1", "100");
        assertTrue(out.contains("\"errorCode\":\"INTERNAL\""), out);
    }

    @Test
    void listGroupPending_happyPath_serializes() throws Exception {
        when(guard.listScopedGroupPendingApprovals(eq("g1"), anyInt())).thenReturn(java.util.List.of());
        when(json.serialize(any())).thenReturn("[]");
        String out = tools.listGroupPendingApprovals("g1", "100");
        assertTrue(out.equals("[]"), out);
    }

    // ==================================================================
    // listAllGroupPendingApprovals
    // ==================================================================

    @Test
    void listAllGroupPending_guardThrows_returnsInternal() throws Exception {
        when(guard.listScopedGroupPendingApprovals(isNull(), anyInt()))
                .thenThrow(new RuntimeException("boom"));
        String out = tools.listAllGroupPendingApprovals("100");
        assertTrue(out.contains("\"errorCode\":\"INTERNAL\""), out);
    }

    // ==================================================================
    // getGroupApprovalStatus
    // ==================================================================

    @Test
    void getGroupApprovalStatus_blankGroupId_returnsBadRequest() {
        String out = tools.getGroupApprovalStatus("  ", "gc1", "summary");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void getGroupApprovalStatus_blankConversationId_returnsBadRequest() {
        String out = tools.getGroupApprovalStatus("g1", "  ", "summary");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void getGroupApprovalStatus_forbidden_returnsForbidden() {
        doThrow(new ForbiddenException("no"))
                .when(guard).requireGroupConversationHitlAccess("g1", "gc1");
        String out = tools.getGroupApprovalStatus("g1", "gc1", "summary");
        assertTrue(out.contains("\"errorCode\":\"FORBIDDEN\""), out);
    }

    @Test
    void getGroupApprovalStatus_jaxrsNotFound_returnsNotFound() throws Exception {
        when(groupConversationService.readGroupConversation("gc1"))
                .thenThrow(new jakarta.ws.rs.NotFoundException("gone"));
        String out = tools.getGroupApprovalStatus("g1", "gc1", "summary");
        assertTrue(out.contains("\"errorCode\":\"NOT_FOUND\""), out);
    }

    @Test
    void getGroupApprovalStatus_resourceNotFound_returnsNotFound() throws Exception {
        when(groupConversationService.readGroupConversation("gc1"))
                .thenThrow(new ResourceNotFoundException("gone"));
        String out = tools.getGroupApprovalStatus("g1", "gc1", "summary");
        assertTrue(out.contains("\"errorCode\":\"NOT_FOUND\""), out);
    }

    @Test
    void getGroupApprovalStatus_genericException_returnsInternal() throws Exception {
        when(groupConversationService.readGroupConversation("gc1"))
                .thenThrow(new RuntimeException("boom"));
        String out = tools.getGroupApprovalStatus("g1", "gc1", "summary");
        assertTrue(out.contains("\"errorCode\":\"INTERNAL\""), out);
    }

    @Test
    void getGroupApprovalStatus_summary_nullState_notPaused_serializesEmpty() throws Exception {
        GroupConversation gc = mock(GroupConversation.class);
        // getState() null => not paused => all paused-guarded fields "" and empty task
        // ids
        when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);
        when(json.serialize(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        String out = tools.getGroupApprovalStatus("g1", "gc1", "summary");
        assertTrue(out.contains("groupConversationId=gc1"), out);
    }

    // ==================================================================
    // approveGroupPhase
    // ==================================================================

    @Test
    void approveGroup_blankGroupId_returnsBadRequest() {
        String out = tools.approveGroupPhase("  ", "gc1", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void approveGroup_blankConversationId_returnsBadRequest() {
        String out = tools.approveGroupPhase("g1", "  ", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void approveGroup_noteTooLong_returnsBadRequest() {
        String longNote = "x".repeat(HitlDecision.MAX_NOTE_LENGTH + 1);
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", longNote, null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void approveGroup_nonStringTaskApprovalValue_returnsBadRequest() throws Exception {
        // deserialize returns a map whose value is not a String => value-type
        // validation fails
        when(json.deserialize(eq("{\"t1\":5}"), eq(Map.class)))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("t1", 5)));
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, "{\"t1\":5}");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
        verifyNoInteractions(groupConversationService);
    }

    @Test
    void approveGroup_blankTaskApprovalValue_returnsBadRequest() throws Exception {
        when(json.deserialize(eq("{\"t1\":\"\"}"), eq(Map.class)))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("t1", "")));
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, "{\"t1\":\"\"}");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
        verifyNoInteractions(groupConversationService);
    }

    @Test
    void approveGroup_forbidden_returnsForbidden() {
        doThrow(new ForbiddenException("no"))
                .when(guard).requireGroupConversationHitlAccess("g1", "gc1");
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"FORBIDDEN\""), out);
    }

    @Test
    void approveGroup_jaxrsNotFound_returnsNotFound() throws Exception {
        when(groupConversationService.resumeDiscussion(eq("gc1"), any(), isNull()))
                .thenThrow(new jakarta.ws.rs.NotFoundException("gone"));
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"NOT_FOUND\""), out);
    }

    @Test
    void approveGroup_resourceModified_returnsConflict() throws Exception {
        when(groupConversationService.resumeDiscussion(eq("gc1"), any(), isNull()))
                .thenThrow(new ResourceModifiedException("modified"));
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"CONFLICT\""), out);
    }

    @Test
    void approveGroup_resourceNotFound_returnsNotFound() throws Exception {
        when(groupConversationService.resumeDiscussion(eq("gc1"), any(), isNull()))
                .thenThrow(new ResourceNotFoundException("gone"));
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"NOT_FOUND\""), out);
    }

    @Test
    void approveGroup_groupConversationGone_returnsNotFound() throws Exception {
        when(groupConversationService.resumeDiscussion(eq("gc1"), any(), isNull()))
                .thenThrow(new GroupConversationGoneException("gone", new RuntimeException()));
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"NOT_FOUND\""), out);
    }

    @Test
    void approveGroup_groupDiscussionException_returnsWrongState() throws Exception {
        when(groupConversationService.resumeDiscussion(eq("gc1"), any(), isNull()))
                .thenThrow(new GroupDiscussionException("not awaiting"));
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"WRONG_STATE\""), out);
    }

    @Test
    void approveGroup_illegalArgument_returnsBadRequest() throws Exception {
        when(groupConversationService.resumeDiscussion(eq("gc1"), any(), isNull()))
                .thenThrow(new IllegalArgumentException("unknown task"));
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void approveGroup_genericException_returnsInternal() throws Exception {
        when(groupConversationService.resumeDiscussion(eq("gc1"), any(), isNull()))
                .thenThrow(new RuntimeException("boom"));
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"INTERNAL\""), out);
    }

    @Test
    void approveGroup_validTaskApprovals_delegatesAndSucceeds() throws Exception {
        when(json.deserialize(eq("{\"t1\":\"APPROVED\"}"), eq(Map.class)))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("t1", "APPROVED")));
        when(groupConversationService.resumeDiscussion(eq("gc1"), any(), isNull()))
                .thenReturn(mock(GroupConversation.class));
        when(json.serialize(any())).thenReturn("{\"ok\":true}");
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, "{\"t1\":\"APPROVED\"}");
        assertTrue(out.contains("ok"), out);
    }

    // ==================================================================
    // cancelGroupDiscussion
    // ==================================================================

    @Test
    void cancelGroup_disabledByKillSwitch_returnsDisabled() {
        tools = build(true, false);
        String out = tools.cancelGroupDiscussion("g1", "gc1");
        assertTrue(out.contains("\"errorCode\":\"DISABLED\""), out);
        verifyNoInteractions(groupConversationService);
    }

    @Test
    void cancelGroup_blankGroupId_returnsBadRequest() {
        String out = tools.cancelGroupDiscussion("  ", "gc1");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void cancelGroup_blankConversationId_returnsBadRequest() {
        String out = tools.cancelGroupDiscussion("g1", "  ");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void cancelGroup_forbidden_returnsForbidden() {
        doThrow(new ForbiddenException("no"))
                .when(guard).requireGroupConversationHitlAccess("g1", "gc1");
        String out = tools.cancelGroupDiscussion("g1", "gc1");
        assertTrue(out.contains("\"errorCode\":\"FORBIDDEN\""), out);
    }

    @Test
    void cancelGroup_jaxrsNotFound_returnsNotFound() throws Exception {
        when(groupConversationService.cancelDiscussion(eq("gc1"), any()))
                .thenThrow(new jakarta.ws.rs.NotFoundException("gone"));
        String out = tools.cancelGroupDiscussion("g1", "gc1");
        assertTrue(out.contains("\"errorCode\":\"NOT_FOUND\""), out);
    }

    @Test
    void cancelGroup_resourceNotFound_returnsNotFound() throws Exception {
        when(groupConversationService.cancelDiscussion(eq("gc1"), any()))
                .thenThrow(new ResourceNotFoundException("gone"));
        String out = tools.cancelGroupDiscussion("g1", "gc1");
        assertTrue(out.contains("\"errorCode\":\"NOT_FOUND\""), out);
    }

    @Test
    void cancelGroup_groupConversationGone_returnsNotFound() throws Exception {
        when(groupConversationService.cancelDiscussion(eq("gc1"), any()))
                .thenThrow(new GroupConversationGoneException("gone", new RuntimeException()));
        String out = tools.cancelGroupDiscussion("g1", "gc1");
        assertTrue(out.contains("\"errorCode\":\"NOT_FOUND\""), out);
    }

    @Test
    void cancelGroup_genericException_returnsInternal() throws Exception {
        when(groupConversationService.cancelDiscussion(eq("gc1"), any()))
                .thenThrow(new ResourceStoreException("db"));
        String out = tools.cancelGroupDiscussion("g1", "gc1");
        assertTrue(out.contains("\"errorCode\":\"INTERNAL\""), out);
    }
}
