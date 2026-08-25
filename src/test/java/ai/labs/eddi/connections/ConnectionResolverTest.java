/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every rule in here is a refusal, and every refusal exists because the
 * alternative — sending no credential, or sending the wrong one — fails in a
 * way that is either invisible or actively harmful.
 */
class ConnectionResolverTest {

    private static final URI ALLOWED_TARGET = URI.create("https://api.atlassian.com/ex/jira/issue/1");

    private ConnectionRegistry registry;
    private SecretResolver secretResolver;
    private GlobalVariableResolver globalVariableResolver;
    private CallerIdentityContext callerIdentityContext;
    private AccessTokenSupplier accessTokenSupplier;
    private ResolutionPrincipalContext resolutionPrincipalContext;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectionRegistry.class);
        secretResolver = mock(SecretResolver.class);
        globalVariableResolver = mock(GlobalVariableResolver.class);
        callerIdentityContext = mock(CallerIdentityContext.class);
        accessTokenSupplier = mock(AccessTokenSupplier.class);
        resolutionPrincipalContext = new ResolutionPrincipalContext();

        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(secretResolver.resolveValue(anyString()))
                .thenAnswer(i -> i.<String>getArgument(0).replace("${vault:jira-token}", "live-token").replace("${vault:jira-password}", "hunter2"));
    }

    @AfterEach
    void unbindPrincipal() {
        // The binding is a ThreadLocal and JUnit reuses the thread, so a leaked
        // principal would silently authorise the next test — which for PER_USER is
        // the difference between proving a refusal and proving nothing.
        resolutionPrincipalContext.clear();
    }

    private ConnectionResolver resolver(boolean authorizationEnabled) {
        var resolver = new ConnectionResolver(registry, new CredentialReferenceResolver(secretResolver, globalVariableResolver),
                callerIdentityContext, new SimpleMeterRegistry(), accessTokenSupplier, authorizationEnabled);
        resolver.resolutionPrincipalContext = resolutionPrincipalContext;
        return resolver;
    }

    /**
     * Binds the principal the pipeline would have bound for this turn.
     * <p>
     * Every PER_USER case has to state this explicitly, which is the point: the
     * credential follows the CONVERSATION, and a test that did not say whose
     * conversation it is would be describing a turn that cannot happen.
     */
    private void boundPrincipal(String userId, ResolutionPrincipal.Provenance provenance) {
        resolutionPrincipalContext.bind(new ResolutionPrincipal(userId, provenance));
    }

    private void register(ConnectionConfiguration connection) {
        when(registry.require(any(ConnectionReference.class))).thenReturn(connection);
        lenient().when(registry.find(any(ConnectionReference.class))).thenReturn(Optional.of(connection));
    }

    private static ConnectionConfiguration staticConnection() {
        var connection = new ConnectionConfiguration();
        connection.setName("jira");
        connection.setAuthType(AuthType.STATIC);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://api.atlassian.com"));
        var auth = new StaticAuth();
        auth.setHeaderName("Authorization");
        auth.setValueTemplate("Bearer ${vault:jira-token}");
        connection.setStaticAuth(auth);
        return connection;
    }

    @Nested
    @DisplayName("CALLER_SUPPLIED")
    class CallerSupplied {

        private static final URI GNOWBE_TARGET = URI.create("https://api.gnowbe.com/api/v2/courses");

        private ConnectionConfiguration gnowbeConnection() {
            var connection = new ConnectionConfiguration();
            connection.setName("gnowbe");
            connection.setAuthType(AuthType.STATIC);
            connection.setBinding(Binding.CALLER_SUPPLIED);
            connection.setBaseUrlAllowlist(List.of("https://api.gnowbe.com"));
            var auth = new StaticAuth();
            auth.setHeaderName("x-api-key");
            connection.setStaticAuth(auth);
            return connection;
        }

        private void callerSupplies(Map<String, String> credentials) {
            when(callerIdentityContext.current()).thenReturn(new CallerIdentity(null, "gnowbe-backend", "https://eddi.example.com", credentials));
        }

        @Test
        @DisplayName("the caller's value is sent under the connection's header name")
        void sendsTheCallersCredential() {
            register(gnowbeConnection());
            callerSupplies(Map.of("gnowbe", "key-id:secret"));

            var credential = resolver(true).resolve("${connection:gnowbe}", GNOWBE_TARGET, null);

            assertEquals("x-api-key", credential.headerName());
            assertEquals("key-id:secret", credential.headerValue());
        }

        @Test
        @DisplayName("no credential on the request fails closed rather than calling out unauthenticated")
        void failsClosedWithoutCredential() {
            register(gnowbeConnection());
            callerSupplies(Map.of());

            var error = assertThrows(ConnectionException.class, () -> resolver(true).resolve("${connection:gnowbe}", GNOWBE_TARGET, null));

            assertEquals(ConnectionException.Reason.NO_CALLER_CREDENTIAL, error.getReason());
            assertTrue(error.getMessage().contains("gnowbe"), error.getMessage());
            assertTrue(error.getMessage().contains("resume"),
                    "the message must name the resume case, which is the non-obvious half: " + error.getMessage());
        }

        @Test
        @DisplayName("a credential for a different connection is not borrowed")
        void doesNotBorrowAnotherConnectionsCredential() {
            register(gnowbeConnection());
            callerSupplies(Map.of("some-other-system", "not-for-gnowbe"));

            var error = assertThrows(ConnectionException.class, () -> resolver(true).resolve("${connection:gnowbe}", GNOWBE_TARGET, null));

            assertEquals(ConnectionException.Reason.NO_CALLER_CREDENTIAL, error.getReason());
        }

        @Test
        @DisplayName("no caller identity at all — a scheduled turn — fails closed too")
        void failsClosedWithoutCallerIdentity() {
            register(gnowbeConnection());
            when(callerIdentityContext.current()).thenReturn(null);

            var error = assertThrows(ConnectionException.class, () -> resolver(true).resolve("${connection:gnowbe}", GNOWBE_TARGET, null));

            assertEquals(ConnectionException.Reason.NO_CALLER_CREDENTIAL, error.getReason());
        }

        @Test
        @DisplayName("the allowlist still bounds where the user's own credential may go")
        void refusesTargetOffTheAllowlist() {
            register(gnowbeConnection());
            callerSupplies(Map.of("gnowbe", "key-id:secret"));

            var error = assertThrows(ConnectionException.class,
                    () -> resolver(true).resolve("${connection:gnowbe}", URI.create("https://evil.example.com/collect"), null));

            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
        }

        @Test
        @DisplayName("withheld from discovery — a cached handshake would pin one caller's credential onto everybody")
        void withheldFromDiscovery() {
            register(gnowbeConnection());
            callerSupplies(Map.of("gnowbe", "key-id:secret"));

            assertTrue(resolver(true).resolveForDiscovery("${connection:gnowbe}", GNOWBE_TARGET).isEmpty());
        }

        @Test
        @DisplayName("resolves without a verified principal — the credential is the authority, not the id")
        void doesNotRequireVerifiedPrincipal() {
            // The driving deployment calls EDDI as one service principal with the end
            // user's key attached, so the bound principal is SELF_ASSERTED. Requiring a
            // verified one here would make the binding unusable in exactly the topology
            // it was built for — and it is not needed, because nothing is looked up by
            // principal: the caller hands over the credential itself.
            register(gnowbeConnection());
            boundPrincipal("end-user-42", ResolutionPrincipal.Provenance.SELF_ASSERTED);
            callerSupplies(Map.of("gnowbe", "key-id:secret"));

            var credential = resolver(true).resolve("${connection:gnowbe}", GNOWBE_TARGET, null);

            assertEquals("key-id:secret", credential.headerValue());
        }
    }

    @Nested
    @DisplayName("static and basic")
    class StaticAndBasic {

        @Test
        @DisplayName("a static connection resolves to its configured header")
        void resolvesStaticHeader() {
            register(staticConnection());

            var credential = resolver(false).resolve("${connection:jira}", ALLOWED_TARGET, null);

            assertEquals("Authorization", credential.headerName());
            assertEquals("Bearer live-token", credential.headerValue());
        }

        @Test
        @DisplayName("BASIC is base64-encoded here, so nobody has to vault a pre-encoded blob")
        void encodesBasic() {
            var connection = staticConnection();
            connection.setAuthType(AuthType.BASIC);
            connection.getStaticAuth().setUsername("svc-eddi");
            connection.getStaticAuth().setPasswordRef("${vault:jira-password}");
            register(connection);

            var credential = resolver(false).resolve("${connection:jira}", ALLOWED_TARGET, null);

            assertEquals("Basic " + Base64.getEncoder().encodeToString("svc-eddi:hunter2".getBytes(StandardCharsets.UTF_8)),
                    credential.headerValue());
        }

        @Test
        @DisplayName("an unresolved GLOBAL VARIABLE is refused too, not only a vault key")
        void refusesUnresolvedGlobalVariable() {
            // Checking only ${vault:} left half the guard missing: an unresolved
            // ${vars:} fails identically — the literal text goes out as the credential
            // and the provider answers 401 with nothing naming the missing variable.
            var connection = staticConnection();
            connection.getStaticAuth().setValueTemplate("Bearer ${vars:jira-token}");
            register(connection);

            var error = assertThrows(ConnectionException.class, () -> resolver(false).resolve("${connection:jira}", ALLOWED_TARGET, null));

            assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason());
            assertTrue(error.getMessage().contains("did not resolve"), error.getMessage());
        }

        @Test
        @DisplayName("an unresolved vault reference is refused, not sent as literal text")
        void refusesUnresolvedVaultReference() {
            var connection = staticConnection();
            connection.getStaticAuth().setValueTemplate("Bearer ${vault:missing-key}");
            register(connection);

            var error = assertThrows(ConnectionException.class, () -> resolver(false).resolve("${connection:jira}", ALLOWED_TARGET, null));

            assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason());
            assertTrue(error.getMessage().contains("did not resolve"), error.getMessage());
        }
    }

    @Nested
    @DisplayName("the target allowlist")
    class TargetAllowlist {

        @Test
        @DisplayName("a target outside the allowlist is refused — a config edit cannot redirect a credential")
        void refusesUnlistedTarget() {
            register(staticConnection());

            var error = assertThrows(ConnectionException.class,
                    () -> resolver(false).resolve("${connection:jira}", URI.create("https://evil.example.com/collect"), null));

            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
        }

        @Test
        @DisplayName("origins are compared canonically, not as strings")
        void comparesOriginsCanonically() {
            var connection = staticConnection();
            connection.setBaseUrlAllowlist(List.of("HTTPS://API.Atlassian.com"));
            register(connection);

            // A case-sensitive comparison here produces an allowlist that looks
            // configured and blocks everything.
            assertEquals("Bearer live-token", resolver(false).resolve("${connection:jira}", ALLOWED_TARGET, null).headerValue());
        }

        @Test
        @DisplayName("a malformed allowlist entry is a configuration error, never a silent match-all")
        void refusesMalformedAllowlistEntry() {
            var connection = staticConnection();
            // Reachable by import or a direct database write, which bypass the
            // write-time validator.
            connection.setBaseUrlAllowlist(List.of("api.atlassian.com"));
            register(connection);

            var error = assertThrows(ConnectionException.class, () -> resolver(false).resolve("${connection:jira}", ALLOWED_TARGET, null));

            assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason());
        }

        @Test
        @DisplayName("a resolve with no target is refused rather than skipping the check")
        void refusesMissingTarget() {
            register(staticConnection());

            assertThrows(ConnectionException.class, () -> resolver(false).resolve("${connection:jira}", null, null));
        }
    }

    @Nested
    @DisplayName("PER_USER needs a verified principal")
    class PerUser {

        private ConnectionConfiguration perUserConnection() {
            var connection = new ConnectionConfiguration();
            connection.setName("drive");
            connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
            connection.setBinding(Binding.PER_USER);
            connection.setBaseUrlAllowlist(List.of("https://api.atlassian.com"));
            var oauth = new OAuthConfig();
            oauth.setTokenUrl("https://auth.atlassian.com/oauth/token");
            oauth.setAuthorizationUrl("https://auth.atlassian.com/authorize");
            oauth.setClientId("client");
            oauth.setClientSecret("${vault:client-secret}");
            connection.setOauth(oauth);
            return connection;
        }

        @Test
        @DisplayName("a self-asserted user id is refused — nothing authenticated it")
        void refusesSelfAssertedPrincipal() {
            // The /v1 adapter in api-key mode believes a caller-supplied user id
            // verbatim. A conversation opened that way carries a real user id that
            // nobody verified, and releasing that user's stored SaaS tokens to whoever
            // asserted it is the whole hole this provenance exists to close.
            register(perUserConnection());
            boundPrincipal("alice", ResolutionPrincipal.Provenance.SELF_ASSERTED);

            var error = assertThrows(ConnectionException.class, () -> resolver(true).resolve("${connection:drive}", ALLOWED_TARGET, "alice"));

            assertEquals(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL, error.getReason());
            verify(accessTokenSupplier, never()).accessToken(any(), any());
        }

        @Test
        @DisplayName("a connection whose users are authenticated upstream may opt in to that")
        void honoursTheProxyOptIn() {
            // Delegating authentication to a front proxy is a real deployment, so the
            // refusal above has an escape hatch — per connection, default off, so
            // enabling it is a decision about one provider's tokens.
            var connection = perUserConnection();
            connection.setAllowUnverifiedPrincipal(true);
            register(connection);
            boundPrincipal("alice", ResolutionPrincipal.Provenance.SELF_ASSERTED);
            when(accessTokenSupplier.accessToken(any(), any())).thenReturn("ya29.token");

            assertEquals("Bearer ya29.token", resolver(true).resolve("${connection:drive}", ALLOWED_TARGET, "alice").headerValue());
            verify(accessTokenSupplier).accessToken(any(), eq("alice"));
        }

        @Test
        @DisplayName("with no resolvable user it refuses rather than falling back to the service grant")
        void refusesWithoutPrincipal() {
            // A scheduled run or a trigger: no conversation principal is bound, so
            // there is nobody to spend a credential on behalf of.
            register(perUserConnection());
            when(callerIdentityContext.current()).thenReturn(null);

            var error = assertThrows(ConnectionException.class, () -> resolver(true).resolve("${connection:drive}", ALLOWED_TARGET, null));

            assertEquals(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL, error.getReason());
            assertFalse(error.getMessage().contains("__service__"), "falling back to the service grant is the failure mode, not the fix");
        }

        @Test
        @DisplayName("a verified conversation owner resolves their own grant")
        void resolvesForVerifiedOwner() {
            register(perUserConnection());
            boundPrincipal("alice", ResolutionPrincipal.Provenance.VERIFIED);
            when(accessTokenSupplier.accessToken(any(), any())).thenReturn("ya29.token");

            var credential = resolver(true).resolve("${connection:drive}", ALLOWED_TARGET, null);

            assertEquals("Bearer ya29.token", credential.headerValue());
            verify(accessTokenSupplier).accessToken(any(), eq("alice"));
        }

        @Test
        @DisplayName("the CONVERSATION's owner is used, not whoever is driving the request")
        void conversationOwnerBeatsBoundCaller() {
            // This is the HITL resume. The thread is bound to the APPROVER - an
            // administrator, by design - while the call being approved belongs to the
            // user who asked for it. Reading the thread ran the approved call against
            // the approver's own SaaS account: the wrong data, and an approval that
            // did not mean what the approver was shown.
            register(perUserConnection());
            boundPrincipal("alice", ResolutionPrincipal.Provenance.VERIFIED);
            when(callerIdentityContext.current()).thenReturn(new CallerIdentity("jwt", "approver-admin", "https://eddi.example"));
            when(accessTokenSupplier.accessToken(any(), any())).thenReturn("ya29.token");

            resolver(true).resolve("${connection:drive}", ALLOWED_TARGET, "alice");

            verify(accessTokenSupplier).accessToken(any(), eq("alice"));
            verify(accessTokenSupplier, never()).accessToken(any(), eq("approver-admin"));
        }

        @Test
        @DisplayName("two disagreeing identities are refused, never reconciled by picking one")
        void refusesWhenTheOverrideDisagrees() {
            // The bound principal says the turn belongs to alice; the call was built
            // for bob. One of the two is wrong and nothing here can tell which, so
            // spending either user's credential would be a guess.
            register(perUserConnection());
            boundPrincipal("alice", ResolutionPrincipal.Provenance.VERIFIED);

            var error = assertThrows(ConnectionException.class, () -> resolver(true).resolve("${connection:drive}", ALLOWED_TARGET, "bob"));

            assertEquals(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL, error.getReason());
            verify(accessTokenSupplier, never()).accessToken(any(), any());
        }

        @Test
        @DisplayName("discovery withholds a PER_USER credential - a cached session would pin one user's token")
        void perUserDiscoveryIsWithheld() {
            register(perUserConnection());

            assertTrue(resolver(true).resolveForDiscovery("${connection:drive}", ALLOWED_TARGET).isEmpty());
        }

        @Test
        @DisplayName("a SERVICE connection resolves under the service principal")
        void serviceBindingUsesServicePrincipal() {
            // Deliberately no bound principal: a SERVICE connection is one credential
            // for everybody, so it must resolve on a scheduled run too.
            var connection = perUserConnection();
            connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.setBinding(Binding.SERVICE);
            register(connection);
            when(accessTokenSupplier.accessToken(any(), any())).thenReturn("service-token");

            assertEquals("Bearer service-token", resolver(false).resolve("${connection:drive}", ALLOWED_TARGET, null).headerValue());
        }
    }

    @Test
    @DisplayName("a resolved credential never prints its value")
    void resolvedCredentialIsNotPrintable() {
        register(staticConnection());

        var credential = resolver(false).resolve("${connection:jira}", ALLOWED_TARGET, null);

        assertFalse(credential.toString().contains("live-token"),
                "this record travels through debug logs and exception messages: " + credential);
    }

    @Test
    @DisplayName("a missing connection is counted, not silently absent from the metrics")
    void countsLookupFailures() {
        var meterRegistry = new SimpleMeterRegistry();
        when(registry.require(any(ConnectionReference.class)))
                .thenThrow(new ConnectionException(ConnectionException.Reason.NOT_FOUND, "No connection named 'gone'"));
        var resolver = new ConnectionResolver(registry, new CredentialReferenceResolver(secretResolver, globalVariableResolver),
                callerIdentityContext, meterRegistry,
                accessTokenSupplier, false);

        assertThrows(ConnectionException.class, () -> resolver.resolve("${connection:gone}", ALLOWED_TARGET, null));

        var counter = meterRegistry.find("connection.resolve.count").tag("outcome", "not_found").counter();
        assertTrue(counter != null && counter.count() == 1,
                "a deleted or misspelled connection fails every turn; a flat dashboard makes that invisible");
    }

    @Test
    @DisplayName("a SERVICE connection DOES supply a credential to discovery")
    void serviceBoundDiscoveryCarriesTheCredential() {
        // The regression: discovery was withheld for EVERY binding, so a
        // connection-bound MCP server had its initialize/tools-list handshake sent
        // unauthenticated, was answered 401, and registered ZERO tools. The agent
        // then simply had no tools, with nothing naming the cause. A SERVICE
        // credential is the same for everybody, so a shared session has nothing to
        // leak.
        register(staticConnection());

        var credential = resolver(false).resolveForDiscovery("${connection:jira}", ALLOWED_TARGET);

        assertTrue(credential.isPresent(), "withholding this is what left the tool list empty");
        assertEquals("Bearer live-token", credential.get().headerValue());
    }

    @Test
    @DisplayName("an unknown connection still throws at discovery rather than quietly returning nothing")
    void unknownConnectionThrowsAtDiscovery() {
        when(registry.find(any(ConnectionReference.class))).thenReturn(Optional.empty());
        when(registry.require(any(ConnectionReference.class)))
                .thenThrow(new ConnectionException(ConnectionException.Reason.NOT_FOUND, "No connection named 'gone'"));

        // Swallowing this would reintroduce the empty-tool-list-with-no-explanation
        // failure by a different route.
        assertThrows(ConnectionException.class, () -> resolver(false).resolveForDiscovery("${connection:gone}", ALLOWED_TARGET));
    }

    @Test
    @DisplayName("a reference is recognised, and an ordinary value is not")
    void recognisesReferences() {
        assertTrue(ConnectionResolver.containsReference("${connection:jira}"));
        assertTrue(ConnectionResolver.containsReference("${connection:tenant-b/jira}"));
        assertFalse(ConnectionResolver.containsReference("${vault:jira}"));
        assertFalse(ConnectionResolver.containsReference("Bearer abc"));
        assertFalse(ConnectionResolver.containsReference(null));
    }
}
