/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static io.netty.handler.codec.http.HttpMethod.valueOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("HttpMethodGuard")
class HttpMethodGuardTest {

    private static RoutingContext contextFor(HttpMethod method) {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(request.method()).thenReturn(method);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        return context;
    }

    @ParameterizedTest(name = "{0} is refused with 405")
    @ValueSource(strings = {"TRACE", "TRACK"})
    @DisplayName("refuses TRACE and TRACK")
    void refusesTraceMethods(String method) {
        RoutingContext context = contextFor(HttpMethod.valueOf(method));

        HttpMethodGuard.handle(context);

        verify(context.response()).setStatusCode(405);
        verify(context.response()).putHeader("Allow", HttpMethodGuard.ALLOWED_METHODS);
        verify(context.response()).end();
        verify(context, never()).next();
    }

    /**
     * The method exactly as the HTTP/1 server hands it to the filter:
     * {@code Http1xServerRequest.method()} is {@code HttpMethod.fromNetty} of the
     * decoded request line ({@code valueOf} is Netty's). TRACK is a constant on
     * neither side, so this is the path that would lose the verb if anything did.
     */
    private static HttpMethod decodedByServer(String verb) {
        return HttpMethod.fromNetty(valueOf(verb));
    }

    @ParameterizedTest(name = "{0} as decoded by the server is refused with 405")
    @ValueSource(strings = {"TRACE", "TRACK", "track"})
    @DisplayName("refuses TRACE and TRACK as the HTTP/1 server decodes them, extension method included")
    void refusesServerDecodedTraceMethods(String verb) {
        HttpMethod decoded = decodedByServer(verb);
        assertEquals(verb, decoded.name(), "Vert.x 4 keeps an extension method's own name");
        RoutingContext context = contextFor(decoded);

        HttpMethodGuard.handle(context);

        verify(context.response()).setStatusCode(405);
        verify(context, never()).next();
    }

    @ParameterizedTest(name = "{0} passes through")
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    @DisplayName("lets every method EDDI serves through")
    void passesServedMethods(String method) {
        RoutingContext context = contextFor(HttpMethod.valueOf(method));

        HttpMethodGuard.handle(context);

        verify(context).next();
        verify(context.response(), never()).setStatusCode(anyInt());
    }

    @Test
    @DisplayName("registers itself as a route filter ahead of the header filters")
    @SuppressWarnings("unchecked")
    void registersAsRouteFilter() {
        Filters filters = mock(Filters.class);
        ArgumentCaptor<Handler<RoutingContext>> handler = ArgumentCaptor.forClass(Handler.class);

        new HttpMethodGuard().register(filters);

        verify(filters).register(handler.capture(), eq(HttpMethodGuard.PRIORITY));
        RoutingContext trace = contextFor(HttpMethod.TRACE);
        handler.getValue().handle(trace);
        verify(trace.response()).setStatusCode(405);
    }
}
