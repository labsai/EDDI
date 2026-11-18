/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Every refresh of a {@code MAILBOX} grant re-proves the account before the new
 * token is used: a mismatch deletes the grant, a match is stored as usual, and
 * "could not tell" neither deletes the grant nor serves the unverified token.
 */
class OAuthTokenServiceMailboxRecheckTest {

    private static final String TENANT = "default";
    private static final String CONNECTION = "gws-support-mailbox";
    private static final String MAILBOX = "support@company.example";
    private static final String PRINCIPAL = "mailbox:" + MAILBOX;

    private InMemoryConnectionGrantStore grantStore;
    private OAuthTokenClient tokenClient;
    private MailboxAccountCheck accountCheck;
    private ISecretProvider secretProvider;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() throws Exception {
        grantStore = new InMemoryConnectionGrantStore();
        tokenClient = mock(OAuthTokenClient.class);
        accountCheck = mock(MailboxAccountCheck.class);
        meters = new SimpleMeterRegistry();
        secretProvider = mock(ISecretProvider.class);
        // Reversible fake sealing, as in OAuthTokenServiceRefreshTest; the protocol is
        // under test, not the crypto.
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
        when(tokenClient.refresh(any(), anyString(), anyString()))
                .thenReturn(new TokenResponse("ya29.refreshed", "1//rotated", Duration.ofHours(1), List.of("https://mail.google.com/")));
        seedExpiredGrant();
    }

    private OAuthTokenService service() throws Exception {
        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(secretResolver.resolveValue(anyString())).thenReturn("client-secret-value");
        IConnectionStore connectionStore = mock(IConnectionStore.class);
        lenient().when(connectionStore.idOfName(anyString(), anyString())).thenReturn("connection-1");
        var service = new OAuthTokenService(grantStore, tokenClient, secretProvider,
                new CredentialReferenceResolver(secretResolver, globalVariableResolver), meters, connectionStore);
        service.mailboxAccountCheck = accountCheck;
        return service;
    }

    private static ConnectionConfiguration connection() {
        var connection = new ConnectionConfiguration();
        connection.setName(CONNECTION);
        connection.setTenantId(TENANT);
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.MAILBOX);
        connection.setBaseUrlAllowlist(List.of("imaps://imap.gmail.com", "https://gmail.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://oauth2.googleapis.com/token");
        oauth.setAuthorizationUrl("https://accounts.google.com/o/oauth2/v2/auth");
        oauth.setClientId("client");
        oauth.setClientSecret("${vault:client-secret}");
        oauth.setAccountCheck(OAuthConfig.ACCOUNT_CHECK_GOOGLE_GMAIL_PROFILE);
        connection.setOauth(oauth);
        return connection;
    }

    private void seedExpiredGrant() {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(CONNECTION);
        grant.setPrincipal(PRINCIPAL);
        grant.setEncryptedAccessToken("sealed:old-access");
        grant.setAccessTokenIv("iv");
        grant.setEncryptedRefreshToken("sealed:old-refresh");
        grant.setRefreshTokenIv("iv");
        grant.setDekId("dek");
        grant.setExpiresAt(Instant.now().minusSeconds(60));
        grant.setStatus(ConnectionGrant.Status.ACTIVE);
        grantStore.seed(grant);
    }

    @Test
    @DisplayName("a refreshed token of the mailbox's own account is stored and served")
    void matchIsStored() throws Exception {
        when(accountCheck.check(any(), eq("ya29.refreshed"), eq(MAILBOX))).thenReturn(MailboxAccountCheck.Outcome.MATCH);

        assertEquals("ya29.refreshed", service().accessToken(connection(), PRINCIPAL));

        ConnectionGrant stored = grantStore.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow();
        assertEquals("sealed:ya29.refreshed", stored.getEncryptedAccessToken());
        assertTrue(stored.getExpiresAt().isAfter(Instant.now().plusSeconds(600)));
    }

    @Test
    @DisplayName("a refreshed token of another account deletes the grant and fails NOT_CONNECTED")
    void mismatchDeletesTheGrant() throws Exception {
        when(accountCheck.check(any(), any(), any())).thenReturn(MailboxAccountCheck.Outcome.MISMATCH);

        ConnectionException refused = assertThrows(ConnectionException.class, () -> service().accessToken(connection(), PRINCIPAL));

        assertEquals(ConnectionException.Reason.NOT_CONNECTED, refused.getReason());
        assertFalse(grantStore.find(TENANT, CONNECTION, PRINCIPAL).isPresent(), "a grant of another account must not survive");
        assertEquals(1.0, meters.counter("eddi.connection.token.refresh.count", "outcome", "account_mismatch").count());
        assertEquals(0.0, meters.counter("eddi.connection.token.refresh.count", "outcome", "transient").count(),
                "a deleted grant is not a transient failure");
    }

    @Test
    @DisplayName("an unverifiable account keeps the grant and the rotated refresh token, but never serves the unchecked token")
    void unavailableKeepsGrantButServesNothing() throws Exception {
        when(accountCheck.check(any(), any(), any())).thenReturn(MailboxAccountCheck.Outcome.UNAVAILABLE);

        ConnectionException refused = assertThrows(ConnectionException.class, () -> service().accessToken(connection(), PRINCIPAL));

        assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, refused.getReason());
        ConnectionGrant kept = grantStore.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow();
        assertEquals(ConnectionGrant.Status.ACTIVE, kept.getStatus());
        assertEquals("sealed:1//rotated", kept.getEncryptedRefreshToken(), "a rotated refresh token must not be lost");
        assertFalse(kept.isAccessTokenUsable(Instant.now(), Duration.ZERO), "the unchecked access token must not be usable");

        // The next request refreshes and checks again rather than serving it.
        when(accountCheck.check(any(), any(), any())).thenReturn(MailboxAccountCheck.Outcome.MATCH);
        assertEquals("ya29.refreshed", service().accessToken(connection(), PRINCIPAL));
        verify(tokenClient, times(2)).refresh(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a PER_USER refresh is never sent to the mailbox check")
    void perUserIsNotChecked() throws Exception {
        var perUser = connection();
        perUser.setBinding(Binding.PER_USER);
        perUser.getOauth().setAccountCheck(null);
        var grant = grantStore.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow();
        grant.setPrincipal("alice");
        grantStore.seed(grant);

        assertNotEquals(null, service().accessToken(perUser, "alice"));
        verify(accountCheck, never()).check(any(), any(), any());
    }
}
