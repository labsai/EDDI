/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.security.CallerIdentityResolver.CallerIdentityException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    @DisplayName("a caller with no principal name fails closed, like the token does")
    void refusesUserIdWithoutPrincipal() {
        // Emitting "" would send a header the receiving API cannot attribute, and
        // the failure would surface far from its cause.
        withCaller(new CallerIdentity("jwt-abc", null, SELF));
        var e = assertThrows(CallerIdentityException.class, () -> resolver.resolveValue("${caller:userId}", SELF_TARGET));
        assertTrue(e.getMessage().contains("no principal name"));
    }

    @Test
    void refusesBlankUserId() {
        withCaller(new CallerIdentity("jwt-abc", "   ", SELF));
        assertThrows(CallerIdentityException.class, () -> resolver.resolveValue("${caller:userId}", SELF_TARGET));
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
    @DisplayName("a request body rejects userId too, not just token")
    void rejectsAnyReferenceInABody() {
        // Bodies are not caller-resolved at all, so userId there is exactly as
        // broken as a token — it ships as a literal placeholder.
        var e = assertThrows(CallerIdentityException.class, () -> resolver.rejectAnyReference("{\"u\":\"${caller:userId}\"}", "a request body"));
        assertTrue(e.getMessage().contains("${caller:userId}"), e.getMessage());
        assertThrows(CallerIdentityException.class, () -> resolver.rejectAnyReference("${caller:token}", "a request body"));
    }

    @Test
    void aBodyWithNoReferencePassesThrough() {
        resolver.rejectAnyReference("{\"plain\":\"json\"}", "a request body");
        resolver.rejectAnyReference(null, "a request body");
    }

    @Test
    @DisplayName("outcomes are counted with a fixed, non-sensitive tag vocabulary")
    void recordsResolutionOutcomes() {
        var registry = new SimpleMeterRegistry();
        resolver.meterRegistry = registry;

        when(context.current()).thenReturn(new CallerIdentity("jwt-abc", "alice", SELF));
        resolver.resolveValue("Bearer ${caller:token}", URI.create(SELF + "/x"));
        assertEquals(1.0, registry.counter("eddi.caller.identity.resolution", "outcome", "resolved", "reference", "token").count());

        // A refusal is the signal worth alerting on — a config aiming the caller's
        // token at a third party.
        assertThrows(CallerIdentityException.class,
                () -> resolver.resolveValue("Bearer ${caller:token}", URI.create("https://elsewhere.example/x")));
        assertEquals(1.0, registry.counter("eddi.caller.identity.resolution", "outcome", "cross_origin", "reference", "token").count());

        // No tag anywhere may carry the token, the user id or the origin.
        var tagValues = registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream()).map(t -> t.getValue()).toList();
        assertFalse(tagValues.contains("jwt-abc"), "the token must never become a metric tag");
        assertFalse(tagValues.contains("alice"), "the user id must never become a metric tag");
        assertFalse(tagValues.contains(SELF), "the origin must never become a metric tag");
    }

    @Test
    @DisplayName("an over-long caller key cannot slip past the rejection by outrunning the pattern")
    void overlongCallerReferenceIsStillRejected() {
        // The scan is bounded to keep it linear, and this pattern is used only to
        // REJECT — so a reference too long for the bound is not "allowed", it is
        // INVISIBLE, and an invisible reference is shipped to the API as a literal
        // placeholder. Making the bound the bypass would defeat the check.
        String overlong = "${caller:" + "x".repeat(65) + "}";

        assertThrows(CallerIdentityException.class, () -> resolver.rejectAnyReference(overlong, "a request body"));
        assertThrows(CallerIdentityException.class, () -> resolver.rejectUnsupportedReference(overlong));
    }

    @Test
    @DisplayName("the bare Qute form is rejected, not shipped as text")
    void rejectsBareQuteFormInABody() {
        // {caller:token} is the natural Qute namespace syntax, so it is an easy
        // mistake. The resolver returns it unchanged — it never adds the '$' — so it
        // is never substituted and would reach the API verbatim.
        assertThrows(CallerIdentityException.class, () -> resolver.rejectAnyReference("{\"t\":\"{caller:token}\"}", "a request body"));
    }

    @Test
    @DisplayName("the bare Qute token form is refused in a query parameter too")
    void rejectsBareQuteTokenInQueryParameter() {
        assertThrows(CallerIdentityException.class, () -> resolver.rejectTokenReference("{caller:token}", "a query parameter"));

        // The bare userId form clears the token-only check — that check is about
        // where a *token* may go...
        resolver.rejectTokenReference("{caller:userId}", "a query parameter");
        // ...but it is still refused when the value is resolved, because nothing
        // ever substitutes the bare form. Unlike ${caller:userId}, which resolves
        // here, this one cannot work end to end and says so.
        assertThrows(CallerIdentityException.class, () -> resolver.resolveValue("{caller:userId}", URI.create("https://eddi.example/x")));
    }

    @Test
    @DisplayName("a typo'd reference fails loudly instead of shipping as a placeholder")
    void rejectsUnsupportedReference() {
        // Qute leaves the whole caller namespace alone, so nothing else would catch
        // this and the API would receive the literal text.
        var e = assertThrows(CallerIdentityException.class,
                () -> resolver.resolveValue("Bearer ${caller:tokn}", URI.create("https://eddi.example/x")));
        assertTrue(e.getMessage().contains("${caller:tokn}"), e.getMessage());
        assertTrue(e.getMessage().contains("not a supported caller reference"), e.getMessage());
    }

    @Test
    @DisplayName("an unsupported reference is caught even next to a valid one")
    void rejectsUnsupportedReferenceAlongsideAValidOne() {
        assertThrows(CallerIdentityException.class,
                () -> resolver.resolveValue("${caller:userId}/${caller:nope}", URI.create("https://eddi.example/x")));
    }

    @Test
    @DisplayName("userId in a query parameter is fine")
    void allowsUserIdInQueryParameter() {
        resolver.rejectTokenReference("${caller:userId}", "a query parameter");
    }

    // ==================== Redaction before persistence ====================

    @Test
    @DisplayName("the token is redacted out of an unconventionally named header")
    void redactsTokenByValue() {
        // Header-name matching misses this one, so value matching has to catch it.
        withCaller(new CallerIdentity("jwt-abc", "alice", SELF));
        assertEquals("Bearer <REDACTED>", resolver.redactCallerToken("Bearer jwt-abc", "<REDACTED>"));
    }

    @Test
    void redactsEveryOccurrence() {
        withCaller(new CallerIdentity("jwt-abc", "alice", SELF));
        assertEquals("x=* y=*", resolver.redactCallerToken("x=jwt-abc y=jwt-abc", "*"));
    }

    @Test
    @DisplayName("a value that does not contain the token is left alone")
    void leavesUnrelatedValuesUnredacted() {
        withCaller(new CallerIdentity("jwt-abc", "alice", SELF));
        assertEquals("application/json", resolver.redactCallerToken("application/json", "<REDACTED>"));
    }

    @Test
    void redactionIsANoOpWithoutACaller() {
        withCaller(null);
        assertEquals("Bearer jwt-abc", resolver.redactCallerToken("Bearer jwt-abc", "<REDACTED>"));
    }

    @Test
    void redactionHandlesNullAndEmpty() {
        withCaller(new CallerIdentity("jwt-abc", "alice", SELF));
        assertNull(resolver.redactCallerToken(null, "<REDACTED>"));
        assertEquals("", resolver.redactCallerToken("", "<REDACTED>"));
    }

    @Test
    @DisplayName("a config value built to make the reference scan quadratic is still checked in linear time")
    void unsupportedReferenceScanDoesNotDegrade() {
        // The optional leading $ means a bare "{caller:" also starts a match, so an
        // unbounded span gave one scan of the rest of the string per occurrence.
        // A header value is author-supplied, so that is reachable from configuration.
        String hostile = "{{caller:".repeat(20_000);

        long startedAt = System.nanoTime();
        // Rejected, not ignored: this really is a malformed caller reference, and
        // the bounded pattern has to SEE it in order to say so. The guard being
        // made here is that neither the scan nor the verdict depends on how long
        // the value is.
        assertThrows(CallerIdentityException.class, () -> resolver.rejectUnsupportedReference(hostile));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(elapsedMillis < 2_000,
                "scanning 20k repetitions took " + elapsedMillis + "ms; the reference span is unbounded again");
    }

    @Test
    @DisplayName("a typo is still reported, which is the whole reason the scan exists")
    void stillReportsATypo() {
        var error = assertThrows(CallerIdentityException.class, () -> resolver.rejectUnsupportedReference("Bearer ${caller:tokn}"));

        assertTrue(error.getMessage().contains("tokn"), error.getMessage());
    }
}
