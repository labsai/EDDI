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
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * {@link ConnectionResolver#requireServiceIdentity} and
 * {@link ConnectionResolver#proveServiceIdentity}: who may declare a Teams app
 * id.
 */
class ConnectionResolverServiceIdentityTest {

    private static final String REF = "${connection:teams-bot}";
    private static final String APP_ID = "11111111-2222-3333-4444-555555555555";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/oauth2/v2.0/token";
    private static final String SCOPE = "https://api.botframework.com/.default";

    private ConnectionRegistry registry;
    private AccessTokenSupplier accessTokenSupplier;
    private ConnectionResolver resolver;
    private ConnectionConfiguration connection;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectionRegistry.class);
        accessTokenSupplier = mock(AccessTokenSupplier.class);
        resolver = new ConnectionResolver(registry,
                new CredentialReferenceResolver(mock(SecretResolver.class), mock(GlobalVariableResolver.class)),
                mock(CallerIdentityContext.class), new SimpleMeterRegistry(), accessTokenSupplier, true);
        connection = new ConnectionConfiguration();
        connection.setName("teams-bot");
        connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        connection.setBinding(Binding.SERVICE);
        var oauth = new OAuthConfig();
        oauth.setClientId(APP_ID.toUpperCase());
        oauth.setTokenUrl(TOKEN_URL + "/");
        oauth.setScopes(List.of(SCOPE));
        connection.setOauth(oauth);
        when(registry.require(any(ConnectionReference.class))).thenReturn(connection);
    }

    @Test
    @DisplayName("the matching identity passes without any token request")
    void matchingIdentity() {
        assertDoesNotThrow(() -> resolver.requireServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
        verifyNoInteractions(accessTokenSupplier);
    }

    @Test
    @DisplayName("another app's client id is refused")
    void otherClientId() {
        connection.getOauth().setClientId("99999999-2222-3333-4444-555555555555");
        assertThrows(ConnectionException.class, () -> resolver.requireServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
    }

    @Test
    @DisplayName("a token endpoint other than the pinned one is refused")
    void otherTokenUrl() {
        connection.getOauth().setTokenUrl("https://idp.attacker.example/token");
        assertThrows(ConnectionException.class, () -> resolver.requireServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
    }

    @Test
    @DisplayName("a connection that relies on discovery instead of a pinned tokenUrl is refused")
    void discoveredTokenUrl() {
        connection.getOauth().setTokenUrl(null);
        connection.getOauth().setDiscoveryUrl("https://login.microsoftonline.com/x/v2.0/.well-known/openid-configuration");
        assertThrows(ConnectionException.class, () -> resolver.requireServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
    }

    @Test
    @DisplayName("a connection without the Bot Connector scope is refused")
    void missingScope() {
        connection.getOauth().setScopes(List.of("https://graph.microsoft.com/.default"));
        assertThrows(ConnectionException.class, () -> resolver.requireServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
    }

    @Test
    @DisplayName("a STATIC or PER_USER connection is refused")
    void notAServiceAccount() {
        connection.setAuthType(AuthType.STATIC);
        assertThrows(ConnectionException.class, () -> resolver.requireServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.PER_USER);
        assertThrows(ConnectionException.class, () -> resolver.requireServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
    }

    @Test
    @DisplayName("proof obtains one fresh service token, never a stored grant")
    void proofMintsFreshToken() {
        when(accessTokenSupplier.freshServiceToken(connection)).thenReturn("token");
        assertDoesNotThrow(() -> resolver.proveServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
        verify(accessTokenSupplier).freshServiceToken(eq(connection));
        // A grant keyed by the connection's name can predate an edit of its client id.
        verify(accessTokenSupplier, never()).accessToken(any(), any());
    }

    @Test
    @DisplayName("proof fails when the identity provider refuses the secret")
    void proofRefused() {
        when(accessTokenSupplier.freshServiceToken(connection))
                .thenThrow(new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "invalid_client"));
        assertThrows(ConnectionException.class, () -> resolver.proveServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
    }

    @Test
    @DisplayName("a supplier that cannot mint proves nothing")
    void defaultSupplierRefuses() {
        AccessTokenSupplier plain = (c, p) -> "stored-grant-token";
        assertThrows(ConnectionException.class, () -> plain.freshServiceToken(connection));
    }

    @Test
    @DisplayName("the per-request check reuses a proof of the same connection, and proves again after an edit")
    void provenIdentityIsRemembered() {
        when(accessTokenSupplier.freshServiceToken(any())).thenReturn("token");
        resolver.requireProvenServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE);
        resolver.requireProvenServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE);
        verify(accessTokenSupplier, times(1)).freshServiceToken(any());

        connection.getOauth().setClientSecret("${vault:another-secret}");
        resolver.requireProvenServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE);
        verify(accessTokenSupplier, times(2)).freshServiceToken(any());
    }

    @Test
    @DisplayName("a failed per-request proof is remembered, so it is not retried on every activity")
    void failedProofIsRemembered() {
        when(accessTokenSupplier.freshServiceToken(any()))
                .thenThrow(new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "invalid_client"));
        assertThrows(ConnectionException.class, () -> resolver.requireProvenServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
        assertThrows(ConnectionException.class, () -> resolver.requireProvenServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
        verify(accessTokenSupplier, times(1)).freshServiceToken(any());
    }

    @Test
    @DisplayName("the per-request check refuses an identity mismatch without minting")
    void provenIdentityChecksFirst() {
        connection.getOauth().setTokenUrl("https://idp.attacker.example/token");
        assertThrows(ConnectionException.class, () -> resolver.requireProvenServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
        verifyNoInteractions(accessTokenSupplier);
    }

    @Test
    @DisplayName("proof never mints for a mismatched identity")
    void proofChecksIdentityFirst() {
        connection.getOauth().setClientId("99999999-2222-3333-4444-555555555555");
        assertThrows(ConnectionException.class, () -> resolver.proveServiceIdentity(REF, APP_ID, TOKEN_URL, SCOPE));
        verifyNoInteractions(accessTokenSupplier);
    }
}
