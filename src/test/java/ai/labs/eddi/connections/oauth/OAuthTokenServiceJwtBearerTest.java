/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.CredentialReferenceResolver;
import ai.labs.eddi.connections.grants.ConnectionGrant;
import ai.labs.eddi.connections.grants.InMemoryConnectionGrantStore;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The signed-JWT service account rides the same {@link AccessTokenSupplier
 * access-token supplier} as {@code client_credentials}: minted on first use,
 * served from the sealed grant while it lives, re-minted — never "refreshed"
 * with a refresh token it does not have — once it expires or is rejected.
 * <p>
 * The point of going through the supplier rather than signing per request is
 * the cache: one token per hour, not one token request per outbound call.
 */
class OAuthTokenServiceJwtBearerTest {

    private static final String KEY_MATERIAL = "-----BEGIN PRIVATE KEY-----resolved-----END PRIVATE KEY-----";

    private InMemoryConnectionGrantStore grantStore;
    private OAuthTokenClient tokenClient;
    private SecretResolver secretResolver;
    private OAuthTokenService service;

    @BeforeEach
    void setUp() throws Exception {
        grantStore = new InMemoryConnectionGrantStore();
        tokenClient = mock(OAuthTokenClient.class);
        IConnectionStore connectionStore = mock(IConnectionStore.class);
        lenient().when(connectionStore.idOfName(anyString(), anyString())).thenReturn("connection-1");
        ISecretProvider secretProvider = mock(ISecretProvider.class);
        lenient().when(secretProvider.sealAll(anyString(), any(), any())).thenAnswer(i -> {
            List<String> plaintexts = i.getArgument(1);
            List<ISecretProvider.SealedValue> sealed = new ArrayList<>();
            for (String plaintext : plaintexts) {
                sealed.add(plaintext == null ? null : new ISecretProvider.SealedValue("sealed:" + plaintext, "iv-" + plaintext, "dek"));
            }
            return sealed;
        });
        lenient().when(secretProvider.unseal(anyString(), any(), anyString()))
                .thenAnswer(i -> ((ISecretProvider.SealedValue) i.getArgument(1)).ciphertext().substring("sealed:".length()));
        secretResolver = mock(SecretResolver.class);
        lenient().when(secretResolver.resolveValue(anyString())).thenReturn(KEY_MATERIAL);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        service = new OAuthTokenService(grantStore, tokenClient, secretProvider,
                new CredentialReferenceResolver(secretResolver, globalVariableResolver), new SimpleMeterRegistry(), connectionStore);
    }

    private static ConnectionConfiguration connection() {
        ConnectionConfiguration connection = JwtAssertionsTest.jwtBearerConnection();
        connection.setTenantId("default");
        return connection;
    }

    @Test
    @DisplayName("the first call mints with the vaulted private key; later calls are served from the sealed grant")
    void mintsOnceThenCaches() {
        when(tokenClient.jwtBearer(any(), anyString())).thenReturn(new TokenResponse("ya29.first", null, Duration.ofHours(1), List.of()));
        var connection = connection();

        assertEquals("ya29.first", service.accessToken(connection, ConnectionResolver.SERVICE_PRINCIPAL));
        assertEquals("ya29.first", service.accessToken(connection, ConnectionResolver.SERVICE_PRINCIPAL));
        assertEquals("ya29.first", service.accessToken(connection, ConnectionResolver.SERVICE_PRINCIPAL));

        verify(tokenClient, times(1)).jwtBearer(any(), eq(KEY_MATERIAL));
        verify(secretResolver).resolveValue("${vault:google-sa-key}");
        verify(tokenClient, never()).clientCredentials(any(), anyString());
    }

    @Test
    @DisplayName("an expired grant is re-minted with a new assertion, never refreshed with a refresh token")
    void expiredGrantIsReMinted() {
        when(tokenClient.jwtBearer(any(), anyString())).thenReturn(new TokenResponse("ya29.second", null, Duration.ofHours(1), List.of()));
        var expired = new ConnectionGrant();
        expired.setTenantId("default");
        expired.setConnectionName("google-sa");
        expired.setPrincipal(ConnectionResolver.SERVICE_PRINCIPAL);
        expired.setEncryptedAccessToken("sealed:ya29.old");
        expired.setAccessTokenIv("iv");
        expired.setDekId("dek");
        expired.setExpiresAt(Instant.now().minusSeconds(5));
        expired.setStatus(ConnectionGrant.Status.ACTIVE);
        grantStore.seed(expired);

        assertEquals("ya29.second", service.accessToken(connection(), ConnectionResolver.SERVICE_PRINCIPAL));
        verify(tokenClient).jwtBearer(any(), eq(KEY_MATERIAL));
        verify(tokenClient, never()).refresh(any(), any(), any());
    }

    @Test
    @DisplayName("a grant left REFRESH_FAILED by a rejected assertion is re-minted, as for client_credentials")
    void refreshFailedIsReMinted() {
        when(tokenClient.jwtBearer(any(), anyString()))
                .thenThrow(new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "invalid_grant"))
                .thenReturn(new TokenResponse("ya29.recovered", null, Duration.ofHours(1), List.of()));
        var failed = new ConnectionGrant();
        failed.setTenantId("default");
        failed.setConnectionName("google-sa");
        failed.setPrincipal(ConnectionResolver.SERVICE_PRINCIPAL);
        failed.setEncryptedAccessToken("sealed:ya29.old");
        failed.setAccessTokenIv("iv");
        failed.setDekId("dek");
        failed.setExpiresAt(Instant.now().minusSeconds(5));
        failed.setStatus(ConnectionGrant.Status.REFRESH_FAILED);
        grantStore.seed(failed);

        assertThrows(ConnectionException.class, () -> service.accessToken(connection(), ConnectionResolver.SERVICE_PRINCIPAL));
        assertEquals("ya29.recovered", service.accessToken(connection(), ConnectionResolver.SERVICE_PRINCIPAL));
    }

    @Test
    @DisplayName("private_key_jwt client_credentials resolves oauth.privateKey, not oauth.clientSecret")
    void privateKeyJwtResolvesThePrivateKey() {
        when(tokenClient.clientCredentials(any(), anyString())).thenReturn(new TokenResponse("eyJ.entra", null, Duration.ofHours(1), List.of()));
        ConnectionConfiguration connection = JwtAssertionsTest.privateKeyJwtConnection();
        connection.setTenantId("default");
        connection.getOauth().setClientSecret(null);
        connection.getOauth().setClientAuthMethod(OAuthConfig.CLIENT_AUTH_PRIVATE_KEY_JWT);
        assertEquals(AuthType.OAUTH2_CLIENT_CREDENTIALS, connection.getAuthType());

        assertEquals("eyJ.entra", service.accessToken(connection, ConnectionResolver.SERVICE_PRINCIPAL));
        verify(secretResolver).resolveValue("${vault:entra-key}");
        verify(tokenClient).clientCredentials(any(), eq(KEY_MATERIAL));
    }
}
