/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.context.ApprovalContextViews;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.memory.ConversationMemoryTail;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP {@code get_approval_status} serves the same {@code approvalContext} view
 * as REST (one shared helper), and its summary values keep their string types.
 */
@DisplayName("McpHitlTools get_approval_status approvalContext")
class McpHitlToolsApprovalContextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private IConversationService conversationService;
    private HitlAccessGuard guard;
    private McpHitlTools tools;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        guard = mock(HitlAccessGuard.class);
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        when(principal.getName()).thenReturn("alice");
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("eddi-approver")).thenReturn(true);
        when(guard.requireConversationHitlAccess("c1")).thenReturn("alice");
        IJsonSerialization json = new JsonSerialization(MAPPER);
        tools = new McpHitlTools(conversationService, mock(IGroupConversationService.class), guard, mock(OwnershipValidator.class),
                mock(ResourceAccessGuard.class), json, identity, new SimpleMeterRegistry(), true, true);
    }

    private RenderedApprovalContext pause(ConversationState state) throws Exception {
        var ctx = RenderedApprovalContext.of(List.of(new Item("Draft", "key sk-ant-" + "fixture".repeat(5) + " end", false, "agent", null, null)),
                false);
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(state);
        snapshot.setHitlPauseType("TOOL_CALL");
        snapshot.setHitlApprovalContext(ctx);
        lenient().when(conversationService.getConversationMemoryTail("c1")).thenAnswer(inv -> ConversationMemoryTail.ofFullSnapshot(snapshot));
        lenient().when(conversationService.getConversationMemorySnapshotForApprover("c1")).thenReturn(snapshot);
        return ctx;
    }

    @Test
    @DisplayName("the summary carries the same view REST serves, re-redacted, and keeps its string fields")
    void sameViewAsRest() throws Exception {
        var ctx = pause(ConversationState.AWAITING_HUMAN);

        JsonNode out = MAPPER.readTree(tools.getApprovalStatus("c1", "summary"));

        assertEquals("TOOL_CALL", out.get("pauseType").asText());
        assertEquals(MAPPER.valueToTree(ApprovalContextViews.toView(ctx)), out.get("approvalContext"));
        assertFalse(out.get("approvalContext").toString().contains("fixturefixture"));
    }

    @Test
    @DisplayName("not paused: null")
    void nullWhenNotPaused() throws Exception {
        pause(ConversationState.READY);

        JsonNode out = MAPPER.readTree(tools.getApprovalStatus("c1", "summary"));

        assertTrue(out.get("approvalContext") == null || out.get("approvalContext").isNull());
        assertEquals("", out.get("pauseType").asText());
    }
}
