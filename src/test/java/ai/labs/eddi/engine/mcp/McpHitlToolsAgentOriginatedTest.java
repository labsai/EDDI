/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.oversight.AgentCallMarker;
import ai.labs.eddi.engine.hitl.oversight.AgentOriginatedRequests;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The MCP tools that decide a HITL pause call the services in-process, so the
 * REST {@code AgentOriginatedRequestFilter} never sees them. An MCP server
 * configured with {@code ${caller:token}} and pointed at this deployment's own
 * {@code /mcp} carries the agent-call marker; these tools refuse it the same
 * way, read off the inbound {@code /mcp} request.
 */
@DisplayName("McpHitlTools — decisions refuse an agent's own MCP call")
class McpHitlToolsAgentOriginatedTest {

    private IConversationService conversationService;
    private IGroupConversationService groupConversationService;
    private HttpServerRequest httpRequest;
    private McpHitlTools tools;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        groupConversationService = mock(IGroupConversationService.class);
        HitlAccessGuard guard = mock(HitlAccessGuard.class);
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("approver");
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("eddi-approver")).thenReturn(true);
        OwnershipValidator ownership = mock(OwnershipValidator.class);
        when(ownership.isAdmin(any())).thenReturn(true);
        tools = new McpHitlTools(conversationService, groupConversationService, guard, ownership, mock(ResourceAccessGuard.class),
                mock(IJsonSerialization.class), identity, new SimpleMeterRegistry(), true, true);

        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        RoutingContext routingContext = mock(RoutingContext.class);
        httpRequest = mock(HttpServerRequest.class);
        when(current.getCurrent()).thenReturn(routingContext);
        when(routingContext.request()).thenReturn(httpRequest);
        tools.agentOriginatedRequests = new AgentOriginatedRequests(current);
    }

    @Test
    @DisplayName("resume_conversation, approve_group_phase and submit_group_human_input refuse a marked MCP request")
    void markedDecisionsRefused() {
        when(httpRequest.getHeader(AgentCallMarker.HEADER)).thenReturn(AgentCallMarker.VALUE);

        for (String out : new String[]{tools.resumeConversation("other-conversation", "APPROVED", null, null),
                tools.approveGroupPhase("g1", "gc1", "APPROVED", null, null, null),
                tools.submitGroupHumanInput("g1", "gc1", "m1", "yes", null)}) {
            assertTrue(out.contains("\"errorCode\":\"AGENT_ORIGINATED\""), out);
        }
        verifyNoInteractions(conversationService, groupConversationService);
    }

    @Test
    @DisplayName("an unmarked MCP request decides as before")
    void unmarkedDecisionsProceed() {
        String out = tools.resumeConversation("c1", "APPROVED", null, null);

        assertFalse(out.contains("AGENT_ORIGINATED"), out);
        assertTrue(out.contains("RESUMED"), out);
    }

    @Test
    @DisplayName("without an HTTP request (an in-process call) nothing is refused")
    void noHttpRequest() {
        CurrentVertxRequest none = mock(CurrentVertxRequest.class);
        assertFalse(new AgentOriginatedRequests(none).currentRequestIsMarked());
        assertFalse(AgentOriginatedRequests.currentRequestIsMarked(null));
    }
}
