/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        @ParameterizedTest
        @DisplayName("every common scheme prefix passes as literal text")
        @ValueSource(strings = {"Bearer ", "Basic ", "token=", "SSWS ", "Token token=", "ApiKey "})
        void acceptsSchemePrefixes(String scheme) {
            var connection = staticConnection();
            connection.getStaticAuth().setValueTemplate(scheme + "${vault:jira-token}");

            assertDoesNotThrow(connection::validate);
        }

        @Test
        @DisplayName("a literal key with a reference stapled on is refused, and quoted back redacted")
        void refusesLiteralKeyBeforeReference() {
            // The bypass the old check allowed: it inspected only the ${…} segments, so
            // the literal text around them — the one place a key could sit — was never
            // read, while the Javadoc and the docs both claimed it was.
            var connection = staticConnection();
            connection.getStaticAuth().setValueTemplate("sk-live-abcdef${vault:unused}");

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("credential-shaped"), error.getMessage());
            assertTrue(error.getMessage().contains("'sk-l…'"), "the offending literal must be named, redacted: " + error.getMessage());
            assertFalse(error.getMessage().contains("sk-live-abcdef"), "the refusal must not echo the whole key: " + error.getMessage());
        }

        @Test
        @DisplayName("a literal key AFTER the reference is refused too")
        void refusesLiteralKeyAfterReference() {
            var connection = staticConnection();
            connection.getStaticAuth().setValueTemplate("${vault:jira-token}sk-live-abcdef");

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("credential-shaped"), error.getMessage());
        }

        @Test
        @DisplayName("a long literal segment is refused even without a key-shaped run")
        void refusesLongLiteralSegment() {
            var connection = staticConnection();
            // 34 characters, every word shorter than a key: too much text for a scheme.
            connection.getStaticAuth().setValueTemplate("Bearer token for the jira service ${vault:jira-token}");

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("characters of literal text"), error.getMessage());
            assertTrue(error.getMessage().contains("32"), "the message must state the bound: " + error.getMessage());
        }

        @ParameterizedTest
        @DisplayName("an interpolation that is not a well-formed reference is refused rather than read as literal text")
        @ValueSource(strings = {"Bearer ${vault:jira-token", "Bearer ${env:HOME} ${vault:jira-token}", "Bearer ${vault:} ${vault:jira-token}",
                "${vault:jira-token} ${connection:jira}"})
        void refusesMalformedInterpolation(String template) {
            // Each of these fell outside the old interpolation pattern and so counted as
            // literal text, which the old check never looked at.
            var connection = staticConnection();
            connection.getStaticAuth().setValueTemplate(template);

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("not a well-formed reference"), template + ": " + error.getMessage());
        }

        @Test
        @DisplayName("a reference whose key runs past 256 characters is refused as malformed, not swallowed as literal text")
        void refusesOverlongReferenceKey() {
            var connection = staticConnection();
            connection.getStaticAuth().setValueTemplate("Bearer ${vault:" + "k".repeat(257) + "}");

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("not a well-formed reference"), error.getMessage());
        }

        @Test
        @DisplayName("a credential-shaped extraAuthParam is refused")
        void refusesCredentialInExtraAuthParams() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setExtraAuthParams(Map.of("client_secret", "oops"));

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("credential-shaped"), error.getMessage());
        }

        @ParameterizedTest
        @DisplayName("a credential-shaped extraAuthParam is refused however it is punctuated")
        @ValueSource(strings = {"code_verifier", "Code-Verifier", "CODE.VERIFIER", "codeverifier", "api_key", "access_token",
                "refresh_token", "private_key", "secret_key", "api_token"})
        void refusesCredentialInExtraAuthParamsWhateverTheSpelling(String key) {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setExtraAuthParams(Map.of(key, "oops"));

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("credential-shaped"), error.getMessage());
        }

        @Test
        @DisplayName("a non-secret protocol parameter is fine")
        void acceptsProtocolParams() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setExtraAuthParams(Map.of("prompt", "consent", "audience", "api.atlassian.com", "access_type", "offline"));

            assertDoesNotThrow(connection::validate);
        }

        @ParameterizedTest
        @DisplayName("a parameter EDDI composes itself is refused whatever its case or punctuation")
        @ValueSource(strings = {"redirect_uri", "Redirect_uri", "REDIRECT-URI", "state", "State", "code_challenge", "Code-Challenge-Method",
                "client_id", "ClientId", "response_type"})
        void refusesReservedProtocolParams(String key) {
            // Before, only the exact lower-case credential names were caught, so
            // "Redirect_uri" — which a lenient provider reads as redirect_uri — could
            // point the authorization code somewhere else.
            var connection = oauthConnection(AuthType.OAUTH2_AUTHORIZATION_CODE);
            connection.getOauth().setExtraAuthParams(Map.of(key, "https://attacker.example/callback"));

            var error = assertThrows(IllegalArgumentException.class, connection::validate, key);

            assertTrue(error.getMessage().contains("composes itself"), key + ": " + error.getMessage());
        }

        @ParameterizedTest
        @DisplayName("a credential-shaped VALUE is refused even under an innocent key")
        @ValueSource(strings = {"sk-live-abcdef0123", "xoxb-1234-5678-abcd", "ghp_abcdefghijklmnop", "AKIAIOSFODNN7EXAMPLE",
                "eyJhbGciOiJIUzI1NiJ9.e30.abc", "Bearer abcdef", "basic dXNlcjpwYXNz"})
        void refusesCredentialShapedValues(String value) {
            // The docs said the map "is checked too"; only the keys were, so a value
            // pasted under "prompt" landed in plaintext in the document.
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setExtraAuthParams(Map.of("prompt", value));

            var error = assertThrows(IllegalArgumentException.class, connection::validate, value);

            assertTrue(error.getMessage().contains("looks like a credential"), value + ": " + error.getMessage());
            assertFalse(error.getMessage().contains(value), "the refusal must not echo the value: " + error.getMessage());
        }

        @Test
        @DisplayName("a value carrying a reference is refused — it would be resolved into the browser-visible URL")
        void refusesReferenceInValue() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setExtraAuthParams(Map.of("audience", "${vault:audience}"));

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("${…} reference"), error.getMessage());
        }

        @Test
        @DisplayName("a value over 512 characters is refused")
        void refusesOverlongValue() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setExtraAuthParams(Map.of("audience", "a".repeat(513)));

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("512"), error.getMessage());
        }

        @Test
        @DisplayName("a value of exactly 512 characters is still accepted")
        void acceptsValueAtTheBound() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setExtraAuthParams(Map.of("audience", "a".repeat(512)));

            assertDoesNotThrow(connection::validate);
        }

        @Test
        @DisplayName("a parameter with a null value is refused rather than sent as the word null")
        void refusesNullValue() {
            var connection = oauthConnection(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            var params = new HashMap<String, String>();
            params.put("prompt", null);
            connection.getOauth().setExtraAuthParams(params);

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("no value"), error.getMessage());
        }
    }

    @Nested
    @DisplayName("the token-endpoint timeout")
    class Timeout {

        @ParameterizedTest
        @DisplayName("a timeout outside 1..60000 ms is refused at save time rather than clamped at use")
        @ValueSource(ints = {0, -1, 60_001, Integer.MAX_VALUE})
        void refusesOutOfRangeTimeout(int timeoutMs) {
            // OAuthTokenClient clamps what it uses, so an out-of-range document worked —
            // at a timeout the author never wrote, with the only notice a log line at
            // refresh time.
            var connection = staticConnection();
            connection.setTimeoutMs(timeoutMs);

            var error = assertThrows(IllegalArgumentException.class, connection::validate, String.valueOf(timeoutMs));

            assertTrue(error.getMessage().contains("timeoutMs"), error.getMessage());
            assertTrue(error.getMessage().contains("60000"), "the message must state the bound: " + error.getMessage());
        }

        @ParameterizedTest
        @DisplayName("the bounds themselves are accepted, and so is leaving it unset")
        @ValueSource(ints = {1, 15_000, 60_000})
        void acceptsTimeoutInRange(int timeoutMs) {
            var connection = staticConnection();
            connection.setTimeoutMs(timeoutMs);

            assertDoesNotThrow(connection::validate);
        }

        @Test
        @DisplayName("an unset timeout means the resolver's default")
        void acceptsUnsetTimeout() {
            var connection = staticConnection();
            connection.setTimeoutMs(null);

            assertDoesNotThrow(connection::validate);
        }
    }

    @Nested
    @DisplayName("allowlists")
    class Allowlists {

        private final List<String> logRecords = new ArrayList<>();
        private Logger modelLogger;
        private Handler logHandler;
        private Level previousLevel;
        private boolean previousUseParentHandlers;

        @BeforeEach
        void captureLog() {
            logHandler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    logRecords.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
                }

                @Override
                public void flush() {
                    // nothing is buffered
                }

                @Override
                public void close() {
                    // nothing to release
                }
            };
            // src/test/resources/logging.properties silences the ai.labs.eddi namespace,
            // so the level has to be raised for the record to reach a handler at all.
            modelLogger = Logger.getLogger(ConnectionConfiguration.class.getName());
            previousLevel = modelLogger.getLevel();
            previousUseParentHandlers = modelLogger.getUseParentHandlers();
            modelLogger.setLevel(Level.ALL);
            modelLogger.setUseParentHandlers(false);
            modelLogger.addHandler(logHandler);
        }

        @AfterEach
        void releaseLog() {
            modelLogger.removeHandler(logHandler);
            modelLogger.setLevel(previousLevel);
            modelLogger.setUseParentHandlers(previousUseParentHandlers);
        }

        @Test
        @DisplayName("an empty baseUrlAllowlist is refused — a credential must name where it may go")
        void refusesEmptyAllowlist() {
            var connection = staticConnection();
            connection.setBaseUrlAllowlist(List.of());

            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        @DisplayName("a plaintext http origin on a remote host is accepted, with a WARN naming the connection and the origin")
        void warnsAboutPlaintextRemoteOrigin() {
            var connection = staticConnection();
            connection.setBaseUrlAllowlist(List.of("http://api.internal.example"));

            assertDoesNotThrow(connection::validate);

            assertTrue(logRecords.stream().anyMatch(record -> record.contains("plaintext http")), logRecords.toString());
            assertTrue(logRecords.stream().anyMatch(record -> record.contains("jira") && record.contains("http://api.internal.example")),
                    "the warning must name the connection and the origin, or nobody can find it: " + logRecords);
        }

        @ParameterizedTest
        @DisplayName("loopback over http and any https origin are accepted silently")
        @ValueSource(strings = {"http://localhost:7070", "http://127.0.0.1:8080", "https://api.internal.example"})
        void staysQuietForLoopbackAndHttps(String origin) {
            var connection = staticConnection();
            connection.setBaseUrlAllowlist(List.of(origin));

            assertDoesNotThrow(connection::validate);

            assertTrue(logRecords.isEmpty(), origin + " must not be reported: " + logRecords);
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
    @DisplayName("the CALLER_SUPPLIED binding")
    class CallerSuppliedRules {

        /**
         * The shape the Gnowbe connector uses: the platform authenticates the user and
         * passes their own key inward on every request, so EDDI stores nothing and the
         * connection carries only the header name and where that key may be sent.
         */
        private ConnectionConfiguration callerSuppliedConnection() {
            var connection = staticConnection();
            connection.setName("gnowbe");
            connection.setBinding(Binding.CALLER_SUPPLIED);
            connection.setBaseUrlAllowlist(List.of("https://api.gnowbe.com"));
            var auth = new StaticAuth();
            auth.setHeaderName("x-api-key");
            connection.setStaticAuth(auth);
            return connection;
        }

        @Test
        @DisplayName("a header name and an allowlist are the whole document")
        void acceptsHeaderNameOnly() {
            assertDoesNotThrow(() -> callerSuppliedConnection().validate());
        }

        @Test
        @DisplayName("headerName is still required — the connection owns it whoever supplies the value")
        void refusesMissingHeaderName() {
            var connection = callerSuppliedConnection();
            connection.getStaticAuth().setHeaderName(null);

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("headerName"), error.getMessage());
        }

        @Test
        @DisplayName("a stored valueTemplate is refused — it would race the caller's value silently")
        void refusesValueTemplate() {
            var connection = callerSuppliedConnection();
            connection.getStaticAuth().setValueTemplate("Bearer ${vault:gnowbe-key}");

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("valueTemplate"), error.getMessage());
            assertTrue(error.getMessage().contains("caller supplies"),
                    "the message must say which value wins and why: " + error.getMessage());
        }

        @Test
        @DisplayName("BASIC fields are refused rather than ignored")
        void refusesBasicFields() {
            var withUsername = callerSuppliedConnection();
            withUsername.getStaticAuth().setUsername("svc@example.com");
            assertTrue(assertThrows(IllegalArgumentException.class, withUsername::validate).getMessage().contains("username"));

            var withPassword = callerSuppliedConnection();
            withPassword.getStaticAuth().setPasswordRef("${vault:pw}");
            assertTrue(assertThrows(IllegalArgumentException.class, withPassword::validate).getMessage().contains("passwordRef"));
        }

        @Test
        @DisplayName("every authType but STATIC is refused — there is nothing to exchange or refresh")
        void refusesNonStaticAuthTypes() {
            for (AuthType authType : List.of(AuthType.BASIC, AuthType.OAUTH2_CLIENT_CREDENTIALS, AuthType.OAUTH2_AUTHORIZATION_CODE)) {
                var connection = callerSuppliedConnection();
                connection.setAuthType(authType);

                var error = assertThrows(IllegalArgumentException.class, connection::validate, "authType " + authType);

                assertTrue(error.getMessage().contains("STATIC"), authType + ": " + error.getMessage());
            }
        }

        @Test
        @DisplayName("allowUnverifiedPrincipal stays PER_USER-only — no grant is read here to relax")
        void refusesUnverifiedPrincipalFlag() {
            var connection = callerSuppliedConnection();
            connection.setAllowUnverifiedPrincipal(true);

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("PER_USER"), error.getMessage());
        }

        @Test
        @DisplayName("the allowlist is still required — here it is the only thing bounding the user's own credential")
        void refusesEmptyAllowlist() {
            var connection = callerSuppliedConnection();
            connection.setBaseUrlAllowlist(List.of());

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("baseUrlAllowlist"), error.getMessage());
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
        @DisplayName("SERVICE on the authorization-code flow is refused — and that was the DEFAULT")
        void refusesServiceOnAuthorizationCode() {
            // binding defaults to SERVICE, so an author who wrote an authorization-code
            // block and never thought about binding got a connection that saved,
            // deployed, and showed users a working consent screen — then resolved every
            // call against the __service__ principal, which no authorization-code flow
            // can ever produce a grant for. The symptom was "not connected" for a user
            // who had just connected.
            var connection = oauthConnection(AuthType.OAUTH2_AUTHORIZATION_CODE);
            connection.setBinding(Binding.SERVICE);

            var error = assertThrows(IllegalArgumentException.class, connection::validate);

            assertTrue(error.getMessage().contains("PER_USER"), error.getMessage());
            assertTrue(error.getMessage().contains("OAUTH2_CLIENT_CREDENTIALS"),
                    "the message must name the alternative, or the author has nowhere to go: " + error.getMessage());
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

    @Nested
    @DisplayName("the name grammar")
    class NameGrammar {

        @ParameterizedTest
        @DisplayName("a name the reference pattern or the credential header could not carry is refused, with the rule in the message")
        @ValueSource(strings = {"my jira", " jira", "jira ", "acme/jira", "jira}", "tenant:jira", "-jira", ".jira", "jïra", "jira\t",
                "jira{1}"})
        void refusesUnreferenceableNames(String name) {
            // Each of these saved before: the only check was non-blank. "acme/jira"
            // is the worst of them — ${connection:acme/jira} parses as tenant "acme",
            // so the reference resolved against a tenant that does not hold it.
            var connection = staticConnection();
            connection.setName(name);

            var error = assertThrows(IllegalArgumentException.class, connection::validate, name);

            assertTrue(error.getMessage().contains(ConnectionConfiguration.NAME_GRAMMAR),
                    "the refusal must show the rule, or the author has to guess: " + error.getMessage());
        }

        @Test
        @DisplayName("a name longer than 64 characters is refused")
        void refusesOverlongName() {
            var connection = staticConnection();
            connection.setName("a".repeat(65));

            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        @DisplayName("surrounding whitespace is refused rather than trimmed away silently")
        void doesNotTrimSilently() {
            var connection = staticConnection();
            connection.setName(" jira ");

            assertThrows(IllegalArgumentException.class, connection::validate);
            assertEquals(" jira ", connection.getName(), "validation must not rewrite what the author sent");
        }

        @ParameterizedTest
        @DisplayName("every shape a reference and the header can carry is accepted")
        @ValueSource(strings = {"jira", "google-drive", "svc_01", "a.b", "J1", "7up", "Jira.Cloud_v2-prod"})
        void acceptsReferenceableNames(String name) {
            var connection = staticConnection();
            connection.setName(name);

            assertDoesNotThrow(connection::validate, name);
        }

        @Test
        @DisplayName("64 characters is the longest accepted name")
        void acceptsSixtyFourCharacters() {
            var connection = staticConnection();
            connection.setName("a".repeat(64));

            assertDoesNotThrow(connection::validate);
        }
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
