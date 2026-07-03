/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
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
import org.mockito.ArgumentCaptor;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
