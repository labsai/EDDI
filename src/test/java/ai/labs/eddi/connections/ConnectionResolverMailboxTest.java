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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A {@link Binding#MAILBOX} grant is spent by the email channel's mail login
 * and nowhere else: every other resolver entry point refuses it with
 * {@code UNSUPPORTED_PLACEMENT} before any token is fetched, and
 * {@link ConnectionResolver#resolveMailboxAccessToken} resolves only the grant
 * of the mailbox it is asked for.
 */
class ConnectionResolverMailboxTest {

    private static final URI IMAP = URI.create("imaps://imap.gmail.com");
    private static final URI GMAIL_API = URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages");
    private static final String REFERENCE = "${connection:gws-mailbox}";

    private ConnectionRegistry registry;
    private AccessTokenSupplier accessTokenSupplier;
    private ResolutionPrincipalContext resolutionPrincipalContext;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectionRegistry.class);
        accessTokenSupplier = mock(AccessTokenSupplier.class);
        resolutionPrincipalContext = new ResolutionPrincipalContext();
        meterRegistry = new SimpleMeterRegistry();
        // A verified user is bound, so a refusal cannot be blamed on a missing
        // principal.
        resolutionPrincipalContext.bind(new ResolutionPrincipal("alice", ResolutionPrincipal.Provenance.VERIFIED));
        when(accessTokenSupplier.accessToken(any(), anyString())).thenReturn("ya29.mailbox");
    }

    @AfterEach
    void unbind() {
        resolutionPrincipalContext.clear();
    }

    private ConnectionResolver resolver() {
        var resolver = new ConnectionResolver(registry,
                new CredentialReferenceResolver(mock(SecretResolver.class), mock(GlobalVariableResolver.class)),
                mock(CallerIdentityContext.class), meterRegistry, accessTokenSupplier, true);
        resolver.resolutionPrincipalContext = resolutionPrincipalContext;
        return resolver;
    }

    private void register(ConnectionConfiguration connection) {
        when(registry.require(any(ConnectionReference.class))).thenReturn(connection);
        when(registry.find(any(ConnectionReference.class))).thenReturn(Optional.of(connection));
    }

    static ConnectionConfiguration mailboxConnection(Binding binding) {
        var connection = new ConnectionConfiguration();
        connection.setName("gws-mailbox");
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(binding);
        connection.setBaseUrlAllowlist(List.of("imaps://imap.gmail.com", "smtps://smtp.gmail.com", "https://gmail.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setAuthorizationUrl("https://accounts.google.com/o/oauth2/v2/auth");
        oauth.setTokenUrl("https://oauth2.googleapis.com/token");
        oauth.setClientId("client");
        oauth.setClientSecret("${vault:gws-mailbox-client-secret}");
        oauth.setScopes(List.of("https://mail.google.com/"));
        if (binding == Binding.MAILBOX) {
            oauth.setAccountCheck(OAuthConfig.ACCOUNT_CHECK_GOOGLE_GMAIL_PROFILE);
        }
        connection.setOauth(oauth);
        return connection;
    }

    private void assertRefusedAsMailbox(ConnectionException refused) {
        assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, refused.getReason());
        assertTrue(refused.getMessage().contains("MAILBOX"), refused.getMessage());
        // Refused before any token was fetched — not after spending the grant.
        verifyNoInteractions(accessTokenSupplier);
        assertEquals(1.0, meterRegistry.counter("eddi.connection.resolve.count", "authType", "OAUTH2_AUTHORIZATION_CODE", "binding", "MAILBOX",
                "outcome", "unsupported_placement").count());
    }

    @Nested
    @DisplayName("every other entry point refuses a MAILBOX connection")
    class Refusals {

        @Test
        @DisplayName("resolve — an httpcall / MCP / A2A / ingestion header")
        void resolveHeader() {
            register(mailboxConnection(Binding.MAILBOX));
            assertRefusedAsMailbox(assertThrows(ConnectionException.class, () -> resolver().resolve(REFERENCE, GMAIL_API, "alice")));
        }

        @Test
        @DisplayName("resolveAccessToken — the bare token the system mailer and other XOAUTH2 users take")
        void resolveAccessToken() {
            register(mailboxConnection(Binding.MAILBOX));
            assertRefusedAsMailbox(assertThrows(ConnectionException.class, () -> resolver().resolveAccessToken(REFERENCE, IMAP, null)));
        }

        @Test
        @DisplayName("resolveBasicLogin — a username/password mail login")
        void resolveBasicLogin() {
            register(mailboxConnection(Binding.MAILBOX));
            assertRefusedAsMailbox(assertThrows(ConnectionException.class, () -> resolver().resolveBasicLogin(REFERENCE, IMAP)));
        }

        @Test
        @DisplayName("resolveForDiscovery — refused loudly, not withheld into an empty tool list")
        void resolveForDiscovery() {
            register(mailboxConnection(Binding.MAILBOX));
            assertRefusedAsMailbox(assertThrows(ConnectionException.class, () -> resolver().resolveForDiscovery(REFERENCE, GMAIL_API)));
        }
    }

    @Nested
    @DisplayName("resolveMailboxAccessToken")
    class MailboxToken {

        @Test
        @DisplayName("resolves the grant filed under mailbox:<normalised address>, whoever is bound to the turn")
        void resolvesTheMailboxGrant() {
            register(mailboxConnection(Binding.MAILBOX));

            assertEquals("ya29.mailbox", resolver().resolveMailboxAccessToken(REFERENCE, IMAP, "Support@Company.example"));
            verify(accessTokenSupplier).accessToken(any(), eq("mailbox:support@company.example"));
        }

        @Test
        @DisplayName("refuses a connection that is not MAILBOX-bound, so a PER_USER grant is never read as a mailbox's")
        void refusesOtherBindings() {
            register(mailboxConnection(Binding.PER_USER));

            ConnectionException refused = assertThrows(ConnectionException.class,
                    () -> resolver().resolveMailboxAccessToken(REFERENCE, IMAP, "support@company.example"));
            assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, refused.getReason());
            verifyNoInteractions(accessTokenSupplier);
        }

        @Test
        @DisplayName("still enforces the allowlist")
        void enforcesAllowlist() {
            register(mailboxConnection(Binding.MAILBOX));

            ConnectionException refused = assertThrows(ConnectionException.class,
                    () -> resolver().resolveMailboxAccessToken(REFERENCE, URI.create("imaps://imap.evil.example"), "support@company.example"));
            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, refused.getReason());
            verifyNoInteractions(accessTokenSupplier);
        }

        @Test
        @DisplayName("a rejected grant (REFRESH_FAILED) reads as NOT_CONNECTED, naming the re-link")
        void rejectedGrantIsNotConnected() {
            register(mailboxConnection(Binding.MAILBOX));
            when(accessTokenSupplier.accessToken(any(), anyString()))
                    .thenThrow(new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "REFRESH_FAILED"));

            ConnectionException refused = assertThrows(ConnectionException.class,
                    () -> resolver().resolveMailboxAccessToken(REFERENCE, IMAP, "support@company.example"));
            assertEquals(ConnectionException.Reason.NOT_CONNECTED, refused.getReason());
            assertTrue(refused.getMessage().contains("mailbox-grants/authorize"), refused.getMessage());
        }

        @Test
        @DisplayName("a transient failure stays transient")
        void transientStaysTransient() {
            register(mailboxConnection(Binding.MAILBOX));
            when(accessTokenSupplier.accessToken(any(), anyString()))
                    .thenThrow(new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, "blip"));

            ConnectionException refused = assertThrows(ConnectionException.class,
                    () -> resolver().resolveMailboxAccessToken(REFERENCE, IMAP, "support@company.example"));
            assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, refused.getReason());
        }

        @Test
        @DisplayName("a malformed address is a configuration error, not a lookup under a junk principal")
        void malformedAddress() {
            register(mailboxConnection(Binding.MAILBOX));

            ConnectionException refused = assertThrows(ConnectionException.class,
                    () -> resolver().resolveMailboxAccessToken(REFERENCE, IMAP, "not an address"));
            assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, refused.getReason());
            verifyNoInteractions(accessTokenSupplier);
        }
    }

    @Test
    @DisplayName("the save-time mail login check accepts a MAILBOX connection")
    void mailLoginTargetAcceptsMailbox() {
        register(mailboxConnection(Binding.MAILBOX));
        resolver().requireMailLoginTarget(REFERENCE, IMAP);
    }
}
