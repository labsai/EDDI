/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.RedirectPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Gmail-profile account check: the account named by the profile must be the
 * mailbox, nothing else counts as proof either way, and the token only travels
 * where the connection allows, through {@link OutboundHttp}, with no redirect.
 */
class MailboxAccountCheckTest {

    private static final String MAILBOX = "support@company.example";

    private OutboundHttp http;
    private MailboxAccountCheck check;

    @BeforeEach
    void setUp() {
        http = mock(OutboundHttp.class);
        check = new MailboxAccountCheck(http);
    }

    private static ConnectionConfiguration connection() {
        var connection = new ConnectionConfiguration();
        connection.setName("gws-support-mailbox");
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.MAILBOX);
        connection.setBaseUrlAllowlist(List.of("imaps://imap.gmail.com", "https://gmail.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setAccountCheck(OAuthConfig.ACCOUNT_CHECK_GOOGLE_GMAIL_PROFILE);
        connection.setOauth(oauth);
        return connection;
    }

    private void profileAnswers(int status, String body) throws Exception {
        when(http.send(any(), any())).thenReturn(new OutboundResponse<>(status, "", Map.of(), MailboxAccountCheck.GMAIL_PROFILE, List.of(), body,
                false));
    }

    @Test
    @DisplayName("the profile of the mailbox's own account matches, case and all")
    void match() throws Exception {
        profileAnswers(200, "{\"emailAddress\":\"Support@Company.Example\",\"messagesTotal\":3}");

        assertEquals(MailboxAccountCheck.Outcome.MATCH, check.check(connection(), "ya29.token", MAILBOX));

        var request = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(http).send(request.capture(), any());
        assertEquals(MailboxAccountCheck.GMAIL_PROFILE, request.getValue().uri());
        assertEquals(List.of("Bearer ya29.token"), request.getValue().headers().get("Authorization"));
        assertEquals(RedirectPolicy.NEVER, request.getValue().redirectOverride());
    }

    @Test
    @DisplayName("another account's profile is a mismatch")
    void mismatch() throws Exception {
        profileAnswers(200, "{\"emailAddress\":\"alice@company.example\"}");

        assertEquals(MailboxAccountCheck.Outcome.MISMATCH, check.check(connection(), "ya29.token", MAILBOX));
    }

    @Test
    @DisplayName("an error status proves nothing either way")
    void errorStatusIsUnavailable() throws Exception {
        profileAnswers(403, "{\"error\":{\"code\":403}}");

        assertEquals(MailboxAccountCheck.Outcome.UNAVAILABLE, check.check(connection(), "ya29.token", MAILBOX));
    }

    @Test
    @DisplayName("a profile with no address, or no JSON, proves nothing")
    void unreadableIsUnavailable() throws Exception {
        profileAnswers(200, "{\"messagesTotal\":3}");
        assertEquals(MailboxAccountCheck.Outcome.UNAVAILABLE, check.check(connection(), "ya29.token", MAILBOX));

        profileAnswers(200, "<html>");
        assertEquals(MailboxAccountCheck.Outcome.UNAVAILABLE, check.check(connection(), "ya29.token", MAILBOX));
    }

    @Test
    @DisplayName("an unreachable Gmail API proves nothing")
    void unreachableIsUnavailable() throws Exception {
        when(http.send(any(), any())).thenThrow(new IOException("connection reset"));

        assertEquals(MailboxAccountCheck.Outcome.UNAVAILABLE, check.check(connection(), "ya29.token", MAILBOX));
    }

    @Test
    @DisplayName("without the Gmail API on the allowlist the token is not sent at all")
    void allowlistIsRequired() {
        var connection = connection();
        connection.setBaseUrlAllowlist(List.of("imaps://imap.gmail.com"));

        var refused = assertThrows(ConnectionException.class, () -> check.check(connection, "ya29.token", MAILBOX));
        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, refused.getReason());
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("a connection naming no known check is refused before anything is sent")
    void unknownCheckIsRefused() {
        var connection = connection();
        connection.getOauth().setAccountCheck(null);

        assertThrows(ConnectionException.class, () -> check.check(connection, "ya29.token", MAILBOX));
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("a requested address that is not an address can never match")
    void malformedExpectedAddress() throws Exception {
        profileAnswers(200, "{\"emailAddress\":\"support@company.example\"}");

        assertEquals(MailboxAccountCheck.Outcome.MISMATCH, check.check(connection(), "ya29.token", "not an address"));
    }
}
