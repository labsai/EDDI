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
import java.util.LinkedHashMap;
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

        private static final URI ACME_TARGET = URI.create("https://api.example.com/api/v2/items");

        private ConnectionConfiguration acmeConnection() {
            var connection = new ConnectionConfiguration();
            connection.setName("acme");
            connection.setAuthType(AuthType.STATIC);
            connection.setBinding(Binding.CALLER_SUPPLIED);
            connection.setBaseUrlAllowlist(List.of("https://api.example.com"));
            var auth = new StaticAuth();
            auth.setHeaderName("x-api-key");
            connection.setStaticAuth(auth);
            return connection;
        }

        private void callerSupplies(Map<String, String> credentials) {
            when(callerIdentityContext.current()).thenReturn(new CallerIdentity(null, "acme-backend", "https://eddi.example.com", credentials));
        }

        @Test
        @DisplayName("the caller's value is sent under the connection's header name")
        void sendsTheCallersCredential() {
            register(acmeConnection());
            callerSupplies(Map.of("acme", "key-id:secret"));

            var credential = resolver(true).resolve("${connection:acme}", ACME_TARGET, null);

            assertEquals("x-api-key", credential.headerName());
            assertEquals("key-id:secret", credential.headerValue());
        }

        @Test
        @DisplayName("no credential on the request fails closed rather than calling out unauthenticated")
        void failsClosedWithoutCredential() {
            register(acmeConnection());
            callerSupplies(Map.of());

            var error = assertThrows(ConnectionException.class, () -> resolver(true).resolve("${connection:acme}", ACME_TARGET, null));

            assertEquals(ConnectionException.Reason.NO_CALLER_CREDENTIAL, error.getReason());
            assertTrue(error.getMessage().contains("acme"), error.getMessage());
            assertTrue(error.getMessage().contains("resume"),
                    "the message must name the resume case, which is the non-obvious half: " + error.getMessage());
        }

        @Test
        @DisplayName("with authorization disabled the refusal names the deployment, not a header the caller did send")
        void namesTheDisabledAuthorizationWhenNoCallerCanBeAuthenticated() {
            // CallerIdentityContext.capture() drops every connection credential on an
            // anonymous request, and with authorization.enabled=false every request is
            // anonymous. The operator can see the header going out; a message saying
            // the request carried none sends them to debug the wrong system.
            register(acmeConnection());
            when(callerIdentityContext.current()).thenReturn(null);

            var error = assertThrows(ConnectionException.class, () -> resolver(false).resolve("${connection:acme}", ACME_TARGET, null));

            assertEquals(ConnectionException.Reason.NO_CALLER_CREDENTIAL, error.getReason());
            assertTrue(error.getMessage().contains("authorization.enabled=false"),
                    "the message must name the deployment setting that drops the credential: " + error.getMessage());
            assertTrue(error.getMessage().contains("authorization.enabled=true"), "and the fix: " + error.getMessage());
            assertFalse(error.getMessage().contains("carried no credential"),
                    "it must not claim the request carried nothing; it may well have: " + error.getMessage());
        }

        @Test
        @DisplayName("a credential for a different connection is not borrowed")
        void doesNotBorrowAnotherConnectionsCredential() {
            register(acmeConnection());
            callerSupplies(Map.of("some-other-system", "not-for-acme"));

            var error = assertThrows(ConnectionException.class, () -> resolver(true).resolve("${connection:acme}", ACME_TARGET, null));

            assertEquals(ConnectionException.Reason.NO_CALLER_CREDENTIAL, error.getReason());
        }

        @Test
        @DisplayName("no caller identity at all — a scheduled turn — fails closed too")
        void failsClosedWithoutCallerIdentity() {
            register(acmeConnection());
            when(callerIdentityContext.current()).thenReturn(null);

            var error = assertThrows(ConnectionException.class, () -> resolver(true).resolve("${connection:acme}", ACME_TARGET, null));

            assertEquals(ConnectionException.Reason.NO_CALLER_CREDENTIAL, error.getReason());
        }

        @Test
        @DisplayName("a stored document with no headerName is a configuration error, not an NPE")
        void refusesConnectionWithoutHeaderName() {
            // ConnectionRegistry serves cached documents and does not re-run validate(),
            // so the save-time rules are not a guarantee here: a document written before
            // they existed, or straight into the store, still reaches the resolver.
            // Not `new StaticAuth()`: headerName defaults to "Authorization", so an
            // author who never set one has a valid document that sends the credential
            // there. Only an absent block or an explicitly blanked name reach the guard.
            // Labelled rather than looping over the objects themselves: StaticAuth
            // inherits Object.toString(), so a failure message built from one names the
            // identity hash and not which of the three cases broke.
            Map<String, StaticAuth> cases = new LinkedHashMap<>();
            cases.put("no staticAuth block", null);
            cases.put("blank headerName", headerNamed("  "));
            cases.put("null headerName", headerNamed(null));

            for (var entry : cases.entrySet()) {
                var connection = acmeConnection();
                connection.setStaticAuth(entry.getValue());
                register(connection);
                callerSupplies(Map.of("acme", "key-id:secret"));

                var error = assertThrows(ConnectionException.class,
                        () -> resolver(true).resolve("${connection:acme}", ACME_TARGET, null), entry.getKey());

                assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason(), entry.getKey());
                assertTrue(error.getMessage().contains("headerName"), entry.getKey() + ": " + error.getMessage());
            }
        }

        private StaticAuth headerNamed(String headerName) {
            var auth = new StaticAuth();
            auth.setHeaderName(headerName);
            return auth;
        }

        @Test
        @DisplayName("the allowlist still bounds where the user's own credential may go")
        void refusesTargetOffTheAllowlist() {
            register(acmeConnection());
            callerSupplies(Map.of("acme", "key-id:secret"));

            var error = assertThrows(ConnectionException.class,
                    () -> resolver(true).resolve("${connection:acme}", URI.create("https://evil.example.com/collect"), null));

            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
        }

        @Test
        @DisplayName("withheld from discovery — a cached handshake would pin one caller's credential onto everybody")
        void withheldFromDiscovery() {
            register(acmeConnection());
            callerSupplies(Map.of("acme", "key-id:secret"));

            assertTrue(resolver(true).resolveForDiscovery("${connection:acme}", ACME_TARGET).isEmpty());
        }

        @Test
        @DisplayName("resolves without a verified principal — the credential is the authority, not the id")
        void doesNotRequireVerifiedPrincipal() {
            // The driving deployment calls EDDI as one service principal with the end
            // user's key attached, so the bound principal is SELF_ASSERTED. Requiring a
            // verified one here would make the binding unusable in exactly the topology
            // it was built for — and it is not needed, because nothing is looked up by
            // principal: the caller hands over the credential itself.
            register(acmeConnection());
            boundPrincipal("end-user-42", ResolutionPrincipal.Provenance.SELF_ASSERTED);
            callerSupplies(Map.of("acme", "key-id:secret"));

            var credential = resolver(true).resolve("${connection:acme}", ACME_TARGET, null);

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
        @DisplayName("a STATIC document with no staticAuth is a configuration error, not an NPE")
        void staticWithoutStaticAuthIsAConfigurationError() {
            // A cached document written before the validation rules, or straight into
            // the store. An NPE here is not a ConnectionException, so the MCP failure
            // classifier could not tell it from an outage and tripped the breaker.
            var cases = new LinkedHashMap<String, StaticAuth>();
            cases.put("no staticAuth block", null);
            var blankHeader = new StaticAuth();
            blankHeader.setHeaderName(" ");
            blankHeader.setValueTemplate("Bearer ${vault:jira-token}");
            cases.put("blank headerName", blankHeader);
            var noTemplate = new StaticAuth();
            noTemplate.setHeaderName("Authorization");
            cases.put("no valueTemplate", noTemplate);

            for (var entry : cases.entrySet()) {
                var connection = staticConnection();
                connection.setStaticAuth(entry.getValue());
                register(connection);

                var error = assertThrows(ConnectionException.class,
                        () -> resolver(false).resolve("${connection:jira}", ALLOWED_TARGET, null), entry.getKey());

                assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason(), entry.getKey());
                assertTrue(error.getMessage().contains("staticAuth"), entry.getKey() + ": " + error.getMessage());
            }
        }

        @Test
        @DisplayName("BASIC with no username refuses rather than sending 'null:password'")
        void basicWithoutUsernameIsAConfigurationError() {
            var cases = new LinkedHashMap<String, StaticAuth>();
            cases.put("no staticAuth block", null);
            var noUser = new StaticAuth();
            noUser.setHeaderName("Authorization");
            noUser.setPasswordRef("${vault:jira-password}");
            cases.put("null username", noUser);
            var noPassword = new StaticAuth();
            noPassword.setHeaderName("Authorization");
            noPassword.setUsername("svc-eddi");
            cases.put("no passwordRef", noPassword);

            for (var entry : cases.entrySet()) {
                var connection = staticConnection();
                connection.setAuthType(AuthType.BASIC);
                connection.setStaticAuth(entry.getValue());
                register(connection);

                var error = assertThrows(ConnectionException.class,
                        () -> resolver(false).resolve("${connection:jira}", ALLOWED_TARGET, null), entry.getKey());

                assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason(), entry.getKey());
                assertTrue(error.getMessage().contains("staticAuth"), entry.getKey() + ": " + error.getMessage());
            }
            verify(secretResolver, never()).resolveValue(anyString());
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

        @Test
        @DisplayName("an allowlisted plaintext http target on a remote host is refused by default, naming the property, before any secret resolves")
        void refusesPlaintextRemoteTargetByDefault() {
            var connection = staticConnection();
            connection.setBaseUrlAllowlist(List.of("http://api.internal.example"));
            register(connection);

            var error = assertThrows(ConnectionException.class,
                    () -> resolver(false).resolve("${connection:jira}", URI.create("http://api.internal.example/issue/1"), null));

            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
            assertTrue(error.getMessage().contains("eddi.connections.allow-plaintext-remote-origins"), error.getMessage());
            verify(secretResolver, never()).resolveValue(anyString());
        }

        @Test
        @DisplayName("the same target resolves once the deployment allows plaintext remote origins")
        void resolvesPlaintextRemoteTargetWhenAllowed() {
            var connection = staticConnection();
            connection.setBaseUrlAllowlist(List.of("http://api.internal.example"));
            register(connection);
            var resolver = resolver(false);
            resolver.connectionsConfig = new ConnectionsConfig(true, "https://eddi.example.com", true);

            assertEquals("Bearer live-token",
                    resolver.resolve("${connection:jira}", URI.create("http://api.internal.example/issue/1"), null).headerValue());
        }

        @Test
        @DisplayName("a plaintext http target on loopback resolves with the property at its default")
        void resolvesLoopbackHttpTargetByDefault() {
            var connection = staticConnection();
            connection.setBaseUrlAllowlist(List.of("http://localhost:8080"));
            register(connection);
            var resolver = resolver(false);
            resolver.connectionsConfig = new ConnectionsConfig(true, "https://eddi.example.com", false);

            assertEquals("Bearer live-token",
                    resolver.resolve("${connection:jira}", URI.create("http://localhost:8080/issue/1"), null).headerValue());
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

        var counter = meterRegistry.find("eddi.connection.resolve.count").tag("outcome", "not_found").counter();
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
    @DisplayName("a store failure on the discovery path is counted as a lookup failure, not silently NOT_FOUND")
    void discoveryCountsAStoreFailure() {
        // resolveForDiscovery reads the binding through registry.find, which turns a
        // store outage into a NOT_FOUND ConnectionException. resolve() counts that;
        // the discovery path did not, so an MCP server failing every handshake over a
        // database blip showed a flat dashboard.
        var meterRegistry = new SimpleMeterRegistry();
        when(registry.find(any(ConnectionReference.class)))
                .thenThrow(new ConnectionException(ConnectionException.Reason.NOT_FOUND, "Could not read connection 'jira': store down"));
        var resolver = new ConnectionResolver(registry, new CredentialReferenceResolver(secretResolver, globalVariableResolver),
                callerIdentityContext, meterRegistry, accessTokenSupplier, false);

        assertThrows(ConnectionException.class, () -> resolver.resolveForDiscovery("${connection:jira}", ALLOWED_TARGET));

        var counter = meterRegistry.find("eddi.connection.resolve.count").tag("outcome", "not_found").counter();
        assertTrue(counter != null && counter.count() == 1, "a discovery lookup failure must reach the same counter a tool-call lookup failure does");
    }

    @Test
    @DisplayName("bindingOf names the binding a caller was withheld a credential for")
    void bindingOfNamesTheActualBinding() {
        var connection = staticConnection();
        connection.setBinding(Binding.CALLER_SUPPLIED);
        connection.getStaticAuth().setValueTemplate(null);
        register(connection);

        assertEquals(Optional.of(Binding.CALLER_SUPPLIED), resolver(true).bindingOf("${connection:jira}"),
                "a warning that assumes PER_USER for a CALLER_SUPPLIED connection sends the operator looking for an OAuth grant");
        assertTrue(resolver(true).resolveForDiscovery("${connection:jira}", ALLOWED_TARGET).isEmpty());
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
