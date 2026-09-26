/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RequestBodyLimitGuard} — the global body limit was raised to 60 MB for
 * knowledge-base file uploads, and with it for every other endpoint.
 */
@DisplayName("RequestBodyLimitGuard")
class RequestBodyLimitGuardTest {

    private static final long MB = 1024L * 1024;

    private static RequestBodyLimitGuard guard() {
        var guard = new RequestBodyLimitGuard();
        guard.defaultMaxBodySize = new MemorySize(BigInteger.valueOf(25 * MB));
        return guard;
    }

    private static RoutingContext request(String method, String path, Long contentLength) {
        return request(method, path, contentLength, null, HttpVersion.HTTP_1_1, true);
    }

    private static RoutingContext request(String method, String path, Long contentLength, String transferEncoding,
                                          HttpVersion version, boolean ended) {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.normalizedPath()).thenReturn(path);
        when(request.method()).thenReturn(HttpMethod.valueOf(method));
        when(request.getHeader(HttpHeaders.CONTENT_LENGTH))
                .thenReturn(contentLength == null ? null : contentLength.toString());
        when(request.getHeader(HttpHeaders.TRANSFER_ENCODING)).thenReturn(transferEncoding);
        when(request.version()).thenReturn(version);
        when(request.isEnded()).thenReturn(ended);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), any(CharSequence.class))).thenReturn(response);
        return context;
    }

    @Test
    @DisplayName("refuses a chunked body on an ordinary endpoint, before any of it is read")
    void refusesAChunkedBody() {
        // Content-Length was the only thing judged, so a client that simply omitted
        // it was buffered up to the global 60 MB on every endpoint.
        RoutingContext context = request("POST", "/agentstore/agents", null, "chunked", HttpVersion.HTTP_1_1,
                false);

        guard().handle(context);

        verify(context.response()).setStatusCode(411);
        verify(context.response()).putHeader(HttpHeaders.CONNECTION, "close");
        verify(context, never()).next();
    }

    @Test
    @DisplayName("refuses an HTTP/2 body that arrives without a length")
    void refusesAnUnsizedHttp2Body() {
        RoutingContext context = request("PUT", "/agentstore/agents/abc", null, null, HttpVersion.HTTP_2, false);

        guard().handle(context);

        verify(context.response()).setStatusCode(411);
    }

    @Test
    @DisplayName("an HTTP/2 request with no body at all passes")
    void allowsABodylessHttp2Request() {
        RoutingContext context = request("POST", "/agents/abc/end", null, null, HttpVersion.HTTP_2, true);

        guard().handle(context);

        verify(context).next();
    }

    @Test
    @DisplayName("the upload may stream its body without a length, under the global ceiling")
    void theUploadMayBeChunked() {
        RoutingContext context = request("POST", "/ragstore/rags/5a8b1c2d/sources/src-1/files", null, "chunked",
                HttpVersion.HTTP_1_1, false);

        guard().handle(context);

        verify(context).next();
    }

    @Test
    @DisplayName("refusing unsized bodies can be turned off for a client that cannot send a length")
    void unsizedRefusalCanBeTurnedOff() {
        var guard = guard();
        guard.refuseUnsizedBodies = false;
        RoutingContext context = request("POST", "/agentstore/agents", null, "chunked", HttpVersion.HTTP_1_1,
                false);

        guard.handle(context);

        verify(context).next();
    }

    @Test
    @DisplayName("a refusal closes the connection rather than draining the body")
    void aRefusalClosesTheConnection() {
        RoutingContext context = request("PUT", "/agentstore/agents/abc", 40 * MB);

        guard().handle(context);

        verify(context.response()).putHeader(HttpHeaders.CONNECTION, "close");
    }

    @Test
    @DisplayName("an attachment at the attachment limit fits, base64 and all")
    void theLimitMakesRoomForTheLargestAttachment() {
        // 20 MiB base64-encoded is about 27.96 M characters: a 25 MB limit refused
        // inline attachments the attachment validator itself accepts.
        var guard = guard();
        long base64Of20MiB = (20L * MB / 3 + 1) * 4;

        assertTrue(guard.effectiveLimit() > base64Of20MiB, "limit " + guard.effectiveLimit());

        guard.attachmentMaxBytes = 60 * MB;
        assertTrue(guard.effectiveLimit() > (60L * MB / 3) * 4, "an operator raising the attachment limit is not refused");
    }

    @Test
    @DisplayName("the upload exemption holds under quarkus.http.root-path")
    void theExemptionHoldsUnderARootPath() {
        var guard = guard();
        guard.rootPath = "/eddi";
        RoutingContext upload = request("POST", "/eddi/ragstore/rags/5a8b1c2d/sources/src-1/files", 40 * MB);
        RoutingContext other = request("PUT", "/eddi/agentstore/agents/abc", 40 * MB);

        guard.handle(upload);
        guard.handle(other);

        verify(upload).next();
        verify(other.response()).setStatusCode(413);
    }

    @Test
    @DisplayName("it is registered as a Vert.x filter, which Quarkus runs before any route reads a body")
    void isRegisteredAsAFilter() {
        // Quarkus runs every handler registered through Filters ahead of the
        // application's routes, the REST layer's body reading among them — the
        // same mechanism HttpMethodGuard relies on. Proving that ordering end to end
        // needs a booted application (a @QuarkusTest), which needs the Docker-backed
        // dev services this unit suite does not have; what can be proven here is that
        // the guard puts itself in that chain, below HttpMethodGuard.
        Filters filters = mock(Filters.class);

        guard().register(filters);

        verify(filters).register(any(), eq(RequestBodyLimitGuard.PRIORITY));
        assertTrue(RequestBodyLimitGuard.PRIORITY < HttpMethodGuard.PRIORITY);
    }

    @Test
    @DisplayName("refuses a large body on an ordinary endpoint before it is read")
    void refusesALargeJsonBody() {
        RoutingContext context = request("PUT", "/agentstore/agents/abc", 40 * MB);

        guard().handle(context);

        verify(context.response()).setStatusCode(413);
        verify(context.response()).end(anyString());
        verify(context, never()).next();
    }

    @Test
    @DisplayName("lets a knowledge base's file upload carry a large body")
    void allowsTheFileUpload() {
        RoutingContext context = request("POST", "/ragstore/rags/5a8b1c2d/sources/src-1/files", 40 * MB);

        guard().handle(context);

        verify(context).next();
        verify(context.response(), never()).setStatusCode(anyInt());
    }

    @Test
    @DisplayName("only the upload itself: another method on the same path is held to the default")
    void onlyThePostIsExempt() {
        RoutingContext context = request("PUT", "/ragstore/rags/5a8b1c2d/sources/src-1/files", 40 * MB);

        guard().handle(context);

        verify(context.response()).setStatusCode(413);
    }

    @Test
    @DisplayName("lets an ordinary body through")
    void allowsASmallBody() {
        RoutingContext context = request("PUT", "/agentstore/agents/abc", 10 * MB);

        guard().handle(context);

        verify(context).next();
    }

    @Test
    @DisplayName("leaves a request without a declared length to the global ceiling")
    void leavesChunkedRequestsAlone() {
        RoutingContext context = request("POST", "/agentstore/agents", null);

        guard().handle(context);

        verify(context).next();
    }
}
