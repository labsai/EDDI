/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

import ai.labs.eddi.configs.connections.rest.ConnectionReadRedactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save-time rules for the two signed-JWT shapes. The theme is the same as for
 * every other connection field: the key is a reference and never a literal, and
 * a field that the chosen flow would silently ignore is refused, because it
 * reads as load-bearing to whoever looks at the document next.
 */
class ConnectionConfigurationSignedJwtValidationTest {

    private static final String SHA256_HEX = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89";
    private static final String SHA1_HEX = "0123456789ABCDEF0123456789ABCDEF01234567";

    static ConnectionConfiguration jwtBearer() {
        var connection = new ConnectionConfiguration();
        connection.setName("google-sa");
        connection.setAuthType(AuthType.OAUTH2_JWT_BEARER);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://www.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://oauth2.googleapis.com/token");
        oauth.setClientId("eddi@project.iam.gserviceaccount.com");
        oauth.setPrivateKey("${vault:google-sa-key}");
        oauth.setScopes(List.of("https://www.googleapis.com/auth/drive.readonly"));
        connection.setOauth(oauth);
        return connection;
    }

    static ConnectionConfiguration privateKeyJwt(AuthType authType) {
        var connection = new ConnectionConfiguration();
        connection.setName("entra");
        connection.setAuthType(authType);
        connection.setBinding(authType == AuthType.OAUTH2_AUTHORIZATION_CODE ? Binding.PER_USER : Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://graph.microsoft.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://login.microsoftonline.com/tid/oauth2/v2.0/token");
        oauth.setAuthorizationUrl("https://login.microsoftonline.com/tid/oauth2/v2.0/authorize");
        oauth.setClientId("app-id");
        oauth.setClientAuthMethod(OAuthConfig.CLIENT_AUTH_PRIVATE_KEY_JWT);
        oauth.setPrivateKey("${vault:entra-key}");
        oauth.setCertificateThumbprint(SHA256_HEX);
        connection.setOauth(oauth);
        return connection;
    }

    private static String refusal(ConnectionConfiguration connection) {
        return assertThrows(IllegalArgumentException.class, connection::validate).getMessage();
    }

    @Nested
    @DisplayName("OAUTH2_JWT_BEARER")
    class JwtBearer {

        @Test
        @DisplayName("a well-formed service-account connection validates, and clientId is optional (a JSON key file names it)")
        void accepts() {
            assertDoesNotThrow(() -> jwtBearer().validate());
            var noClientId = jwtBearer();
            noClientId.getOauth().setClientId(null);
            assertDoesNotThrow(noClientId::validate);
        }

        @Test
        @DisplayName("binding must be SERVICE")
        void serviceOnly() {
            var perUser = jwtBearer();
            perUser.setBinding(Binding.PER_USER);
            assertTrue(refusal(perUser).contains("OAUTH2_JWT_BEARER requires binding SERVICE"));
            var callerSupplied = jwtBearer();
            callerSupplied.setBinding(Binding.CALLER_SUPPLIED);
            assertTrue(refusal(callerSupplied).contains("SERVICE"));
        }

        @Test
        @DisplayName("privateKey is required and must be a reference, never a pasted PEM")
        void privateKeyIsAReference() {
            var missing = jwtBearer();
            missing.getOauth().setPrivateKey(null);
            assertTrue(refusal(missing).contains("oauth.privateKey is required"));
            var literal = jwtBearer();
            literal.getOauth().setPrivateKey("-----BEGIN PRIVATE KEY-----MIIEvQ...-----END PRIVATE KEY-----");
            String message = refusal(literal);
            assertTrue(message.contains("oauth.privateKey must be a ${vault:…} reference"));
            assertFalse(message.contains("MIIEvQ"), "the refusal must not echo the key");
            var embedded = jwtBearer();
            embedded.getOauth().setPrivateKey("x${vault:google-sa-key}");
            assertTrue(refusal(embedded).contains("oauth.privateKey"));
        }

        @Test
        @DisplayName("fields of the other flows are refused rather than ignored")
        void foreignFieldsRefused() {
            var secret = jwtBearer();
            secret.getOauth().setClientSecret("${vault:x}");
            assertTrue(refusal(secret).contains("oauth.clientSecret must not be set"));
            var authUrl = jwtBearer();
            authUrl.getOauth().setAuthorizationUrl("https://accounts.google.com/o/oauth2/v2/auth");
            assertTrue(refusal(authUrl).contains("oauth.authorizationUrl must not be set"));
            var thumbprint = jwtBearer();
            thumbprint.getOauth().setCertificateThumbprint(SHA256_HEX);
            assertTrue(refusal(thumbprint).contains("oauth.certificateThumbprint must not be set"));
            var params = jwtBearer();
            params.getOauth().setExtraAuthParams(Map.of("prompt", "consent"));
            assertTrue(refusal(params).contains("oauth.extraAuthParams must be empty"));
            var clientAuth = jwtBearer();
            clientAuth.getOauth().setClientAuthMethod(OAuthConfig.CLIENT_AUTH_PRIVATE_KEY_JWT);
            assertTrue(refusal(clientAuth).contains("oauth.clientAuthMethod does not apply"));
        }

        @Test
        @DisplayName("the token URL is held to the same https rule as every credential endpoint")
        void tokenUrlHttps() {
            var plaintext = jwtBearer();
            plaintext.getOauth().setTokenUrl("http://oauth2.googleapis.com/token");
            assertTrue(refusal(plaintext).contains("oauth.tokenUrl must use https"));
            var missing = jwtBearer();
            missing.getOauth().setTokenUrl(null);
            assertTrue(refusal(missing).contains("OAUTH2_JWT_BEARER"), "the required-field message names the new type");
        }

        @Test
        @DisplayName("audience, clientId and privateKeyId are literal identifiers: no reference, bounded")
        void identifiers() {
            var audienceRef = jwtBearer();
            audienceRef.getOauth().setAudience("${vault:aud}");
            assertTrue(refusal(audienceRef).contains("oauth.audience"));
            var kidRef = jwtBearer();
            kidRef.getOauth().setPrivateKeyId("${vault:kid}");
            assertTrue(refusal(kidRef).contains("oauth.privateKeyId"));
            var longClientId = jwtBearer();
            longClientId.getOauth().setClientId("a".repeat(ConnectionConfiguration.MAX_IDENTIFIER_CHARS + 1));
            assertTrue(refusal(longClientId).contains("oauth.clientId"));
            var ok = jwtBearer();
            ok.getOauth().setAudience("https://oauth2.googleapis.com/token");
            ok.getOauth().setPrivateKeyId("0123abcd");
            assertDoesNotThrow(ok::validate);
        }
    }

    @Nested
    @DisplayName("clientAuthMethod private_key_jwt")
    class PrivateKeyJwt {

        @Test
        @DisplayName("validates on client_credentials and on authorization_code")
        void accepts() {
            assertDoesNotThrow(() -> privateKeyJwt(AuthType.OAUTH2_CLIENT_CREDENTIALS).validate());
            assertDoesNotThrow(() -> privateKeyJwt(AuthType.OAUTH2_AUTHORIZATION_CODE).validate());
        }

        @Test
        @DisplayName("needs privateKey as a reference and refuses a clientSecret beside it")
        void keyNotSecret() {
            var noKey = privateKeyJwt(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            noKey.getOauth().setPrivateKey(null);
            assertTrue(refusal(noKey).contains("oauth.privateKey is required"));
            var both = privateKeyJwt(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            both.getOauth().setClientSecret("${vault:secret}");
            assertTrue(refusal(both).contains("oauth.clientSecret must not be set"));
        }

        @Test
        @DisplayName("the default stays client_secret_basic, which still needs a clientSecret and refuses a stray privateKey")
        void defaultIsBackwardCompatible() {
            var connection = privateKeyJwt(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            assertEquals(OAuthConfig.CLIENT_AUTH_BASIC, new OAuthConfig().getClientAuthMethod());
            connection.getOauth().setClientAuthMethod(OAuthConfig.CLIENT_AUTH_BASIC);
            assertTrue(refusal(connection).contains("oauth.clientSecret is required"));
            connection.getOauth().setClientSecret("${vault:secret}");
            assertTrue(refusal(connection).contains("oauth.privateKey must not be set"));
            connection.getOauth().setPrivateKey(null);
            assertTrue(refusal(connection).contains("oauth.certificateThumbprint must not be set"));
            connection.getOauth().setCertificateThumbprint(null);
            assertDoesNotThrow(connection::validate);
        }

        @Test
        @DisplayName("an unknown clientAuthMethod names all three accepted values")
        void unknownMethod() {
            var connection = privateKeyJwt(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.getOauth().setClientAuthMethod("tls_client_auth");
            assertTrue(refusal(connection).contains("private_key_jwt"));
        }

        @Test
        @DisplayName("the thumbprint must match its header: SHA-256 for x5t#S256, SHA-1 for x5t")
        void thumbprintLength() {
            var sha1InS256 = privateKeyJwt(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            sha1InS256.getOauth().setCertificateThumbprint(SHA1_HEX);
            String message = refusal(sha1InS256);
            assertTrue(message.contains("SHA-1 thumbprint"), message);
            assertTrue(message.contains("x5t"), message);

            var sha1InX5t = privateKeyJwt(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            sha1InX5t.getOauth().setCertificateThumbprint(SHA1_HEX);
            sha1InX5t.getOauth().setCertificateThumbprintHeader(OAuthConfig.THUMBPRINT_X5T);
            assertDoesNotThrow(sha1InX5t::validate);

            var badHeader = privateKeyJwt(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            badHeader.getOauth().setCertificateThumbprintHeader("x5c");
            assertTrue(refusal(badHeader).contains("certificateThumbprintHeader"));

            var garbage = privateKeyJwt(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            garbage.getOauth().setCertificateThumbprint("not a thumbprint!");
            assertTrue(refusal(garbage).contains("oauth.certificateThumbprint"));
        }

        @Test
        @DisplayName("hex and base64url spell the same thumbprint")
        void thumbprintForms() {
            String fromHex = ConnectionConfiguration.canonicalThumbprint(SHA256_HEX, OAuthConfig.THUMBPRINT_X5T_S256);
            String fromLowerHexNoColons = ConnectionConfiguration.canonicalThumbprint(SHA256_HEX.replace(":", "").toLowerCase(),
                    OAuthConfig.THUMBPRINT_X5T_S256);
            String fromBase64Url = ConnectionConfiguration.canonicalThumbprint(fromHex, OAuthConfig.THUMBPRINT_X5T_S256);
            String fromPaddedBase64 = ConnectionConfiguration.canonicalThumbprint(
                    Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(fromHex)), OAuthConfig.THUMBPRINT_X5T_S256);
            assertEquals(fromHex, fromLowerHexNoColons);
            assertEquals(fromHex, fromBase64Url);
            assertEquals(fromHex, fromPaddedBase64);
            assertFalse(fromHex.contains("="));
        }
    }

    @Nested
    @DisplayName("Read redaction")
    class Redaction {

        @Test
        @DisplayName("a legacy literal privateKey is redacted for a non-admin reader; a reference is kept")
        void privateKeyRedacted() {
            var legacy = jwtBearer();
            legacy.getOauth().setPrivateKey("-----BEGIN PRIVATE KEY-----MIIEvQ-----END PRIVATE KEY-----");
            ConnectionConfiguration redacted = ConnectionReadRedactor.redactLegacyLiterals(legacy);
            assertFalse(redacted.getOauth().getPrivateKey().contains("MIIEvQ"));
            assertTrue(redacted.getOauth().getPrivateKey().startsWith("<redacted"));

            ConnectionConfiguration kept = ConnectionReadRedactor.redactLegacyLiterals(jwtBearer());
            assertEquals("${vault:google-sa-key}", kept.getOauth().getPrivateKey());
            assertEquals("eddi@project.iam.gserviceaccount.com", kept.getOauth().getClientId());
        }

        @Test
        @DisplayName("the new fields survive the redactor's JSON round trip")
        void roundTrip() {
            ConnectionConfiguration copy = ConnectionReadRedactor.redactLegacyLiterals(privateKeyJwt(AuthType.OAUTH2_CLIENT_CREDENTIALS));
            assertEquals(OAuthConfig.CLIENT_AUTH_PRIVATE_KEY_JWT, copy.getOauth().getClientAuthMethod());
            assertEquals(SHA256_HEX, copy.getOauth().getCertificateThumbprint());
            assertEquals(OAuthConfig.THUMBPRINT_X5T_S256, copy.getOauth().getCertificateThumbprintHeader());
        }
    }
}
