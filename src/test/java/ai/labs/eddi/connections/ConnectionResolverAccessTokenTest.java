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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ConnectionResolver#resolveAccessToken}: the bare token XOAUTH2 needs,
 * for OAuth connections only, with every rule of {@code resolve} still applied.
 */
class ConnectionResolverAccessTokenTest {

    private static final URI GRAPH = URI.create("https://graph.microsoft.com/v1.0/me");

    private ConnectionRegistry registry;
    private SecretResolver secretResolver;
    private GlobalVariableResolver globalVariableResolver;
    private AccessTokenSupplier accessTokenSupplier;
    private ResolutionPrincipalContext resolutionPrincipalContext;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectionRegistry.class);
        secretResolver = mock(SecretResolver.class);
        globalVariableResolver = mock(GlobalVariableResolver.class);
        accessTokenSupplier = mock(AccessTokenSupplier.class);
        resolutionPrincipalContext = new ResolutionPrincipalContext();
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void unbind() {
        resolutionPrincipalContext.clear();
    }

    private ConnectionResolver resolver() {
        var resolver = new ConnectionResolver(registry, new CredentialReferenceResolver(secretResolver, globalVariableResolver),
                mock(CallerIdentityContext.class), meterRegistry, accessTokenSupplier, true);
        resolver.resolutionPrincipalContext = resolutionPrincipalContext;
        return resolver;
    }

    private void register(ConnectionConfiguration connection) {
        when(registry.require(any(ConnectionReference.class))).thenReturn(connection);
        when(registry.find(any(ConnectionReference.class))).thenReturn(Optional.of(connection));
    }

    private static ConnectionConfiguration oauth(AuthType authType, Binding binding) {
        var connection = new ConnectionConfiguration();
        connection.setName("exo");
        connection.setAuthType(authType);
        connection.setBinding(binding);
        connection.setBaseUrlAllowlist(List.of("https://graph.microsoft.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://login.microsoftonline.com/tenant/oauth2/v2.0/token");
        oauth.setAuthorizationUrl("https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize");
        oauth.setClientId("client");
        oauth.setClientSecret("${vault:client-secret}");
        connection.setOauth(oauth);
        return connection;
    }

    private static ConnectionConfiguration headerConnection(AuthType authType) {
        var connection = new ConnectionConfiguration();
        connection.setName("exo");
        connection.setAuthType(authType);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://graph.microsoft.com"));
        var auth = new StaticAuth();
        auth.setHeaderName("Authorization");
        auth.setValueTemplate("Bearer ${vault:token}");
        auth.setUsername("mailbox@example.com");
        auth.setPasswordRef("${vault:app-password}");
        connection.setStaticAuth(auth);
        return connection;
    }

    @Nested
    @DisplayName("OAuth connections yield the bare token")
    class OAuth {

        @Test
        @DisplayName("client credentials resolve under the service principal, with no Bearer prefix")
        void clientCredentials() {
            register(oauth(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE));
            when(accessTokenSupplier.accessToken(any(), anyString())).thenReturn("eyJ.service");

            assertEquals("eyJ.service", resolver().resolveAccessToken("${connection:exo}", GRAPH, null));
            verify(accessTokenSupplier).accessToken(any(), eq(ConnectionResolver.SERVICE_PRINCIPAL));
        }

        @Test
        @DisplayName("an authorization-code PER_USER grant resolves for the verified owner")
        void perUserVerified() {
            register(oauth(AuthType.OAUTH2_AUTHORIZATION_CODE, Binding.PER_USER));
            resolutionPrincipalContext.bind(new ResolutionPrincipal("alice", ResolutionPrincipal.Provenance.VERIFIED));
            when(accessTokenSupplier.accessToken(any(), anyString())).thenReturn("ya29.alice");

            assertEquals("ya29.alice", resolver().resolveAccessToken("${connection:exo}", GRAPH, "alice"));
            verify(accessTokenSupplier).accessToken(any(), eq("alice"));
        }

        @Test
        @DisplayName("a self-asserted owner is refused, as on the header path — an email turn is SELF_ASSERTED")
        void perUserSelfAssertedRefused() {
            register(oauth(AuthType.OAUTH2_AUTHORIZATION_CODE, Binding.PER_USER));
            resolutionPrincipalContext.bind(new ResolutionPrincipal("email:int:bob@example.org", ResolutionPrincipal.Provenance.SELF_ASSERTED));

            var error = assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken("${connection:exo}", GRAPH, null));

            assertEquals(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL, error.getReason());
            verify(accessTokenSupplier, never()).accessToken(any(), any());
        }

        @Test
        @DisplayName("PER_USER with no bound principal (a polling job) is refused, never the service grant")
        void perUserWithoutPrincipalRefused() {
            register(oauth(AuthType.OAUTH2_AUTHORIZATION_CODE, Binding.PER_USER));

            var error = assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken("${connection:exo}", GRAPH, null));

            assertEquals(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL, error.getReason());
            verify(accessTokenSupplier, never()).accessToken(any(), any());
        }

        @Test
        @DisplayName("a disagreeing override is refused")
        void disagreeingOverrideRefused() {
            register(oauth(AuthType.OAUTH2_AUTHORIZATION_CODE, Binding.PER_USER));
            resolutionPrincipalContext.bind(new ResolutionPrincipal("alice", ResolutionPrincipal.Provenance.VERIFIED));

            var error = assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken("${connection:exo}", GRAPH, "bob"));

            assertEquals(ConnectionException.Reason.NO_VERIFIED_PRINCIPAL, error.getReason());
        }

        @Test
        @DisplayName("a target off the allowlist is refused before any token is fetched")
        void targetNotAllowed() {
            register(oauth(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE));

            var error = assertThrows(ConnectionException.class,
                    () -> resolver().resolveAccessToken("${connection:exo}", URI.create("https://attacker.example.net/"), null));

            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
            verify(accessTokenSupplier, never()).accessToken(any(), any());
        }

        @Test
        @DisplayName("a null target is refused — there is nothing to check the allowlist against")
        void nullTargetRefused() {
            register(oauth(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE));

            var error = assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken("${connection:exo}", null, null));

            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
        }

        @Test
        @DisplayName("an https origin of the API does not cover a mail origin: the allowlist must name the mail server itself")
        void mailOriginNotCoveredByHttpsEntry() {
            // The same host as the target, so only the scheme and port can be what refuses
            // it.
            var connection = oauth(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE);
            connection.setBaseUrlAllowlist(List.of("https://outlook.office365.com"));
            register(connection);

            var error = assertThrows(ConnectionException.class,
                    () -> resolver().resolveAccessToken("${connection:exo}", URI.create("imaps://outlook.office365.com:993"), null));

            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
            verify(accessTokenSupplier, never()).accessToken(any(), any());
        }

        @Test
        @DisplayName("a mail origin on the allowlist releases the token for that server; the default port folds")
        void mailOriginAllowed() {
            var connection = oauth(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE);
            connection.setBaseUrlAllowlist(List.of("smtps://smtp.office365.com"));
            register(connection);
            when(accessTokenSupplier.accessToken(any(), anyString())).thenReturn("eyJ.mail");

            assertEquals("eyJ.mail",
                    resolver().resolveAccessToken("${connection:exo}", URI.create("smtps://smtp.office365.com:465"), null));
            var other = assertThrows(ConnectionException.class,
                    () -> resolver().resolveAccessToken("${connection:exo}", URI.create("smtp://smtp.office365.com:587"), null));
            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, other.getReason());
        }

        @Test
        @DisplayName("a blank token from the supplier is refused, not handed to XOAUTH2")
        void blankTokenRefused() {
            register(oauth(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE));
            when(accessTokenSupplier.accessToken(any(), anyString())).thenReturn("  ");

            var error = assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken("${connection:exo}", GRAPH, null));

            assertEquals(ConnectionException.Reason.GRANT_UNUSABLE, error.getReason());
        }
    }

    @Nested
    @DisplayName("resolveBasicLogin: username and password for a mail login")
    class BasicLogins {

        private static final URI RELAY = URI.create("smtp://relay.example.com:587");

        private ConnectionConfiguration basic() {
            var connection = headerConnection(AuthType.BASIC);
            connection.setBaseUrlAllowlist(List.of("smtp://relay.example.com"));
            return connection;
        }

        @Test
        @DisplayName("a BASIC connection yields its username and the vault-resolved password")
        void basicResolves() {
            register(basic());
            when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
            when(secretResolver.resolveValue("${vault:app-password}")).thenReturn("app-pw");

            var login = resolver().resolveBasicLogin("${connection:exo}", RELAY);

            assertEquals("mailbox@example.com", login.username());
            assertEquals("app-pw", login.password());
            assertTrue(!login.toString().contains("app-pw"), "toString must not print the password");
        }

        @Test
        @DisplayName("the allowlist is checked before the password is resolved")
        void targetChecked() {
            register(basic());

            var error = assertThrows(ConnectionException.class,
                    () -> resolver().resolveBasicLogin("${connection:exo}", URI.create("smtp://elsewhere.example.com:587")));

            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
            verifyNoInteractions(secretResolver);
        }

        @Test
        @DisplayName("OAuth and STATIC connections are refused: there is no username/password to give")
        void otherTypesRefused() {
            register(oauth(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE));
            assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT,
                    assertThrows(ConnectionException.class, () -> resolver().resolveBasicLogin("${connection:exo}", RELAY)).getReason());
            register(headerConnection(AuthType.STATIC));
            assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT,
                    assertThrows(ConnectionException.class, () -> resolver().resolveBasicLogin("${connection:exo}", RELAY)).getReason());
            verifyNoInteractions(secretResolver);
        }
    }

    @Nested
    @DisplayName("header-shaped connections are refused")
    class Refusals {

        @Test
        @DisplayName("STATIC is refused and its secret is never resolved")
        void staticRefused() {
            register(headerConnection(AuthType.STATIC));

            var error = assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken("${connection:exo}", GRAPH, null));

            assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, error.getReason());
            verifyNoInteractions(secretResolver, accessTokenSupplier);
        }

        @Test
        @DisplayName("BASIC is refused and its password is never resolved")
        void basicRefused() {
            register(headerConnection(AuthType.BASIC));

            var error = assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken("${connection:exo}", GRAPH, null));

            assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, error.getReason());
            verifyNoInteractions(secretResolver, accessTokenSupplier);
        }

        @Test
        @DisplayName("CALLER_SUPPLIED is refused even on a document that claims an OAuth type")
        void callerSuppliedRefused() {
            var connection = oauth(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.CALLER_SUPPLIED);
            register(connection);

            var error = assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken("${connection:exo}", GRAPH, null));

            assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, error.getReason());
            verifyNoInteractions(accessTokenSupplier);
        }

        @Test
        @DisplayName("a refusal is counted with its reason")
        void refusalCounted() {
            register(headerConnection(AuthType.BASIC));

            assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken("${connection:exo}", GRAPH, null));

            var counter = meterRegistry.find("eddi.connection.resolve.count").tag("outcome", "unsupported_placement").counter();
            assertNotNull(counter);
            assertTrue(counter.count() == 1);
        }

        @Test
        @DisplayName("an unknown connection is counted as a lookup failure and rethrown")
        void unknownConnection() {
            when(registry.require(any(ConnectionReference.class)))
                    .thenThrow(new ConnectionException(ConnectionException.Reason.NOT_FOUND, "No connection named 'gone'"));

            var error = assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken("${connection:gone}", GRAPH, null));

            assertEquals(ConnectionException.Reason.NOT_FOUND, error.getReason());
            assertNotNull(meterRegistry.find("eddi.connection.resolve.count").tag("outcome", "not_found").counter());
        }
    }
}
