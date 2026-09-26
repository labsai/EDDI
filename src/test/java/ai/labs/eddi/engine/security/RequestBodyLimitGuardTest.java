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
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.normalizedPath()).thenReturn(path);
        when(request.method()).thenReturn(HttpMethod.valueOf(method));
        when(request.getHeader(HttpHeaders.CONTENT_LENGTH))
                .thenReturn(contentLength == null ? null : contentLength.toString());
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), any(CharSequence.class))).thenReturn(response);
        return context;
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
