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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression cover for the Platform Operator's self-URL defect, on the token
 * side.
 * <p>
 * The operator's tools have to target the address EDDI can reach
 * <em>itself</em> at — which, whenever a tunnel, a published-port remap or a
 * reverse proxy sits in front, is not the origin the admin's browser used. On
 * an OIDC-protected deployment those same tools authenticate with
 * {@code ${caller:token}}, and the strict same-origin rule would refuse the
 * token for exactly the address that is correct: the operator would have been
 * fixed into a different failure rather than out of one.
 * <p>
 * So: the caller's own origin resolves, this process's own address resolves,
 * and nothing else does.
 *
 * @author ginccc
 */
@DisplayName("caller token forwarding to EDDI's own address")
class CallerIdentitySelfOriginTest {

    /** What the browser used — an SSH tunnel in front of the container. */
    private static final String BROWSER_ORIGIN = "http://localhost:7080";

    /** What EDDI can reach itself at, inside the container. */
    private static final int SELF_PORT = 7070;
    private static final URI SELF_TARGET = URI.create("http://127.0.0.1:7070/agentstore/agents/descriptors");

    private CallerIdentityContext context;
    private CallerIdentityResolver resolver;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        context = mock(CallerIdentityContext.class);
        registry = new SimpleMeterRegistry();
        resolver = new CallerIdentityResolver(context, true);
        resolver.meterRegistry = registry;
        resolver.selfUrlResolver = new SelfUrlResolver(Optional.empty(), SELF_PORT);
        when(context.current()).thenReturn(new CallerIdentity("tok-abc", "alice", normalized(BROWSER_ORIGIN)));
    }

    /** {@code CallerIdentity.origin()} holds the normalized form. */
    private static String normalized(String origin) {
        return OriginMatcher.normalize(URI.create(origin));
    }

    /**
     * THE regression test for defect 1: an operator provisioned with EDDI's own
     * address, used by a caller who arrived on a different origin, still gets its
     * token. Reverting the {@code isSelf} branch in
     * {@link CallerIdentityResolver#resolveValue} makes this throw.
     */
    @Test
    @DisplayName("the token resolves for this deployment's own address even though the caller arrived elsewhere")
    void resolvesForSelfWhenCallerOriginDiffers() {
        assertEquals("Bearer tok-abc", resolver.resolveValue("Bearer ${caller:token}", SELF_TARGET));
    }

    @Test
    @DisplayName("resolution against self is counted under its own outcome tag")
    void countsSelfResolutionSeparately() {
        resolver.resolveValue("Bearer ${caller:token}", SELF_TARGET);
        var counter = registry.find("eddi.caller.identity.resolution").tag("outcome", "resolved_self").counter();
        assertTrue(counter != null && counter.count() == 1.0d,
                "a self-origin resolution should be distinguishable from a plain same-origin one");
    }

    @Test
    @DisplayName("the caller's own origin still resolves")
    void callerOriginStillResolves() {
        assertEquals("Bearer tok-abc",
                resolver.resolveValue("Bearer ${caller:token}", URI.create(BROWSER_ORIGIN + "/agentstore/agents")));
    }

    /**
     * The exception is for EDDI's address, not for "any loopback address": a
     * neighbouring process on another port is a different service.
     */
    @Test
    @DisplayName("loopback on a DIFFERENT port is not this process")
    void refusesOtherLoopbackPorts() {
        var e = assertThrows(CallerIdentityException.class,
                () -> resolver.resolveValue("Bearer ${caller:token}", URI.create("http://127.0.0.1:9999/agentstore/agents")));
        assertTrue(e.getMessage().contains("may only be sent back to the origin the caller came from"), e.getMessage());
    }

    @Test
    @DisplayName("a third-party host is still refused, and counted as cross-origin")
    void refusesThirdParties() {
        assertThrows(CallerIdentityException.class,
                () -> resolver.resolveValue("Bearer ${caller:token}", URI.create("https://evil.example/collect")));
        var counter = registry.find("eddi.caller.identity.resolution").tag("outcome", "cross_origin").counter();
        assertTrue(counter != null && counter.count() == 1.0d, "a refusal must still be counted as cross_origin");
    }

    /**
     * Field injection means a directly constructed resolver has none. It must then
     * behave exactly as it did before this branch existed rather than throwing an
     * NPE on every cross-origin call.
     */
    @Test
    @DisplayName("without a SelfUrlResolver the strict same-origin rule applies unchanged")
    void degradesToStrictSameOrigin() {
        resolver.selfUrlResolver = null;
        assertThrows(CallerIdentityException.class, () -> resolver.resolveValue("Bearer ${caller:token}", SELF_TARGET));
        assertEquals("Bearer tok-abc",
                resolver.resolveValue("Bearer ${caller:token}", URI.create(BROWSER_ORIGIN + "/agentstore/agents")));
    }

    /**
     * The self-origin exception is about WHERE the token may go, not about the
     * other guards. A token in a query parameter stays rejected regardless.
     */
    @Test
    @DisplayName("the self exception does not relax the headers-only rule")
    void headersOnlyStillHolds() {
        assertThrows(CallerIdentityException.class,
                () -> resolver.rejectTokenReference("access_token=${caller:token}", "a query parameter"));
    }
}
