/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two rules about where a credential may go and whose credential it is.
 * <p>
 * The origin half is written as string equality between two canonical forms
 * because that is exactly how {@code ConnectionResolver} decides: it
 * canonicalises the allowlist entry and the target and compares the results. So
 * the property worth pinning is not "canonicalOrigin produces this literal" but
 * "these two spellings of one origin produce the SAME string, and two genuinely
 * different origins do not".
 */
class ConnectionOriginAndPrincipalTest {

    private static String allowlistEntry(String origin) {
        return ConnectionConfiguration.requireCanonicalOrigin(origin, "baseUrlAllowlist");
    }

    private static String targetOrigin(String url) {
        return ConnectionConfiguration.canonicalOrigin(URI.create(url));
    }

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

    private static ConnectionConfiguration perUserConnection() {
        var connection = new ConnectionConfiguration();
        connection.setName("drive");
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.PER_USER);
        connection.setBaseUrlAllowlist(List.of("https://www.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setAuthorizationUrl("https://accounts.google.com/o/oauth2/v2/auth");
        oauth.setTokenUrl("https://oauth2.googleapis.com/token");
        oauth.setClientId("client-abc");
        oauth.setClientSecret("${vault:google-client-secret}");
        connection.setOauth(oauth);
        return connection;
    }

    @Nested
    @DisplayName("canonical origins fold the scheme's default port")
    class DefaultPortFolding {

        @Test
        @DisplayName("an allowlist entry with no port matches a target that spells out :443")
        void foldsTheDefaultPortOnTheTarget() {
            // The failure this prevents is the nastiest kind: an allowlist that looks
            // correct, was written by hand without a port, and refuses every call
            // because the SDK building the target URL happened to include one.
            assertEquals(allowlistEntry("https://api.example.com"), targetOrigin("https://api.example.com:443/rest/api/3/issue"),
                    "the resolver compares these two strings for equality, so an unfolded :443 is an allowlist that blocks everything");
        }

        @Test
        @DisplayName("and the mirror: an entry that spells out :443 matches a target with no port")
        void foldsTheDefaultPortOnTheAllowlistEntry() {
            // Same fold from the other side. An operator who copied the port into the
            // allowlist must not be worse off than one who did not.
            assertEquals(allowlistEntry("https://api.example.com:443"), targetOrigin("https://api.example.com/rest/api/3/issue"));
        }

        @Test
        @DisplayName("http folds :80 the same way, and never confuses the two schemes")
        void foldsThePlainHttpDefaultPort() {
            assertEquals(allowlistEntry("http://internal.example.com"), targetOrigin("http://internal.example.com:80/v1"));
            assertNotEquals(allowlistEntry("http://internal.example.com"), targetOrigin("https://internal.example.com/v1"),
                    "http and https are different origins; folding must not erase the scheme along with the port");
        }

        @Test
        @DisplayName("a genuinely different port is still a different origin")
        void keepsANonDefaultPort() {
            // The fold must be exactly the default and nothing else, or an allowlist for
            // the public API also permits whatever is listening on :8443.
            assertNotEquals(allowlistEntry("https://api.example.com"), targetOrigin("https://api.example.com:8443/rest"),
                    "a non-default port must not be folded away, or the allowlist stops naming a specific listener");
            assertEquals(allowlistEntry("https://api.example.com:8443"), targetOrigin("https://api.example.com:8443/rest"),
                    "and an entry that names that port must still match it, or the port can never be allowlisted at all");
        }
    }

    @Nested
    @DisplayName("the unverified-principal opt-in")
    class UnverifiedPrincipal {

        @Test
        @DisplayName("it is refused on a SERVICE-bound connection, where it would relax nothing")
        void refusesTheOptInOnServiceBinding() {
            // Ignored rather than refused, it reads to the next person as a posture
            // already in force — so the day the binding changes to PER_USER, a
            // relaxation nobody re-decided comes into force with it.
            var connection = staticConnection();
            assertDoesNotThrow(connection::validate, "the fixture must be otherwise valid, or the refusal below proves nothing");

            connection.setAllowUnverifiedPrincipal(true);

            var error = assertThrows(IllegalArgumentException.class, connection::validate);
            assertTrue(error.getMessage().contains("allowUnverifiedPrincipal"),
                    "the refusal must name the flag, or it is indistinguishable from the binding rules next to it: " + error.getMessage());
        }

        @Test
        @DisplayName("it is refused on a SERVICE-bound OAuth connection too — the rule is about binding, not auth type")
        void refusesTheOptInOnClientCredentials() {
            // client_credentials resolves one shared grant for everybody, so there is no
            // "which user" for the flag to loosen.
            var connection = perUserConnection();
            connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.setBinding(Binding.SERVICE);
            connection.setAllowUnverifiedPrincipal(true);

            var error = assertThrows(IllegalArgumentException.class, connection::validate);
            assertTrue(error.getMessage().contains("allowUnverifiedPrincipal"), error.getMessage());
        }

        @Test
        @DisplayName("it is accepted on the one binding it means something for")
        void acceptsTheOptInOnPerUserBinding() {
            var connection = perUserConnection();
            connection.setAllowUnverifiedPrincipal(true);

            assertDoesNotThrow(connection::validate, "a deployment that authenticates its users upstream must be able to say so");
        }
    }
}
