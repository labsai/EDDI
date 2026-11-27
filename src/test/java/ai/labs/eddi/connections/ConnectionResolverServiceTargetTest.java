/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ConnectionResolver#requireServiceTarget}: the save-time check a
 * connection-backed channel integration runs on the connection it names.
 */
class ConnectionResolverServiceTargetTest {

    private static final URI DISCORD = URI.create("https://discord.com");
    private static final String REF = "${connection:discord-bot}";

    private ConnectionRegistry registry;
    private AccessTokenSupplier accessTokenSupplier;
    private ConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectionRegistry.class);
        accessTokenSupplier = mock(AccessTokenSupplier.class);
        resolver = new ConnectionResolver(registry,
                new CredentialReferenceResolver(mock(SecretResolver.class), mock(GlobalVariableResolver.class)),
                mock(CallerIdentityContext.class), new SimpleMeterRegistry(), accessTokenSupplier, true);
        resolver.resolutionPrincipalContext = new ResolutionPrincipalContext();
    }

    private ConnectionConfiguration register(AuthType authType, Binding binding, List<String> allowlist) {
        var connection = new ConnectionConfiguration();
        connection.setName("discord-bot");
        connection.setAuthType(authType);
        connection.setBinding(binding);
        connection.setBaseUrlAllowlist(allowlist);
        when(registry.require(any(ConnectionReference.class))).thenReturn(connection);
        when(registry.find(any(ConnectionReference.class))).thenReturn(Optional.of(connection));
        return connection;
    }

    @Test
    @DisplayName("a SERVICE connection allowed to the origin passes, and nothing is resolved")
    void passes() {
        register(AuthType.STATIC, Binding.SERVICE, List.of("https://discord.com"));
        assertDoesNotThrow(() -> resolver.requireServiceTarget(REF, DISCORD, Set.of(AuthType.STATIC)));
        verifyNoInteractions(accessTokenSupplier);
    }

    @Test
    @DisplayName("a PER_USER connection is refused: a chat event runs as no verified user")
    void perUser() {
        register(AuthType.OAUTH2_AUTHORIZATION_CODE, Binding.PER_USER, List.of("https://discord.com"));
        var ex = assertThrows(ConnectionException.class, () -> resolver.requireServiceTarget(REF, DISCORD, Set.of()));
        assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, ex.getReason());
    }

    @Test
    @DisplayName("a CALLER_SUPPLIED connection is refused too")
    void callerSupplied() {
        register(AuthType.STATIC, Binding.CALLER_SUPPLIED, List.of("https://discord.com"));
        assertThrows(ConnectionException.class, () -> resolver.requireServiceTarget(REF, DISCORD, Set.of()));
    }

    @Test
    @DisplayName("an auth type the channel type cannot use is refused")
    void wrongAuthType() {
        register(AuthType.BASIC, Binding.SERVICE, List.of("https://discord.com"));
        var ex = assertThrows(ConnectionException.class, () -> resolver.requireServiceTarget(REF, DISCORD, Set.of(AuthType.STATIC)));
        assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, ex.getReason());
    }

    @Test
    @DisplayName("an allowlist that does not name the origin is refused")
    void originNotAllowed() {
        register(AuthType.STATIC, Binding.SERVICE, List.of("https://slack.com"));
        var ex = assertThrows(ConnectionException.class, () -> resolver.requireServiceTarget(REF, DISCORD, Set.of()));
        assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, ex.getReason());
    }

    @Test
    @DisplayName("with no fixed origin an https-only allowlist passes, with binding and auth type checked")
    void noOrigin() {
        register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE, List.of("https://smba.trafficmanager.net"));
        assertDoesNotThrow(() -> resolver.requireServiceTarget(REF, null, Set.of(AuthType.OAUTH2_CLIENT_CREDENTIALS)));
    }

    @Test
    @DisplayName("without a fixed origin an empty allowlist is still refused at save")
    void noOriginEmptyAllowlist() {
        register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE, List.of());
        var ex = assertThrows(ConnectionException.class, () -> resolver.requireServiceTarget(REF, null, Set.of()));
        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, ex.getReason());
    }

    @Test
    @DisplayName("without a fixed origin a non-https allowlist entry is refused at save")
    void noOriginHttpAllowlist() {
        register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE, List.of("https://ok.example", "http://plain.example"));
        assertThrows(ConnectionException.class, () -> resolver.requireServiceTarget(REF, null, Set.of()));
    }

    @Test
    @DisplayName("without a fixed origin, an allowlist entry with no host is refused as malformed")
    void noOriginHostlessAllowlist() {
        register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE, List.of("https://"));
        var ex = assertThrows(ConnectionException.class, () -> resolver.requireServiceTarget(REF, null, Set.of()));
        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, ex.getReason());
        register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE, List.of("https://smba.trafficmanager.net"));
        assertDoesNotThrow(() -> resolver.requireServiceTarget(REF, null, Set.of()));
    }

    @Test
    @DisplayName("a connection without an auth type is refused, not a NullPointerException")
    void nullAuthType() {
        register(null, Binding.SERVICE, List.of("https://discord.com"));
        var ex = assertThrows(ConnectionException.class, () -> resolver.requireServiceTarget(REF, DISCORD, Set.of(AuthType.STATIC)));
        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, ex.getReason());
    }

    @Test
    @DisplayName("a send re-checks the binding: a connection switched to PER_USER after the save resolves nothing")
    void resolveServiceRefusesPerUser() {
        register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.PER_USER, List.of("https://discord.com"));
        var ex = assertThrows(ConnectionException.class, () -> resolver.resolveService(REF, DISCORD, Set.of()));
        assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, ex.getReason());
        verifyNoInteractions(accessTokenSupplier);
    }

    @Test
    @DisplayName("a send re-checks the auth type")
    void resolveServiceRefusesWrongAuthType() {
        register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE, List.of("https://discord.com"));
        var ex = assertThrows(ConnectionException.class, () -> resolver.resolveService(REF, DISCORD, Set.of(AuthType.STATIC)));
        assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, ex.getReason());
        verifyNoInteractions(accessTokenSupplier);
    }

    @Test
    @DisplayName("a SERVICE connection resolves for the target with the service principal")
    void resolveServiceResolves() {
        var connection = register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE, List.of("https://discord.com"));
        when(accessTokenSupplier.accessToken(eq(connection), any())).thenReturn("tok");
        assertEquals("Bearer tok", resolver.resolveService(REF, DISCORD, Set.of(AuthType.OAUTH2_CLIENT_CREDENTIALS)).headerValue());
        verify(accessTokenSupplier).accessToken(connection, ConnectionResolver.SERVICE_PRINCIPAL);
        verify(registry, times(1)).require(any(ConnectionReference.class));
    }

    @Test
    @DisplayName("a send reads the connection once, so an edit cannot land between the checks and the resolution")
    void resolveServiceReadsOnce() {
        var service = register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE, List.of("https://discord.com"));
        var perUser = new ConnectionConfiguration();
        perUser.setName("discord-bot");
        perUser.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        perUser.setBinding(Binding.PER_USER);
        perUser.setBaseUrlAllowlist(List.of("https://discord.com"));
        when(registry.require(any(ConnectionReference.class))).thenReturn(service, perUser);
        when(accessTokenSupplier.accessToken(eq(service), any())).thenReturn("tok");
        assertEquals("Bearer tok", resolver.resolveService(REF, DISCORD, Set.of()).headerValue());
        verify(accessTokenSupplier, never()).accessToken(eq(perUser), any());
    }

    @Test
    @DisplayName("a send to a non-https target is refused, whatever the allowlist admits")
    void resolveServiceRefusesPlaintext() {
        register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE, List.of("http://localhost:8080"));
        var ex = assertThrows(ConnectionException.class,
                () -> resolver.resolveService(REF, URI.create("http://localhost:8080/api"), Set.of()));
        assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, ex.getReason());
        assertThrows(ConnectionException.class, () -> resolver.resolveService(REF, null, Set.of()));
        verifyNoInteractions(accessTokenSupplier);
    }

    @Test
    @DisplayName("the OAuth client id is read without resolving anything")
    void clientId() {
        var connection = register(AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE, List.of("https://discord.com"));
        var oauth = new OAuthConfig();
        oauth.setClientId("app-1");
        connection.setOauth(oauth);
        assertEquals(Optional.of("app-1"), resolver.oauthClientIdOf(REF));
        connection.setOauth(null);
        assertEquals(Optional.empty(), resolver.oauthClientIdOf(REF));
        verifyNoInteractions(accessTokenSupplier);
    }
}
