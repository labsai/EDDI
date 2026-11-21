/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AnonymousAuthenticationRequest;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenAiPermitModeAuthMechanism} — the piece that lets
 * the shared {@code /v1} API key reach {@link OpenAiAuthFilter} with OIDC on.
 * <p>
 * Regression for security review finding B7: with
 * {@code quarkus.oidc.tenant-enabled=true} and the default
 * {@code http-policy=permit}, every {@code Authorization: Bearer sk-…} request
 * got a 401 from the OIDC layer, because proactive authentication handed the
 * opaque key to the OIDC bearer mechanism regardless of the {@code permit}
 * permission. The {@link Ordering} tests reproduce what Quarkus'
 * {@code HttpAuthenticator} does with the two mechanisms; the property test
 * pins the shipped configuration the mechanism depends on.
 */
class OpenAiPermitModeAuthMechanismTest {

    private static final List<String> SHIPPED_METHODS = List.of("GET", "POST", "OPTIONS");

    private static OpenAiPermitModeAuthMechanism mechanism(Consumer<OpenAiTestFixtures.ConfigBuilder> customizer,
                                                           String permissionPolicy, List<String> methods, boolean inclusive) {
        return new OpenAiPermitModeAuthMechanism(OpenAiTestFixtures.config(customizer),
                Optional.ofNullable(permissionPolicy), Optional.ofNullable(methods), inclusive, 1001);
    }

    private static OpenAiPermitModeAuthMechanism permitMode() {
        return mechanism(b -> {
            b.enabled = true;
            b.apiKey = "sk-eddi-secret";
            b.httpPolicy = OpenAiCompatConfig.POLICY_PERMIT;
        }, "permit", SHIPPED_METHODS, false);
    }

    private static RoutingContext request(HttpMethod method, String normalizedPath) {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(method);
        when(context.request()).thenReturn(request);
        when(context.normalizedPath()).thenReturn(normalizedPath);
        return context;
    }

    /** An identity manager whose anonymous identity is a recognizable mock. */
    private static IdentityProviderManager anonymousIdentityManager(SecurityIdentity anonymous) {
        IdentityProviderManager manager = mock(IdentityProviderManager.class);
        when(manager.authenticate(any())).thenReturn(Uni.createFrom().item(anonymous));
        return manager;
    }

    @Nested
    class Activation {

        @Test
        void active_inPermitModeWithTheShippedPermission() {
            assertTrue(permitMode().isActive());
        }

        @Test
        void inactive_whenAdapterDisabled() {
            assertFalse(mechanism(b -> b.enabled = false, "permit", SHIPPED_METHODS, false).isActive());
        }

        @Test
        void inactive_whenHttpPolicyIsAuthenticated() {
            // OIDC mode: Quarkus must keep validating the JWT exactly as before.
            var mechanism = mechanism(b -> b.httpPolicy = OpenAiCompatConfig.POLICY_AUTHENTICATED,
                    "authenticated", SHIPPED_METHODS, false);
            assertFalse(mechanism.isActive());
        }

        @Test
        void inactive_whenOnlyTheQuarkusPermissionWasOverridden() {
            // eddi.openai-compat.http-policy=permit, but someone set the Quarkus
            // permission itself to authenticated: stepping aside would leave neither
            // check, so the mechanism must not.
            assertFalse(mechanism(b -> {
            }, "authenticated", SHIPPED_METHODS, false).isActive());
        }

        @Test
        void inactive_whenThePermissionPolicyIsMissing() {
            assertFalse(mechanism(b -> {
            }, null, SHIPPED_METHODS, false).isActive());
        }

        @Test
        void inactive_forAnUnknownHttpPolicy() {
            assertFalse(mechanism(b -> b.httpPolicy = "Permit", "permit", SHIPPED_METHODS, false).isActive());
        }

        @Test
        void inactive_withInclusiveAuthentication() {
            // Inclusive auth runs OIDC after this mechanism anyway; stepping in would
            // only break the header-less requests that work today.
            assertFalse(mechanism(b -> {
            }, "permit", SHIPPED_METHODS, true).isActive());
        }
    }

    @Nested
    class Matching {

