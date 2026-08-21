/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write-boundary rules. Each of these is the single point where a
 * configuration that would defeat the vault, export scrubbing or deploy-time
 * grant enforcement is refused, so each gets its own test rather than being
 * covered incidentally.
 */
class ConnectionConfigurationValidationTest {

    private static ConnectionConfiguration staticConnection() {
        var connection = new ConnectionConfiguration();
        connection.setName("jira");
        connection.setAuthType(AuthType.STATIC);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://api.atlassian.com"));
        var auth = new StaticAuth();
        auth.setHeaderName("Authorization");
        auth.setValueTemplate("Bearer ${vault:jira-token}");
        connection.setStaticAuth(auth);
        return connection;
    }

    private static ConnectionConfiguration oauthConnection(AuthType authType) {
        var connection = new ConnectionConfiguration();
        connection.setName("atlassian");
        connection.setAuthType(authType);
        connection.setBinding(authType == AuthType.OAUTH2_AUTHORIZATION_CODE ? Binding.PER_USER : Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://api.atlassian.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://auth.atlassian.com/oauth/token");
        oauth.setAuthorizationUrl("https://auth.atlassian.com/authorize");
        oauth.setClientId("client-abc");
        oauth.setClientSecret("${vault:atlassian-client-secret}");
        oauth.setScopes(List.of("read:jira-work"));
        connection.setOauth(oauth);
        return connection;
    }

    @Test
    @DisplayName("a well-formed static connection validates")
    void acceptsWellFormedStatic() {
        assertDoesNotThrow(() -> staticConnection().validate());
    }

    @Test
    @DisplayName("a well-formed OAuth connection validates")
    void acceptsWellFormedOAuth() {
        assertDoesNotThrow(() -> oauthConnection(AuthType.OAUTH2_AUTHORIZATION_CODE).validate());
        assertDoesNotThrow(() -> oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS).validate());
    }

    @Nested
    @DisplayName("secrets are reference-only")
    class ReferenceOnly {

        @Test
        @DisplayName("a literal client secret is refused, and the message names the fix")
        void refusesLiteralClientSecret() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setClientSecret("actual-secret-value");

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("secretstore"), "the message must name where the value belongs: " + error.getMessage());
        }

        @Test
        @DisplayName("a value that merely CONTAINS a reference is not a reference")
        void refusesLiteralWithReferenceAppended() {
            // The bypass a `find`-based check would allow: a literal key with an
            // unused reference stapled on to satisfy the pattern.
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setClientSecret("sk-live-abcdef${vault:unused}");

            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        @DisplayName("a header template with no reference at all is a plaintext credential")
        void refusesTemplateWithoutAnyReference() {
            var connection = staticConnection();
            connection.getStaticAuth().setValueTemplate("Bearer sk-live-abcdefghijklmnop");

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("plaintext credential"), error.getMessage());
        }

        @Test
        @DisplayName("a header template may mix literal text with a reference")
        void acceptsSchemePlusReference() {
            var connection = staticConnection();
            connection.getStaticAuth().setValueTemplate("Bearer ${vault:jira-token}");

            assertDoesNotThrow(connection::validate);
        }

        @Test
        @DisplayName("a credential-shaped extraAuthParam is refused")
        void refusesCredentialInExtraAuthParams() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setExtraAuthParams(Map.of("client_secret", "oops"));

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("credential-shaped"), error.getMessage());
        }

        @Test
        @DisplayName("a non-secret protocol parameter is fine")
        void acceptsProtocolParams() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setExtraAuthParams(Map.of("prompt", "consent", "audience", "api.atlassian.com"));

            assertDoesNotThrow(connection::validate);
        }
    }

    @Nested
    @DisplayName("allowlists")
    class Allowlists {

        @Test
        @DisplayName("an empty baseUrlAllowlist is refused — a credential must name where it may go")
        void refusesEmptyAllowlist() {
            var connection = staticConnection();
            connection.setBaseUrlAllowlist(List.of());

            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        @DisplayName("a scheme-less origin fails loudly rather than silently never matching")
        void refusesSchemelessOrigin() {
            var connection = staticConnection();
            connection.setBaseUrlAllowlist(List.of("api.atlassian.com"));

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("bare origin") || error.getMessage().contains("http or https"), error.getMessage());
        }

        @Test
        @DisplayName("an origin with a path is refused — it is not an origin")
        void refusesOriginWithPath() {
            var connection = staticConnection();
            connection.setBaseUrlAllowlist(List.of("https://api.atlassian.com/v1"));

            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        @DisplayName("a token URL over http is refused — the client secret is sent to it")
        void refusesPlaintextTokenUrl() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setTokenUrl("http://auth.atlassian.com/oauth/token");

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("https"), error.getMessage());
        }

        @Test
        @DisplayName("a token URL with userinfo is refused")
        void refusesTokenUrlWithUserInfo() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setTokenUrl("https://user:pass@auth.atlassian.com/oauth/token");

            assertThrows(IllegalArgumentException.class, connection::validate);
        }
    }

    @Nested
    @DisplayName("binding and PKCE")
    class BindingRules {

        @Test
        @DisplayName("PER_USER on a static connection is refused — a static key is the same key for everyone")
        void refusesPerUserOnStatic() {
            var connection = staticConnection();
            connection.setBinding(Binding.PER_USER);

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("OAUTH2_AUTHORIZATION_CODE"), error.getMessage());
        }

        @Test
        @DisplayName("PER_USER on client_credentials is refused — there is no per-user grant to resolve")
        void refusesPerUserOnClientCredentials() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.setBinding(Binding.PER_USER);

            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        @DisplayName("PKCE cannot be turned off for the authorization-code flow")
        void refusesDisablingPkce() {
            var connection = oauthConnection(AuthType.OAUTH2_AUTHORIZATION_CODE);
            connection.getOauth().setUsePkce(false);

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("interception"), "the message must say why: " + error.getMessage());
        }

        @Test
        @DisplayName("the authorization-code flow needs an authorizationUrl")
        void refusesMissingAuthorizationUrl() {
            var connection = oauthConnection(AuthType.OAUTH2_AUTHORIZATION_CODE);
            connection.getOauth().setAuthorizationUrl(null);

            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        @DisplayName("an unrecognised client auth method is refused rather than guessed")
        void refusesUnknownClientAuthMethod() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setClientAuthMethod("magic");

            assertThrows(IllegalArgumentException.class, connection::validate);
        }
    }

    @Test
    @DisplayName("a nameless connection is refused — the name is what ${connection:…} refers to")
    void refusesBlankName() {
        var connection = staticConnection();
        connection.setName("  ");

        assertThrows(IllegalArgumentException.class, connection::validate);
    }

    @Test
    @DisplayName("BASIC requires a username and a vaulted password")
    void basicRequiresUsernameAndVaultedPassword() {
        var connection = staticConnection();
        connection.setAuthType(AuthType.BASIC);
        connection.getStaticAuth().setUsername("svc-eddi");
        connection.getStaticAuth().setPasswordRef("hunter2");

        assertThrows(IllegalArgumentException.class, connection::validate);

        connection.getStaticAuth().setPasswordRef("${vault:jira-password}");
        assertDoesNotThrow(connection::validate);

        connection.getStaticAuth().setUsername(null);
        assertThrows(IllegalArgumentException.class, connection::validate);
    }
}
