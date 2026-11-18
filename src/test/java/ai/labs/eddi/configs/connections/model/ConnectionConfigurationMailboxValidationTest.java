/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Binding#MAILBOX}: authorization-code only, with a named account check
 * whose endpoint the allowlist lets the token reach — and the account check
 * refused on every other binding.
 */
class ConnectionConfigurationMailboxValidationTest {

    private static ConnectionConfiguration mailbox() {
        var connection = new ConnectionConfiguration();
        connection.setName("gws-support-mailbox");
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.MAILBOX);
        connection.setBaseUrlAllowlist(List.of("imaps://imap.gmail.com", "smtps://smtp.gmail.com", "https://gmail.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setAuthorizationUrl("https://accounts.google.com/o/oauth2/v2/auth");
        oauth.setTokenUrl("https://oauth2.googleapis.com/token");
        oauth.setClientId("client-id");
        oauth.setClientSecret("${vault:gws-mailbox-client-secret}");
        oauth.setScopes(List.of("https://mail.google.com/"));
        oauth.setAccountCheck(OAuthConfig.ACCOUNT_CHECK_GOOGLE_GMAIL_PROFILE);
        connection.setOauth(oauth);
        return connection;
    }

    @Test
    @DisplayName("the documented Google Workspace mailbox connection validates")
    void acceptsTheDocumentedShape() {
        assertDoesNotThrow(() -> mailbox().validate());
    }

    @Test
    @DisplayName("MAILBOX with client credentials is refused — only the auth-code flow links a mailbox")
    void refusesMailboxWithClientCredentials() {
        var connection = mailbox();
        connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        connection.getOauth().setAuthorizationUrl(null);

        var error = assertThrows(IllegalArgumentException.class, connection::validate);
        assertTrue(error.getMessage().contains("MAILBOX binding requires authType OAUTH2_AUTHORIZATION_CODE"), error.getMessage());
    }

    @Test
    @DisplayName("MAILBOX on a static connection is refused")
    void refusesMailboxOnStatic() {
        var connection = new ConnectionConfiguration();
        connection.setName("static");
        connection.setAuthType(AuthType.STATIC);
        connection.setBinding(Binding.MAILBOX);
        connection.setBaseUrlAllowlist(List.of("https://gmail.googleapis.com"));
        var auth = new StaticAuth();
        auth.setHeaderName("Authorization");
        auth.setValueTemplate("Bearer ${vault:k}");
        connection.setStaticAuth(auth);

        assertThrows(IllegalArgumentException.class, connection::validate);
    }

    @Test
    @DisplayName("the auth-code flow still refuses SERVICE, and names MAILBOX as the other legal binding")
    void authCodeStillRefusesService() {
        var connection = mailbox();
        connection.setBinding(Binding.SERVICE);
        connection.getOauth().setAccountCheck(null);

        var error = assertThrows(IllegalArgumentException.class, connection::validate);
        assertTrue(error.getMessage().contains("PER_USER") && error.getMessage().contains("MAILBOX"), error.getMessage());
    }

    @Test
    @DisplayName("MAILBOX without an account check is refused — nothing would prove whose mailbox was linked")
    void requiresAccountCheck() {
        var connection = mailbox();
        connection.getOauth().setAccountCheck(null);

        var error = assertThrows(IllegalArgumentException.class, connection::validate);
        assertTrue(error.getMessage().contains("oauth.accountCheck"), error.getMessage());
    }

    @Test
    @DisplayName("an unknown account check is refused")
    void refusesUnknownAccountCheck() {
        var connection = mailbox();
        connection.getOauth().setAccountCheck("https://attacker.example/whoami");

        assertThrows(IllegalArgumentException.class, connection::validate);
    }

    @Test
    @DisplayName("google-gmail-profile needs https://gmail.googleapis.com on the allowlist")
    void requiresGmailApiOnAllowlist() {
        var connection = mailbox();
        connection.setBaseUrlAllowlist(List.of("imaps://imap.gmail.com", "smtps://smtp.gmail.com"));

        var error = assertThrows(IllegalArgumentException.class, connection::validate);
        assertTrue(error.getMessage().contains("https://gmail.googleapis.com"), error.getMessage());
    }

    @Test
    @DisplayName("an account check on a PER_USER connection is refused rather than ignored")
    void refusesAccountCheckOnPerUser() {
        var connection = mailbox();
        connection.setBinding(Binding.PER_USER);

        var error = assertThrows(IllegalArgumentException.class, connection::validate);
        assertTrue(error.getMessage().contains("applies only to binding MAILBOX"), error.getMessage());
    }

    @Test
    @DisplayName("allowUnverifiedPrincipal stays PER_USER-only")
    void refusesUnverifiedFlag() {
        var connection = mailbox();
        connection.setAllowUnverifiedPrincipal(true);

        assertThrows(IllegalArgumentException.class, connection::validate);
    }
}
