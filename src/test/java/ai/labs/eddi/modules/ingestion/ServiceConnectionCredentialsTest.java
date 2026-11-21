/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.AccessTokenSupplier;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.CredentialReferenceResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The credential an ingestion run may spend, through the real
 * {@link ConnectionResolver}: only the token supplier and the connection store
 * are stand-ins, so the allowlist and binding rules are the shipped ones.
 */
class ServiceConnectionCredentialsTest {

    private static final URI DRIVE_URL = URI.create("https://www.googleapis.com/drive/v3/files?corpora=drive");

    private ConnectionRegistry registry;
    private AccessTokenSupplier tokens;
    private ServiceConnectionCredentials credentials;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectionRegistry.class);
        tokens = mock(AccessTokenSupplier.class);
        var resolver = new ConnectionResolver(registry,
                new CredentialReferenceResolver(mock(SecretResolver.class), mock(GlobalVariableResolver.class)),
                mock(CallerIdentityContext.class), new SimpleMeterRegistry(), tokens, true);
        credentials = new ServiceConnectionCredentials(resolver);
        when(tokens.accessToken(any(), anyString())).thenReturn("ya29.test-token");
    }

    private void register(ConnectionConfiguration connection) {
        when(registry.require(any(ConnectionReference.class))).thenReturn(connection);
        when(registry.find(any(ConnectionReference.class))).thenReturn(Optional.of(connection));
    }

    private static ConnectionConfiguration serviceAccount(List<String> allowlist) {
        var connection = new ConnectionConfiguration();
        connection.setName("google-drive-sa");
        connection.setAuthType(AuthType.OAUTH2_JWT_BEARER);
        connection.setBinding(Binding.SERVICE);
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://oauth2.googleapis.com/token");
        oauth.setPrivateKey("${vault:google-drive-sa-key}");
        oauth.setScopes(List.of("https://www.googleapis.com/auth/drive.readonly"));
        connection.setOauth(oauth);
        connection.setBaseUrlAllowlist(allowlist);
        return connection;
    }

    @Test
    @DisplayName("a SERVICE connection resolves with the service grant, for the exact request URL")
    void serviceConnectionResolves() throws SourceException {
        register(serviceAccount(List.of("https://www.googleapis.com")));

        ResolvedCredential credential = credentials.resolve("google-drive-sa", DRIVE_URL);

        assertEquals("Authorization", credential.headerName());
        assertEquals("Bearer ya29.test-token", credential.headerValue());
        verify(tokens).accessToken(any(), eq(ConnectionResolver.SERVICE_PRINCIPAL));
    }

    @Test
    @DisplayName("a target off the connection's allowlist is refused and no token is minted")
    void allowlistIsEnforced() {
        register(serviceAccount(List.of("https://api.example.com")));

        var failure = assertThrows(SourceException.class, () -> credentials.resolve("google-drive-sa", DRIVE_URL));

        assertEquals(SourceError.Kind.AUTH, failure.error().kind());
        assertTrue(failure.getMessage().contains("TARGET_NOT_ALLOWED"), failure.getMessage());
        verify(tokens, never()).accessToken(any(), anyString());
    }

    @Test
    @DisplayName("a PER_USER connection is refused by name, with the fix")
    void perUserIsRefused() {
        var connection = serviceAccount(List.of("https://www.googleapis.com"));
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.PER_USER);
        register(connection);

        var failure = assertThrows(SourceException.class, () -> credentials.resolve("google-drive-sa", DRIVE_URL));

        assertEquals(SourceError.Kind.AUTH, failure.error().kind());
        assertTrue(failure.getMessage().contains("bound PER_USER"), failure.getMessage());
        assertTrue(failure.getMessage().contains("SERVICE-bound"), failure.getMessage());
        verify(tokens, never()).accessToken(any(), anyString());
    }

    @Test
    @DisplayName("a CALLER_SUPPLIED connection is refused: an ingestion run has no caller")
    void callerSuppliedIsRefused() {
        var connection = serviceAccount(List.of("https://www.googleapis.com"));
        connection.setBinding(Binding.CALLER_SUPPLIED);
        register(connection);

        var failure = assertThrows(SourceException.class, () -> credentials.resolve("google-drive-sa", DRIVE_URL));

        assertTrue(failure.getMessage().contains("bound CALLER_SUPPLIED"), failure.getMessage());
    }

    @Test
    @DisplayName("a missing connection, or a name that is no connection name, is an AUTH failure with the fix")
    void missingConnection() {
        when(registry.find(any(ConnectionReference.class))).thenReturn(Optional.empty());
        when(registry.require(any(ConnectionReference.class))).thenThrow(new ConnectionException(
                ConnectionException.Reason.NOT_FOUND, "No connection named 'google-drive-sa' exists in tenant 'default'"));

        var missing = assertThrows(SourceException.class, () -> credentials.resolve("google-drive-sa", DRIVE_URL));
        assertTrue(missing.getMessage().contains("No connection named 'google-drive-sa'"), missing.getMessage());

        var crossTenant = assertThrows(SourceException.class, () -> credentials.resolve("other/google", DRIVE_URL));
        assertEquals(SourceError.Kind.AUTH, crossTenant.error().kind());
    }

    @Test
    @DisplayName("a token endpoint that is down is transient; a revoked grant is not")
    void tokenEndpointFailures() {
        register(serviceAccount(List.of("https://www.googleapis.com")));
        when(tokens.accessToken(any(), anyString()))
                .thenThrow(new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, "timeout"))
                .thenThrow(new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "invalid_grant"));

        var down = assertThrows(SourceException.class, () -> credentials.resolve("google-drive-sa", DRIVE_URL));
        assertEquals(SourceError.Kind.TRANSIENT, down.error().kind());

        var revoked = assertThrows(SourceException.class, () -> credentials.resolve("google-drive-sa", DRIVE_URL));
        assertEquals(SourceError.Kind.AUTH, revoked.error().kind());
    }

    @Test
    @DisplayName("a PER_USER connection is refused before anything is resolved, whatever the resolver would answer")
    void perUserNeverResolved() {
        // A resolver that WOULD hand out a grant — as it does on a thread that happens
        // to carry somebody's verified principal. The refusal must not depend on the
        // resolver also refusing.
        ConnectionResolver resolver = mock(ConnectionResolver.class);
        when(resolver.bindingOf(anyString())).thenReturn(Optional.of(Binding.PER_USER));
        when(resolver.resolve(anyString(), any(), any())).thenReturn(new ResolvedCredential("Authorization", "Bearer alice"));

        var failure = assertThrows(SourceException.class,
                () -> new ServiceConnectionCredentials(resolver).resolve("google-drive-sa", DRIVE_URL));

        assertEquals(SourceError.Kind.AUTH, failure.error().kind());
        verify(resolver, never()).resolve(anyString(), any(), any());
    }

    @Test
    @DisplayName("a connection stored without a binding is SERVICE, as the resolver reads it")
    void absentBindingIsService() throws SourceException {
        var connection = serviceAccount(List.of("https://www.googleapis.com"));
        connection.setBinding(null);
        register(connection);

        assertEquals("Bearer ya29.test-token", credentials.resolve("google-drive-sa", DRIVE_URL).headerValue());
    }
}