        @ParameterizedTest
        @ValueSource(strings = {"/v1", "/v1/", "/v1/models", "/v1/models/support", "/v1/chat/completions"})
        void applies_toTheAdapterPaths(String path) {
            assertTrue(permitMode().appliesTo(request(HttpMethod.POST, path)), path);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/", "/v10/models", "/v1x", "/agents/v1", "/agentstore/agents", "/mcp", "/q/metrics",
                "/V1/models"})
        void doesNotApply_elsewhere(String path) {
            assertFalse(permitMode().appliesTo(request(HttpMethod.GET, path)), path);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/v1;x/chat/completions", "/v1/%2e%2e/agentstore/agents", "/v1/..%2fagentstore"})
        void doesNotApply_whenTheTwoNormalizationsDisagree(String path) {
            // The router path and the fully-normalized security path must BOTH sit
            // under /v1. A spelling that only one of them places there is left to OIDC
            // (fail closed), whatever the permission matcher then decides.
            assertFalse(permitMode().appliesTo(request(HttpMethod.GET, path)), path);
        }

        @Test
        void doesNotApply_toAMethodThePermissionDoesNotList() {
            // PUT /v1/... falls to the authenticated catch-all, so OIDC must still run.
            var mechanism = permitMode();
            assertFalse(mechanism.appliesTo(request(HttpMethod.PUT, "/v1/models")));
            assertFalse(mechanism.appliesTo(request(HttpMethod.DELETE, "/v1/models")));
            assertTrue(mechanism.appliesTo(request(HttpMethod.GET, "/v1/models")));
            assertTrue(mechanism.appliesTo(request(HttpMethod.OPTIONS, "/v1/models")));
        }

        @Test
        void appliesToEveryMethod_whenThePermissionListsNone() {
            var mechanism = mechanism(b -> {
            }, "permit", null, false);
            assertTrue(mechanism.appliesTo(request(HttpMethod.PUT, "/v1/models")));
        }

        @Test
        void methodsAreComparedCaseInsensitively() {
            var mechanism = mechanism(b -> {
            }, "permit", List.of(" get ", "post"), false);
            assertTrue(mechanism.appliesTo(request(HttpMethod.GET, "/v1/models")));
            assertTrue(mechanism.appliesTo(request(HttpMethod.POST, "/v1/chat/completions")));
        }

        @Test
        void doesNotApply_whenInactive() {
            var mechanism = mechanism(b -> b.httpPolicy = OpenAiCompatConfig.POLICY_AUTHENTICATED,
                    "authenticated", SHIPPED_METHODS, false);
            assertFalse(mechanism.appliesTo(request(HttpMethod.POST, "/v1/chat/completions")));
        }

        @Test
        void doesNotApply_withoutAPath() {
            assertFalse(permitMode().appliesTo(request(HttpMethod.GET, null)));
            assertFalse(permitMode().appliesTo(null));
        }
    }

    @Nested
    class Authentication {

        @Test
        void returnsTheAnonymousIdentity_forAV1Request() {
            SecurityIdentity anonymous = mock(SecurityIdentity.class);
            IdentityProviderManager manager = anonymousIdentityManager(anonymous);
            RoutingContext context = request(HttpMethod.POST, "/v1/chat/completions");

            SecurityIdentity identity = permitMode().authenticate(context, manager).await().indefinitely();

            assertSame(anonymous, identity);
            ArgumentCaptor<AuthenticationRequest> captor = ArgumentCaptor.forClass(AuthenticationRequest.class);
            verify(manager).authenticate(captor.capture());
            assertInstanceOf(AnonymousAuthenticationRequest.class, captor.getValue(),
                    "only the anonymous identity may come out of this mechanism — it verifies nothing");
            assertSame(context, HttpSecurityUtils.getRoutingContextAttribute(captor.getValue()));
        }

        @Test
        void returnsNoIdentity_elsewhere_soTheNextMechanismRuns() {
            IdentityProviderManager manager = mock(IdentityProviderManager.class);

            assertNull(permitMode().authenticate(request(HttpMethod.GET, "/agentstore/agents"), manager)
                    .await().indefinitely());
            verify(manager, never()).authenticate(any());
        }

        @Test
        void returnsNoIdentity_inOidcMode() {
            IdentityProviderManager manager = mock(IdentityProviderManager.class);
            var mechanism = mechanism(b -> b.httpPolicy = OpenAiCompatConfig.POLICY_AUTHENTICATED,
                    "authenticated", SHIPPED_METHODS, false);

            assertNull(mechanism.authenticate(request(HttpMethod.POST, "/v1/chat/completions"), manager)
                    .await().indefinitely());
            verify(manager, never()).authenticate(any());
        }

        @Test
        void neverChallenges() {
            assertNull(permitMode().getChallenge(request(HttpMethod.GET, "/v1/models")).await().indefinitely());
            assertNull(permitMode().getChallenge(request(HttpMethod.GET, "/agentstore/agents")).await().indefinitely());
        }

        @Test
        void requiresNoIdentityProviderOfItsOwn() {
            // An empty set means Quarkus registers the mechanism unconditionally; a
            // declared type without a matching provider would silently drop it.
            assertTrue(permitMode().getCredentialTypes().isEmpty());
        }
    }

    /**
     * What Quarkus' {@code HttpAuthenticator} does with the mechanisms: sort by
     * descending priority, ask each in turn, stop at the first identity (inclusive
     * authentication off), and fail the request — 401 with the challenge — on the
     * first failure.
     */
    @Nested
    class Ordering {

        /** Stands in for quarkus-oidc's bearer mechanism handed an opaque key. */
        private HttpAuthenticationMechanism oidcRejectingOpaqueTokens(int priority) {
            HttpAuthenticationMechanism oidc = mock(HttpAuthenticationMechanism.class);
            when(oidc.getPriority()).thenReturn(priority);
            when(oidc.authenticate(any(), any())).thenReturn(Uni.createFrom().failure(
                    new AuthenticationFailedException("not a JWT")));
            return oidc;
        }

