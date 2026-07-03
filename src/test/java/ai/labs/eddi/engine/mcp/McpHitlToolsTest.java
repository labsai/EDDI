/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpHitlToolsTest {

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
        when(p.getName()).thenReturn("alice");
        when(identity.getPrincipal()).thenReturn(p);
        tools = build(true, true);
    }

    @Test
    void resume_disabledByKillSwitch_returnsDisabledError() {
        tools = build(true, false);
        String out = tools.resumeConversation("c1", "APPROVED", null);
        assertTrue(out.contains("\"errorCode\":\"DISABLED\""), out);
        verifyNoInteractions(conversationService);
    }

    @Test
    void resume_invalidVerdict_returnsBadRequest() {
        String out = tools.resumeConversation("c1", "maybe", null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void resume_blankConversationId_returnsBadRequest() {
        String out = tools.resumeConversation("  ", "APPROVED", null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void resume_noteTooLong_returnsBadRequest() {
        String longNote = "x".repeat(HitlDecision.MAX_NOTE_LENGTH + 1);
        String out = tools.resumeConversation("c1", "APPROVED", longNote);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void resume_happyPath_setsMcpDecidedByAndDelegates() throws Exception {
        String out = tools.resumeConversation("c1", "approved", "ok"); // lower-case verdict
        assertTrue(out.contains("RESUMED"), out);
        ArgumentCaptor<HitlDecision> captor = ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("c1"), captor.capture(), isNull());
        assertEquals("mcp:alice", captor.getValue().getDecidedBy());
        assertEquals(HitlDecision.HitlVerdict.APPROVED, captor.getValue().getVerdict());
    }

    @Test
    void resume_differentCaller_attributesCorrectPrincipal() throws Exception {
        Principal bob = mock(Principal.class);
        when(bob.getName()).thenReturn("bob");
        when(identity.getPrincipal()).thenReturn(bob); // proves per-call identity resolution
        tools.resumeConversation("c1", "APPROVED", null);
        ArgumentCaptor<HitlDecision> captor = ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("c1"), captor.capture(), isNull());
        assertEquals("mcp:bob", captor.getValue().getDecidedBy());
    }

    @Test
    void resume_forbidden_returnsForbiddenError() {
        doThrow(new ForbiddenException("no")).when(guard).requireConversationHitlAccess("c1");
        String out = tools.resumeConversation("c1", "APPROVED", null);
        assertTrue(out.contains("\"errorCode\":\"FORBIDDEN\""), out);
        verifyNoInteractions(conversationService);
    }

    @Test
    void resume_wrongState_returnsWrongStateWithCurrentState() throws Exception {
        doThrow(new IllegalStateException("bad state"))
                .when(conversationService).resumeConversation(eq("c1"), any(), isNull());
        when(conversationService.getConversationState("c1")).thenReturn(ConversationState.IN_PROGRESS);
        String out = tools.resumeConversation("c1", "APPROVED", null);
        assertTrue(out.contains("\"errorCode\":\"WRONG_STATE\""), out);
        assertTrue(out.contains("IN_PROGRESS"), out);
    }

    @Test
    void cancel_disabledByKillSwitch_returnsDisabled() {
        tools = build(true, false);
        String out = tools.cancelConversation("c1");
        assertTrue(out.contains("\"errorCode\":\"DISABLED\""), out);
        verifyNoInteractions(conversationService);
    }

    @Test
    void cancel_happyPath_attributesMcpPrincipal() throws Exception {
        when(conversationService.cancelConversation(eq("c1"), any(), eq("mcp:alice")))
                .thenReturn(IConversationService.CancelOutcome.CANCELLED);
        String out = tools.cancelConversation("c1");
        assertTrue(out.contains("CANCELLED"), out);
        verify(conversationService).cancelConversation(eq("c1"), any(), eq("mcp:alice"));
    }

    @Test
    void listPendingApprovals_delegatesToGuard() throws Exception {
        when(guard.listScopedPendingApprovals(anyInt())).thenReturn(java.util.List.of());
        when(json.serialize(any())).thenReturn("[]");
        String out = tools.listPendingApprovals("50");
        assertEquals("[]", out);
        verify(guard).listScopedPendingApprovals(50);
    }

    @Test
    void getApprovalStatus_summary_includesPauseType() throws Exception {
        when(guard.requireConversationHitlAccess("c1")).thenReturn("alice");
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPauseType("TOOL_CALL");
        when(conversationService.getConversationMemorySnapshot("c1")).thenReturn(snapshot);
        when(json.serialize(any())).thenAnswer(inv -> inv.getArgument(0).toString());

        String out = tools.getApprovalStatus("c1", "summary");
        assertTrue(out.contains("TOOL_CALL"), out);
    }

    // ---- group surface -----------------------------------------------------

    @Test
    void approveGroup_disabledByKillSwitch_returnsDisabled() {
        tools = build(true, false);
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null);
        assertTrue(out.contains("\"errorCode\":\"DISABLED\""), out);
        verifyNoInteractions(groupConversationService);
    }

    @Test
    void approveGroup_invalidVerdict_returnsBadRequest() {
        String out = tools.approveGroupPhase("g1", "gc1", "perhaps", null, null);
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
    }

    @Test
    void approveGroup_malformedTaskApprovals_returnsBadRequest() throws Exception {
        when(json.deserialize(eq("{bad"), eq(java.util.Map.class))).thenThrow(new java.io.IOException("parse"));
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", null, "{bad");
        assertTrue(out.contains("\"errorCode\":\"BAD_REQUEST\""), out);
        verifyNoInteractions(groupConversationService);
    }

    @Test
    void approveGroup_happyPath_delegatesWithMcpDecidedBy() throws Exception {
        when(json.serialize(any())).thenReturn("{\"ok\":true}");
        String out = tools.approveGroupPhase("g1", "gc1", "APPROVED", "note", null);
        assertTrue(out.contains("ok"), out);
        ArgumentCaptor<GroupApprovalRequest> cap = ArgumentCaptor.forClass(GroupApprovalRequest.class);
        verify(groupConversationService).resumeDiscussion(eq("gc1"), cap.capture(), isNull());
        assertEquals("mcp:alice", cap.getValue().getDecision().getDecidedBy());
        assertEquals(HitlDecision.HitlVerdict.APPROVED, cap.getValue().getDecision().getVerdict());
    }

    @Test
    void listAllGroupPendingApprovals_delegatesToGuardWithNullGroup() throws Exception {
        when(guard.listScopedGroupPendingApprovals(isNull(), anyInt())).thenReturn(java.util.List.of());
        when(json.serialize(any())).thenReturn("[]");
        String out = tools.listAllGroupPendingApprovals("100");
        assertEquals("[]", out);
        verify(guard).listScopedGroupPendingApprovals(null, 100);
    }

    @Test
    void cancelGroup_happyPath_delegates() throws Exception {
        when(groupConversationService.cancelDiscussion(eq("gc1"), any())).thenReturn(true);
        String out = tools.cancelGroupDiscussion("g1", "gc1");
        assertTrue(out.contains("CANCELLED"), out);
    }

    @Test
    void cancelGroup_terminalState_returnsWrongState() throws Exception {
        when(groupConversationService.cancelDiscussion(eq("gc1"), any())).thenReturn(false);
        String out = tools.cancelGroupDiscussion("g1", "gc1");
        assertTrue(out.contains("\"errorCode\":\"WRONG_STATE\""), out);
    }

    @Test
    void getGroupApprovalStatus_summary_serializes() throws Exception {
        GroupConversation gc = mock(GroupConversation.class);
        when(gc.getState()).thenReturn(GroupConversation.GroupConversationState.AWAITING_APPROVAL);
        when(gc.getUserId()).thenReturn("alice");
        when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);
        when(json.serialize(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        String out = tools.getGroupApprovalStatus("g1", "gc1", "summary");
        assertTrue(out.contains("AWAITING_APPROVAL"), out);
    }

    // ---- detail=full read-scope gate (security-critical: approver may read full
    // ONLY while paused)

    @Test
    void getApprovalStatus_detailFull_nonOwnerApproverNotPaused_returnsForbidden() throws Exception {
        when(guard.requireConversationHitlAccess("c1")).thenReturn("someone-else");
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY); // not paused
        when(conversationService.getConversationMemorySnapshot("c1")).thenReturn(snapshot);
        // caller is neither admin nor owner (ownershipValidator mock defaults to false)
        String out = tools.getApprovalStatus("c1", "full");
        assertTrue(out.contains("\"errorCode\":\"FORBIDDEN\""), out);
    }

    @Test
    void getApprovalStatus_detailFull_whilePaused_returnsSnapshot() throws Exception {
        when(guard.requireConversationHitlAccess("c1")).thenReturn("someone-else");
        ConversationMemorySnapshot snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN); // paused
        when(conversationService.getConversationMemorySnapshot("c1")).thenReturn(snapshot);
        when(json.serialize(any())).thenReturn("{\"full\":true}");
        // paused => the gate passes even for a non-owner approver
        String out = tools.getApprovalStatus("c1", "full");
        assertTrue(out.contains("full"), out);
        assertFalse(out.contains("FORBIDDEN"), out);
    }

    @Test
    void getGroupApprovalStatus_detailFull_nonOwnerApproverNotPaused_returnsForbidden() throws Exception {
        GroupConversation gc = mock(GroupConversation.class);
        // getState() unstubbed => null => not AWAITING_APPROVAL => not paused
        when(gc.getUserId()).thenReturn("someone-else");
        when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);
        String out = tools.getGroupApprovalStatus("g1", "gc1", "full");
        assertTrue(out.contains("\"errorCode\":\"FORBIDDEN\""), out);
    }

    @Test
    void getGroupApprovalStatus_detailFull_whilePaused_returnsFullConversation() throws Exception {
        GroupConversation gc = mock(GroupConversation.class);
        when(gc.getState()).thenReturn(GroupConversation.GroupConversationState.AWAITING_APPROVAL); // paused
        when(gc.getUserId()).thenReturn("someone-else");
        when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);
        when(json.serialize(any())).thenReturn("{\"full\":true}");
        String out = tools.getGroupApprovalStatus("g1", "gc1", "full");
        assertTrue(out.contains("full"), out);
        assertFalse(out.contains("FORBIDDEN"), out);
    }
}
