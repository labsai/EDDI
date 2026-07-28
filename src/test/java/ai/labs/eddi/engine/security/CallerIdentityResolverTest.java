/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.security.CallerIdentityResolver.CallerIdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behaviour of {@code ${caller:...}} resolution.
 *
 * @author ginccc
 */
class CallerIdentityResolverTest {

    private static final String SELF = "https://eddi.example:443";
    private static final URI SELF_TARGET = URI.create("https://eddi.example/agentstore/agents/descriptors");

    private CallerIdentityContext context;
    private CallerIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        context = mock(CallerIdentityContext.class);
        resolver = new CallerIdentityResolver(context, true);
    }

    private void withCaller(CallerIdentity identity) {
        when(context.current()).thenReturn(identity);
    }

    // ==================== Pass-through ====================

    @Test
    @DisplayName("a value without a caller reference is returned untouched")
    void leavesUnrelatedValuesAlone() {
        withCaller(new CallerIdentity("tok", "alice", SELF));
        String value = "Bearer ${vault:some-key}";
        assertSame(value, resolver.resolveValue(value, SELF_TARGET));
    }

    @Test
    @DisplayName("null is not a reference")
    void handlesNull() {
        assertNull(resolver.resolveValue(null, SELF_TARGET));
    }

    @Test
    void detectsReferences() {
        assertTrue(CallerIdentityResolver.containsReference("Bearer ${caller:token}"));
        assertTrue(CallerIdentityResolver.containsReference("${caller:userId}"));
        assertFalse(CallerIdentityResolver.containsReference("${vault:key}"));
        assertFalse(CallerIdentityResolver.containsReference(null));
    }

    // ==================== Resolution ====================

    @Test
    @DisplayName("the caller's token is injected for a same-origin call")
    void resolvesTokenForSameOrigin() {
        withCaller(new CallerIdentity("jwt-abc", "alice", SELF));
        assertEquals("Bearer jwt-abc", resolver.resolveValue("Bearer ${caller:token}", SELF_TARGET));
    }

    @Test
    @DisplayName("the default port is equivalent to the explicit one")
    void treatsDefaultPortAsEquivalent() {
        withCaller(new CallerIdentity("jwt-abc", "alice", "https://eddi.example:443"));
        assertEquals("Bearer jwt-abc", resolver.resolveValue("Bearer ${caller:token}", URI.create("https://eddi.example:443/x")));
    }

    @Test
    void resolvesUserId() {
        withCaller(new CallerIdentity("jwt-abc", "alice", SELF));
        assertEquals("alice", resolver.resolveValue("${caller:userId}", SELF_TARGET));
    }

    @Test
    @DisplayName("an anonymous caller yields an empty userId rather than failing")
    void resolvesMissingUserIdToEmpty() {
        withCaller(new CallerIdentity("jwt-abc", null, SELF));
        assertEquals("", resolver.resolveValue("${caller:userId}", SELF_TARGET));
    }

    @Test
    @DisplayName("a token containing regex replacement characters survives intact")
    void escapesReplacementCharacters() {
        withCaller(new CallerIdentity("a$b\\c", "alice", SELF));
        assertEquals("Bearer a$b\\c", resolver.resolveValue("Bearer ${caller:token}", SELF_TARGET));
    }

    @Test
    void resolvesMultipleReferencesInOneValue() {
        withCaller(new CallerIdentity("jwt-abc", "alice", SELF));
        assertEquals("alice/jwt-abc", resolver.resolveValue("${caller:userId}/${caller:token}", SELF_TARGET));
    }

    // ==================== Refusals ====================

    @Test
    @DisplayName("the token is never forwarded to a different host")
    void refusesCrossOriginHost() {
        withCaller(new CallerIdentity("jwt-abc", "alice", SELF));
        var e = assertThrows(CallerIdentityException.class,
                () -> resolver.resolveValue("Bearer ${caller:token}", URI.create("https://evil.example/collect")));
        assertTrue(e.getMessage().contains("origin the caller came from"));
    }

    @Test
    @DisplayName("a different port is a different origin")
    void refusesCrossOriginPort() {
        withCaller(new CallerIdentity("jwt-abc", "alice", "https://eddi.example:443"));
        assertThrows(CallerIdentityException.class,
                () -> resolver.resolveValue("Bearer ${caller:token}", URI.create("https://eddi.example:8443/x")));
    }

    @Test
    @DisplayName("downgrading the scheme is a different origin")
    void refusesSchemeDowngrade() {
        withCaller(new CallerIdentity("jwt-abc", "alice", "https://eddi.example:443"));
        assertThrows(CallerIdentityException.class,
                () -> resolver.resolveValue("Bearer ${caller:token}", URI.create("http://eddi.example/x")));
    }

    @Test
    @DisplayName("userId is not a secret, so cross-origin use is still allowed")
    void allowsUserIdCrossOrigin() {
        withCaller(new CallerIdentity("jwt-abc", "alice", SELF));
        assertEquals("alice", resolver.resolveValue("${caller:userId}", URI.create("https://partner.example/x")));
    }

    @Test
    @DisplayName("an unauthenticated turn fails loudly instead of sending an empty bearer")
    void refusesWithoutCaller() {
        withCaller(null);
        var e = assertThrows(CallerIdentityException.class, () -> resolver.resolveValue("Bearer ${caller:token}", SELF_TARGET));
        assertTrue(e.getMessage().contains("no authenticated"));
    }

    @Test
    void refusesWhenCallerHasNoToken() {
        withCaller(new CallerIdentity(null, "alice", SELF));
        assertThrows(CallerIdentityException.class, () -> resolver.resolveValue("Bearer ${caller:token}", SELF_TARGET));
    }

    @Test
    @DisplayName("an unknown caller origin never counts as a match")
    void refusesWhenOriginUnknown() {
        withCaller(new CallerIdentity("jwt-abc", "alice", null));
        assertThrows(CallerIdentityException.class, () -> resolver.resolveValue("Bearer ${caller:token}", SELF_TARGET));
    }

    @Test
    void refusesWhenFeatureDisabled() {
        var disabled = new CallerIdentityResolver(context, false);
        withCaller(new CallerIdentity("jwt-abc", "alice", SELF));
        var e = assertThrows(CallerIdentityException.class, () -> disabled.resolveValue("Bearer ${caller:token}", SELF_TARGET));
        assertTrue(e.getMessage().contains("disabled"));
    }

    // ==================== Tokens must stay out of URLs ====================

    @Test
    @DisplayName("a token reference in a query parameter is rejected")
    void rejectsTokenInQueryParameter() {
        var e = assertThrows(CallerIdentityException.class, () -> resolver.rejectTokenReference("${caller:token}", "a query parameter"));
        assertTrue(e.getMessage().contains("request header"));
    }

    @Test
    @DisplayName("userId in a query parameter is fine")
    void allowsUserIdInQueryParameter() {
        resolver.rejectTokenReference("${caller:userId}", "a query parameter");
    }
}