        private SecurityIdentity runChain(RoutingContext context, IdentityProviderManager manager,
                                          HttpAuthenticationMechanism... mechanisms) {
            List<HttpAuthenticationMechanism> sorted = new ArrayList<>(List.of(mechanisms));
            sorted.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
            for (HttpAuthenticationMechanism mechanism : sorted) {
                SecurityIdentity identity = mechanism.authenticate(context, manager).await().indefinitely();
                if (identity != null) {
                    return identity;
                }
            }
            return null;
        }

        @Test
        void ranksAboveOidc_atTheDefaultOidcPriority() {
            assertTrue(permitMode().getPriority() > 1001);
        }

        @Test
        void ranksAboveOidc_whenOidcPriorityIsRaised() {
            var mechanism = new OpenAiPermitModeAuthMechanism(OpenAiTestFixtures.enabledConfig(),
                    Optional.of("permit"), Optional.of(SHIPPED_METHODS), false, 5000);
            assertEquals(5001, mechanism.getPriority());
            var saturated = new OpenAiPermitModeAuthMechanism(OpenAiTestFixtures.enabledConfig(),
                    Optional.of("permit"), Optional.of(SHIPPED_METHODS), false, Integer.MAX_VALUE);
            assertEquals(Integer.MAX_VALUE, saturated.getPriority());
        }

        @Test
        void sharedKeyRequest_neverReachesOidc() {
            SecurityIdentity anonymous = mock(SecurityIdentity.class);
            IdentityProviderManager manager = anonymousIdentityManager(anonymous);
            HttpAuthenticationMechanism oidc = oidcRejectingOpaqueTokens(1001);
            RoutingContext context = request(HttpMethod.POST, "/v1/chat/completions");

            SecurityIdentity identity = runChain(context, manager, oidc, permitMode());

            assertSame(anonymous, identity);
            verify(oidc, never()).authenticate(any(), any());
        }

        @Test
        void otherPaths_stillGoToOidc() {
            IdentityProviderManager manager = mock(IdentityProviderManager.class);
            HttpAuthenticationMechanism oidc = mock(HttpAuthenticationMechanism.class);
            SecurityIdentity user = mock(SecurityIdentity.class);
            when(oidc.getPriority()).thenReturn(1001);
            when(oidc.authenticate(any(), any())).thenReturn(Uni.createFrom().item(user));
            RoutingContext context = request(HttpMethod.GET, "/agentstore/agents");

            assertSame(user, runChain(context, manager, oidc, permitMode()));
        }

        @Test
        void oidcMode_stillGoesToOidc() {
            IdentityProviderManager manager = mock(IdentityProviderManager.class);
            HttpAuthenticationMechanism oidc = mock(HttpAuthenticationMechanism.class);
            SecurityIdentity user = mock(SecurityIdentity.class);
            when(oidc.getPriority()).thenReturn(1001);
            when(oidc.authenticate(any(), any())).thenReturn(Uni.createFrom().item(user));
            var mechanism = mechanism(b -> b.httpPolicy = OpenAiCompatConfig.POLICY_AUTHENTICATED,
                    "authenticated", SHIPPED_METHODS, false);

            assertSame(user, runChain(request(HttpMethod.POST, "/v1/chat/completions"), manager, oidc, mechanism));
        }
    }

    /**
     * The mechanism keys on the shipped {@code openai-compat} permission. If its
     * paths, policy expression or methods move, this fails rather than leaving the
     * mechanism and the permission describing different requests.
     */
    @Test
    void shippedPermission_isWhatTheMechanismAssumes() throws IOException {
        // The main file itself: src/test/resources carries its own
        // application.properties,
        // which shadows it on the test classpath.
        Path file = Path.of(System.getProperty("basedir", "."), "src", "main", "resources", "application.properties");
        assertTrue(Files.isRegularFile(file), "Expected the application config at " + file);
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            properties.load(in);
        }
        assertEquals("/v1/*", properties.getProperty("quarkus.http.auth.permission.openai-compat.paths"));
        assertEquals("${eddi.openai-compat.http-policy:permit}",
                properties.getProperty("quarkus.http.auth.permission.openai-compat.policy"));
        assertEquals("GET,POST,OPTIONS", properties.getProperty("quarkus.http.auth.permission.openai-compat.methods"));
        assertEquals(OpenAiCompatConfig.POLICY_PERMIT, properties.getProperty("eddi.openai-compat.http-policy"));
        assertNull(properties.getProperty("quarkus.http.auth.inclusive"),
                "inclusive authentication would run OIDC on /v1 after the mechanism");
        assertNull(properties.getProperty("quarkus.http.auth.permission.openai-compat.auth-mechanism"),
                "a path-specific mechanism would exclude this one, which names no credential transport");
    }
}
