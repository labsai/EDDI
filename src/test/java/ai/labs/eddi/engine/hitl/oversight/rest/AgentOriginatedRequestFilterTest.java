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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

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
            "POST,groups/g1/conversations/gc1/human-input", "post,AGENTS/C2/RESUME",
            // spellings routing still accepts: matrix parameters, repeated slashes, an id
            // whose %2F was decoded into a slash, a trailing slash
            "POST,agents/c2/resume;v=1", "POST,agents//c2/resume", "POST,agents/a/b/resume", "POST,groups/g1/conversations/gc1/approve;x/",
            "PATCH,hitl/samples;v=1/s1", "POST,//hitl/autonomy/a1/apply"})
    @DisplayName("a marked HITL decision is refused: an agent may not resume a pause as the approver it talks to")
    void decisionRefused(String method, String path) {
        assertTrue(AgentOriginatedRequestFilter.refused(method, path, "true"), path);
    }

    @ParameterizedTest
    @CsvSource({"groups/g1/conversations/gc1/approve;x=%2Fzzz", "groups/g1/conversations/gc1/approve/stream;x=%2Fzzz",
            "groups/g1/conversations/gc1/human-input;x=%2Fzzz", "agents/c2/resume;x=%2Fzzz", "agents/c2/resume/stream;x=%2F..%2Fzzz",
            "agents/c2;v=%2Fa/resume;x=%2Fzzz", "hitl/samples/s1;x=%2Fzzz"})
    @DisplayName("a matrix parameter holding an encoded slash cannot move the last segment: the raw reading is judged too")
    void encodedSlashInMatrixParameterRefused(String rawPath) {
        // The router drops ;x=%2Fzzz from the raw segment and serves approve; the
        // decoded path is .../approve;x=/zzz, whose last segment reads zzz.
        String decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        assertTrue(AgentOriginatedRequestFilter.refused("POST", decoded, rawPath, "true"), rawPath);
        assertFalse(AgentOriginatedRequestFilter.refused("POST", decoded, rawPath, null), "unmarked: " + rawPath);
    }

    @Test
    @DisplayName("the filter reads the raw path from the request, so the encoded-slash form is aborted")
    void filterReadsRawPath() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("groups/g1/conversations/gc1/approve;x=/zzz");
        when(uriInfo.getPath(false)).thenReturn("groups/g1/conversations/gc1/approve;x=%2Fzzz");
        when(request.getHeaderString(AgentCallMarker.HEADER)).thenReturn("true");

        new AgentOriginatedRequestFilter().filter(request);

        verify(request).abortWith(any());
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
