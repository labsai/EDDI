/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("MCP decision tools — editedAnswer, reasonCode, per-call and per-task decisions")
class McpHitlToolsReviewTest {

    private IConversationService conversationService;
    private IGroupConversationService groupConversationService;
    private McpHitlTools tools;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        groupConversationService = mock(IGroupConversationService.class);
        var ownershipValidator = mock(OwnershipValidator.class);
        when(ownershipValidator.isApprover(any())).thenReturn(true);
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("alice");
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("eddi-approver")).thenReturn(true);
        tools = new McpHitlTools(conversationService, groupConversationService, mock(HitlAccessGuard.class), ownershipValidator,
                mock(ResourceAccessGuard.class), new JsonSerialization(new ObjectMapper()), identity, new SimpleMeterRegistry(), true, true);
    }

    @Test
    @DisplayName("resume_conversation passes the edit, the reason and per-call decisions through")
    void resumeWithReview() throws Exception {
        String out = tools.resumeConversation("c1", "APPROVED", null, "123", "Better answer", "TONE",
                "{\"call-1\":{\"verdict\":\"approved\",\"amendedArguments\":{\"body\":\"hi\"},\"reasonCode\":\"FORMAT\"},"
                        + "\"call-2\":{\"verdict\":\"REJECTED\",\"note\":\"no\"}}");

        assertTrue(out.contains("RESUMED"), out);
        var captor = ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("c1"), captor.capture(), isNull());
        HitlDecision decision = captor.getValue();
        assertEquals("Better answer", decision.getEditedAnswer());
        assertEquals("TONE", decision.getReasonCode());
        assertEquals("mcp:alice", decision.getDecidedBy());
        assertEquals(HitlVerdict.APPROVED, decision.getToolDecisions().get("call-1").getVerdict());
        assertEquals("{\"body\":\"hi\"}", decision.getToolDecisions().get("call-1").getAmendedArguments());
        assertEquals("FORMAT", decision.getToolDecisions().get("call-1").getReasonCode());
        assertEquals("no", decision.getToolDecisions().get("call-2").getNote());
    }

    @Test
    @DisplayName("malformed toolDecisionsJson is a BAD_REQUEST and never reaches the service")
    void malformedToolDecisions() {
        assertTrue(tools.resumeConversation("c1", "APPROVED", null, null, null, null, "[1,2]").contains("BAD_REQUEST"));
        assertTrue(tools.resumeConversation("c1", "APPROVED", null, null, null, null, "{\"c\":{\"verdict\":\"maybe\"}}").contains("BAD_REQUEST"));
        assertTrue(tools.resumeConversation("c1", "APPROVED", null, null, null, null, "{\"c\":{\"verdict\":\"APPROVED\",\"note\":5}}")
                .contains("BAD_REQUEST"));
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("the service's validation message comes back as BAD_REQUEST")
    void validationMessage() throws Exception {
        doThrow(new IllegalArgumentException("this agent does not allow approving with an edited answer"))
                .when(conversationService).resumeConversation(eq("c1"), any(), isNull());

        String out = tools.resumeConversation("c1", "APPROVED", null, null, "x", null, null);

        assertTrue(out.contains("BAD_REQUEST") && out.contains("edited answer"), out);
    }

    @Test
    @DisplayName("the legacy overloads still work and send no review fields")
    void legacyOverloads() throws Exception {
        tools.resumeConversation("c1", "REJECTED", "note");
        var captor = ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("c1"), captor.capture(), isNull());
        assertNull(captor.getValue().getEditedAnswer());
        assertNull(captor.getValue().getToolDecisions());
    }

    @Test
    @DisplayName("approve_group_phase passes taskDecisions, the synthesis edit and the reason")
    void approveWithReview() throws Exception {
        tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null, null, "New synthesis", "OTHER",
                "{\"t1\":{\"verdict\":\"APPROVED\",\"editedResult\":\"fixed\"}}");

        var captor = ArgumentCaptor.forClass(GroupApprovalRequest.class);
        verify(groupConversationService).resumeDiscussion(eq("gc1"), captor.capture(), isNull());
        GroupApprovalRequest request = captor.getValue();
        assertEquals("New synthesis", request.getDecision().getEditedAnswer());
        assertEquals("OTHER", request.getDecision().getReasonCode());
        assertEquals("fixed", request.getTaskDecisions().get("t1").getEditedResult());
        assertTrue(tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null, null, null, null, "nope").contains("BAD_REQUEST"));
    }
}
