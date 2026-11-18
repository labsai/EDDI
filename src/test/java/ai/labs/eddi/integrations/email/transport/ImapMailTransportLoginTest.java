/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which login the IMAP side makes: a {@code MAILBOX}-bound connection logs in
 * with the integration's own mailbox grant (and nothing else), every other
 * OAuth connection as before, and an unlinked mailbox fails as
 * {@code NOT_CONNECTED} so the poller can tell the owners what to do.
 */
class ImapMailTransportLoginTest {

    private static final String REFERENCE = "${connection:gws-mailbox}";
    private static final URI IMAP = URI.create("imaps://imap.gmail.com");

    private ConnectionResolver resolver;
    private ImapMailTransport transport;
    private EmailChannelSettings settings;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resolver = mock(ConnectionResolver.class);
        Instance<ConnectionResolver> resolvers = mock(Instance.class);
        when(resolvers.isResolvable()).thenReturn(true);
        when(resolvers.get()).thenReturn(resolver);
        transport = new ImapMailTransport(mock(SafeSocketConnector.class), (host, port) -> null, resolvers);
        settings = EmailChannelSettings.from(Map.of("transport", "imap", "connection", "gws-mailbox", "host", "imap.gmail.com", "address",
                "support@company.example"));
        when(resolver.authTypeOf(REFERENCE)).thenReturn(Optional.of(AuthType.OAUTH2_AUTHORIZATION_CODE));
    }

    @Test
    @DisplayName("a MAILBOX connection logs in with the integration's own mailbox grant, never the generic token path")
    void mailboxUsesMailboxToken() throws Exception {
        when(resolver.bindingOf(REFERENCE)).thenReturn(Optional.of(Binding.MAILBOX));
        when(resolver.resolveMailboxAccessToken(REFERENCE, IMAP, "support@company.example")).thenReturn("ya29.mailbox");

        assertArrayEquals(new String[]{"support@company.example", "ya29.mailbox", "XOAUTH2"}, transport.login(settings));
        verify(resolver, never()).resolveAccessToken(anyString(), any(), any());
    }

    @Test
    @DisplayName("a SERVICE OAuth connection still takes the client-credentials token")
    void serviceOAuthUnchanged() throws Exception {
        when(resolver.authTypeOf(REFERENCE)).thenReturn(Optional.of(AuthType.OAUTH2_CLIENT_CREDENTIALS));
        when(resolver.bindingOf(REFERENCE)).thenReturn(Optional.of(Binding.SERVICE));
        when(resolver.resolveAccessToken(REFERENCE, IMAP, null)).thenReturn("eyJ.service");

        assertArrayEquals(new String[]{"support@company.example", "eyJ.service", "XOAUTH2"}, transport.login(settings));
        verify(resolver, never()).resolveMailboxAccessToken(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("an unlinked mailbox fails NOT_CONNECTED; any other credential failure stays CREDENTIAL")
    void notConnectedCategory() {
        when(resolver.bindingOf(REFERENCE)).thenReturn(Optional.of(Binding.MAILBOX));
        when(resolver.resolveMailboxAccessToken(REFERENCE, IMAP, "support@company.example"))
                .thenThrow(new ConnectionException(ConnectionException.Reason.NOT_CONNECTED, "not linked"));

        assertEquals(MailboxException.Category.NOT_CONNECTED, assertThrows(MailboxException.class, () -> transport.login(settings)).getCategory());

        doThrow(new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, "blip")).when(resolver)
                .resolveMailboxAccessToken(REFERENCE, IMAP, "support@company.example");
        assertEquals(MailboxException.Category.CREDENTIAL, assertThrows(MailboxException.class, () -> transport.login(settings)).getCategory());
    }
}
