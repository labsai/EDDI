/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.AgentCallMarker;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AgentOriginatedRequestFilter")
class AgentOriginatedRequestFilterTest {

    @ParameterizedTest
    @CsvSource({"PATCH,hitl/samples/s1", "POST,/hitl/autonomy/a1/apply", "POST,hitl/analytics/rebuild", "post,hitl/autonomy/a1/lift",
            "DELETE,hitl/samples/s1"})
    @DisplayName("a marked write to a guarded path is refused")
    void refused(String method, String path) {
        assertTrue(AgentOriginatedRequestFilter.refused(method, path, "true"));
    }

    @ParameterizedTest
    @CsvSource({"POST,agents/c2/resume", "POST,/agents/c2/resume/stream", "POST,agents/c2/resume/",
            "POST,groups/g1/conversations/gc1/approve", "POST,groups/g1/conversations/gc1/approve/stream",
            "POST,groups/g1/conversations/gc1/human-input", "post,AGENTS/C2/RESUME"})
    @DisplayName("a marked HITL decision is refused: an agent may not resume a pause as the approver it talks to")
    void decisionRefused(String method, String path) {
        assertTrue(AgentOriginatedRequestFilter.refused(method, path, "true"), path);
    }

    @ParameterizedTest
    @CsvSource(value = {"POST,agents/c2/resume,", "GET,agents/c2/approval-status,true", "POST,agents/c2,true", "POST,agents/c2/stream,true",
            "POST,groups/g1/conversations/gc1/followup,true", "POST,agents/c2/resumex,true"}, nullValues = "")
    @DisplayName("an unmarked decision, a read and a plain conversation turn pass")
    void decisionPasses(String method, String path, String marker) {
        assertFalse(AgentOriginatedRequestFilter.refused(method, path, marker), path);
    }

    @ParameterizedTest
    @CsvSource(value = {"GET,hitl/samples,true", "HEAD,hitl/autonomy,true", "PATCH,hitl/samples/s1,", "POST,hitl/analytics/summary,true",
            "POST,agentstore/agents,true", "POST,hitl/samplesx,true"}, nullValues = "")
    @DisplayName("reads, unmarked requests and other paths pass")
    void passes(String method, String path, String marker) {
        assertFalse(AgentOriginatedRequestFilter.refused(method, path, marker));
    }

    @Test
    @DisplayName("the filter aborts with 403 AGENT_ORIGINATED")
    void aborts() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(request.getMethod()).thenReturn("PATCH");
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("hitl/samples/s1");
        when(request.getHeaderString(AgentCallMarker.HEADER)).thenReturn("true");

        new AgentOriginatedRequestFilter().filter(request);

        var response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        assertEquals(403, response.getValue().getStatus());
    }

    @Test
    @DisplayName("an unmarked request is not touched")
    void unmarked() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(request.getMethod()).thenReturn("PATCH");
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("hitl/samples/s1");

        new AgentOriginatedRequestFilter().filter(request);

        verify(request, never()).abortWith(any());
    }
}
