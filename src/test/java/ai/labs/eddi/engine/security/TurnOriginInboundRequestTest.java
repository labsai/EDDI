/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The inbound side of the email origin taint (email plan v3 §5.3): a REST or
 * MCP request carrying {@code X-Eddi-Turn-Origin} runs tainted, a header can
 * add the taint and never remove it, the receiving side refuses the guarded
 * writes of such a request, and the approval-like oversight and handoff
 * endpoints count as guarded.
 */
@DisplayName("TurnOrigin — the taint of an inbound request, and the guarded writes it may not make")
class TurnOriginInboundRequestTest {

    private final ResolutionPrincipalContext principals = new ResolutionPrincipalContext();

    @AfterEach
    void tearDown() {
        principals.clear();
    }

    @Nested
    @DisplayName("reading the header")
    class Reading {

        @Test
        @DisplayName("a request with X-Eddi-Turn-Origin: email runs tainted")
        void headerTaints() throws Exception {
            try (var request = TurnOriginTestSupport.inboundRequest("email")) {
                assertEquals(TurnOrigin.EMAIL, TurnOrigin.current());
                assertTrue(TurnOrigin.currentIsEmail());
            }
            assertNull(TurnOrigin.current(), "no request, no taint");
        }

        @Test
        @DisplayName("any of several values naming email taints; case and blanks do not matter")
        void anyValueTaints() {
            assertEquals(TurnOrigin.EMAIL, TurnOrigin.fromHeaderValues(List.of("none", " EMAIL ")));
            assertEquals(TurnOrigin.EMAIL, TurnOrigin.fromHeaderValues(Arrays.asList(null, "email")));
            assertNull(TurnOrigin.fromHeaderValues(List.of("none", "")));
            assertNull(TurnOrigin.fromHeaderValues(null));
        }

        @Test
        @DisplayName("a request without the header, or with junk in it, never clears a taint the turn already has")
        void headerNeverRemoves() throws Exception {
            principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
            try (var request = TurnOriginTestSupport.inboundRequest("none")) {
                assertEquals(TurnOrigin.EMAIL, TurnOrigin.current());
            }
            try (var request = TurnOriginTestSupport.inboundRequest()) {
                assertEquals(TurnOrigin.EMAIL, TurnOrigin.current());
            }
        }

        @Test
        @DisplayName("an untainted request is untainted")
        void untainted() throws Exception {
            try (var request = TurnOriginTestSupport.inboundRequest()) {
                assertNull(TurnOrigin.current());
            }
        }

        @Test
        @DisplayName("stamping schedule metadata adds the taint to what is there, and a null origin changes nothing")
        void stampMetadata() {
            Map<String, Object> metadata = Map.of("type", "x");

            Map<String, Object> stamped = TurnOrigin.stampMetadata(metadata, TurnOrigin.EMAIL);

            assertEquals(Map.of("type", "x", TurnOrigin.CONTEXT_KEY, TurnOrigin.EMAIL), stamped);
            assertSame(metadata, TurnOrigin.stampMetadata(metadata, null));
            assertEquals(Map.of(TurnOrigin.CONTEXT_KEY, TurnOrigin.EMAIL), TurnOrigin.stampMetadata(null, TurnOrigin.EMAIL));
        }
    }

    @Nested
    @DisplayName("approval-like writes are guarded")
    class ApprovalLike {

        @Test
        @DisplayName("oversight writes — sample verdicts, earned autonomy, the analytics rebuild, spend-control decisions")
        void oversightWrites() {
            for (String path : List.of("/hitl/samples/s1", "/hitl/autonomy/agent-1/apply", "/hitl/autonomy/agent-1/category",
                    "/hitl/autonomy/agent-1/lift", "/hitl/analytics/rebuild", "/administration/spend-control-changes/c1/approve",
                    "/administration/spend-control-changes/c1/withdraw", "/eddi/hitl/autonomy;x=1/agent-1/suspend")) {
                assertTrue(EscalationGuard.isGuardedRequest("POST", path), path);
            }
            assertFalse(EscalationGuard.isGuardedRequest("GET", "/hitl/autonomy/agent-1"));
            assertFalse(EscalationGuard.isGuardedRequest("GET", "/hitl/samples"));
        }

