/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.factory;

import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EDDI's internal loopback calls carried no credentials, so every service that
 * re-enters its own API — the agent wizard, {@code setup-api}, the Platform
 * Operator's write tools, most of {@code McpAdminTools} — answered 401 as soon
 * as {@code authorization.enabled=true}. That is the configuration per-user
 * workspaces require, so the two features were mutually exclusive until this
 * filter existed.
 */
class LoopbackCallerAuthFilterTest {

    private static ClientRequestContext requestWith(MultivaluedMap<String, Object> headers) {
        ClientRequestContext context = mock(ClientRequestContext.class);
        when(context.getHeaders()).thenReturn(headers);
        return context;
    }

    private static LoopbackCallerAuthFilter filterFor(CallerIdentity bound, CallerIdentity captured) {
        CallerIdentityContext context = mock(CallerIdentityContext.class);
        when(context.current()).thenReturn(bound);
        when(context.capture()).thenReturn(captured);
        return new LoopbackCallerAuthFilter(context);
    }

    @Test
    @DisplayName("a request thread's captured token is forwarded")
    void forwardsCapturedToken() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        filterFor(null, new CallerIdentity("tok-123", "alice", "http://127.0.0.1:7070")).filter(requestWith(headers));

        assertEquals("Bearer tok-123", headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("a bound identity wins over a captured one")
    void boundIdentityWins() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        filterFor(new CallerIdentity("bound-tok", "alice", "http://127.0.0.1:7070"),
                new CallerIdentity("captured-tok", "approver", "http://127.0.0.1:7070")).filter(requestWith(headers));

        // On a HITL resume the request belongs to the approving administrator while the
        // turn belongs to the user who asked. The bound identity is the turn's.
        assertEquals("Bearer bound-tok", headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("no identity means no header — unchanged behaviour with OIDC off")
    void anonymousAddsNoHeader() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        filterFor(null, null).filter(requestWith(headers));

        assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("an identity without a token adds no header")
    void identityWithoutTokenAddsNoHeader() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        filterFor(null, new CallerIdentity(null, "alice", "http://127.0.0.1:7070")).filter(requestWith(headers));

        assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("an explicit Authorization header is never overwritten")
    void explicitHeaderWins() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle(HttpHeaders.AUTHORIZATION, "Bearer explicit");
        filterFor(null, new CallerIdentity("tok-123", "alice", "http://127.0.0.1:7070")).filter(requestWith(headers));

        assertEquals("Bearer explicit", headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("a failure resolving the caller leaves the call unauthenticated rather than breaking it")
    void resolutionFailureIsNotFatal() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        CallerIdentityContext context = mock(CallerIdentityContext.class);
        when(context.current()).thenThrow(new IllegalStateException("no request context"));

        new LoopbackCallerAuthFilter(context).filter(requestWith(headers));

        assertFalse(headers.containsKey(HttpHeaders.AUTHORIZATION));
    }
}