        @Test
        @DisplayName("team handoff decisions, as endpoints and as MCP tools")
        void handoffDecisions() {
            assertTrue(EscalationGuard.isGuardedRequest("POST", "/groupstore/groups/g1/workspace/handoffs/h1/accept"));
            assertTrue(EscalationGuard.isGuardedRequest("POST", "/groupstore/groups/g1/workspace/handoffs/h1/decline"));
            assertFalse(EscalationGuard.isGuardedRequest("GET", "/groupstore/groups/g1/workspace/handoffs"));
            assertTrue(EscalationGuard.isGuardedTool("accept_team_handoff"));
            assertTrue(EscalationGuard.isGuardedTool("eddi_decline_team_handoff"));
        }

        @Test
        @DisplayName("starting or continuing a conversation, a schedule or a group is not refused: it is tainted instead")
        void startPathsArePropagatedNotRefused() {
            for (String path : List.of("/agents/a1/start", "/agents/a1/c1", "/schedulestore/schedules", "/schedulestore/schedules/s1/fire",
                    "/groups/g1/conversations", "/groups/g1/conversations/gc1/continue", "/v1/chat/completions")) {
                assertFalse(EscalationGuard.isGuardedRequest("POST", path), path);
            }
        }

        @Test
        @DisplayName("a tainted turn is refused the oversight write, an untainted one is not")
        void refusedOnlyWhenTainted() {
            URI apply = URI.create("http://localhost:7070/hitl/autonomy/agent-1/apply");
            assertTrue(EscalationGuard.refuseRequest("POST", apply).isEmpty());
            principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
            assertTrue(EscalationGuard.refuseRequest("POST", apply).isPresent());
            assertTrue(EscalationGuard.refuseTool("accept_team_handoff").isPresent());
        }
    }

    @Nested
    @DisplayName("TaintedRequestFilter — the receiving side for REST")
    class Filter {

        private ContainerRequestContext request(String method, String path, String... origin) {
            ContainerRequestContext request = mock(ContainerRequestContext.class);
            UriInfo uriInfo = mock(UriInfo.class);
            when(uriInfo.getPath()).thenReturn(path);
            when(request.getUriInfo()).thenReturn(uriInfo);
            when(request.getMethod()).thenReturn(method);
            var headers = new MultivaluedHashMap<String, String>();
            if (origin.length > 0) {
                headers.put(TurnOrigin.HEADER, List.of(origin));
            }
            when(request.getHeaders()).thenReturn(headers);
            return request;
        }

        @Test
        @DisplayName("a tainted write to a guarded endpoint answers 403 before the resource runs")
        void taintedGuardedWriteRefused() {
            for (String path : List.of("channelstore/channels/abc/email-senders", "hitl/autonomy/agent-1/apply",
                    "groupstore/groups/g1/workspace/handoffs/h1/accept", "agents/c1/resume")) {
                ContainerRequestContext request = request("POST", path, "email");

                new TaintedRequestFilter().filter(request);

                ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
                verify(request).abortWith(response.capture());
                assertEquals(403, response.getValue().getStatus(), path);
            }
        }

        @Test
        @DisplayName("the same write without the header, a tainted read, and a tainted conversation start all pass")
        void othersPass() {
            for (ContainerRequestContext request : List.of(request("POST", "hitl/autonomy/agent-1/apply"),
                    request("GET", "channelstore/channels/abc/email-senders", "email"), request("POST", "agents/a1/start", "email"),
                    request("POST", "schedulestore/schedules", "email"))) {
                new TaintedRequestFilter().filter(request);

                verify(request, never()).abortWith(any());
            }
        }
    }
}
