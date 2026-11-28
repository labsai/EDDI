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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two RFC 7523 assertions, verified the way a provider verifies them: with
 * the public half of the key, against the exact bytes that were signed.
 */
class JwtAssertionsTest {

    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String SERVICE_ACCOUNT = "eddi-sa@project-1.iam.gserviceaccount.com";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static KeyPair keyPair;
    static String pem;

    @BeforeAll
    static void generateKey() throws Exception {
        keyPair = generate(2048);
        pem = pkcs8Pem(keyPair);
    }

    static KeyPair generate(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    /**
     * A PEM armour line such as {@code BEGIN PRIVATE KEY}, dashes added at run
     * time. Written out whole, a header and footer in one source hunk is what a
     * secret scanner reports as a leaked private key.
     */
    static String armour(String label) {
        return "-----" + label + "-----";
    }

    static String pkcs8Pem(KeyPair pair) {
        return armour("BEGIN PRIVATE KEY") + "\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded()) + "\n" + armour("END PRIVATE KEY") + "\n";
    }

    /** A Google service-account key file, shaped as the console downloads it. */
    static String jsonKeyFile(String pemKey, String clientEmail, String keyId) throws Exception {
        var node = MAPPER.createObjectNode();
        node.put("type", "service_account");
        node.put("project_id", "project-1");
        node.put("private_key_id", keyId);
        node.put("private_key", pemKey);
        node.put("client_email", clientEmail);
        node.put("token_uri", "https://attacker.example.com/token");
        return MAPPER.writeValueAsString(node);
    }

    static ConnectionConfiguration jwtBearerConnection() {
        var connection = new ConnectionConfiguration();
        connection.setName("google-sa");
        connection.setAuthType(AuthType.OAUTH2_JWT_BEARER);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://www.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl(TOKEN_URL);
        oauth.setClientId(SERVICE_ACCOUNT);
        oauth.setPrivateKey("${vault:google-sa-key}");
        oauth.setScopes(List.of("https://www.googleapis.com/auth/drive.readonly", "https://www.googleapis.com/auth/calendar"));
        connection.setOauth(oauth);
        return connection;
    }

    static ConnectionConfiguration privateKeyJwtConnection() {
        var connection = new ConnectionConfiguration();
        connection.setName("entra-app");
        connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://graph.microsoft.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://login.microsoftonline.com/tenant-id/oauth2/v2.0/token");
        oauth.setClientId("11111111-2222-3333-4444-555555555555");
        oauth.setClientAuthMethod(OAuthConfig.CLIENT_AUTH_PRIVATE_KEY_JWT);
        oauth.setPrivateKey("${vault:entra-key}");
        oauth.setScopes(List.of("https://graph.microsoft.com/.default"));
        connection.setOauth(oauth);
        return connection;
    }

    /** Verifies the signature with the public key and returns header and claims. */
    static JsonNode[] verify(String jwt, KeyPair pair) throws Exception {
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "a JWS compact serialization has three segments");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])), "the signature must verify with the public key");
        assertFalse(jwt.contains("="), "base64url without padding");
        return new JsonNode[]{MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0])), MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]))};
    }

    @Nested
    @DisplayName("JWT bearer grant (RFC 7523 §2.1)")
    class JwtBearerGrant {

        @Test
        @DisplayName("is RS256, signed by the key, with iss = sub = the service account, aud = token URL and space-joined scopes")
        void structureAndSignature() throws Exception {
            Instant now = Instant.now();
            String jwt = JwtAssertions.jwtBearerGrant(jwtBearerConnection(), SigningKey.parse(pem, "google-sa"), now);
            JsonNode[] parts = verify(jwt, keyPair);
            assertEquals("RS256", parts[0].get("alg").asText());
            assertEquals("JWT", parts[0].get("typ").asText());
            assertNull(parts[0].get("kid"), "no kid when neither the connection nor a key file names one");
            assertNull(parts[0].get("x5t#S256"), "a grant assertion carries no certificate thumbprint");

            JsonNode claims = parts[1];
            assertEquals(SERVICE_ACCOUNT, claims.get("iss").asText());
            assertEquals(SERVICE_ACCOUNT, claims.get("sub").asText());
            assertEquals(TOKEN_URL, claims.get("aud").asText());
            assertEquals("https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/calendar", claims.get("scope").asText());
            assertNotNull(claims.get("jti"));
        }

        @Test
        @DisplayName("iat is now and exp is at most an hour later (Google refuses more)")
        void timeBounds() throws Exception {
            Instant now = Instant.parse("2026-11-05T10:00:00Z");
            JsonNode claims = verify(JwtAssertions.jwtBearerGrant(jwtBearerConnection(), SigningKey.parse(pem, "x"), now), keyPair)[1];
            long iat = claims.get("iat").asLong();
            long exp = claims.get("exp").asLong();
            assertEquals(now.getEpochSecond(), iat);
            assertTrue(exp > iat, "exp after iat");
            assertTrue(exp - iat <= 3600, "an assertion valid for more than an hour is refused by Google");
            assertEquals(JwtAssertions.ASSERTION_LIFETIME.toSeconds(), exp - iat);
        }

        @Test
        @DisplayName("oauth.audience overrides the token URL, and oauth.privateKeyId becomes the kid header")
        void audienceAndKid() throws Exception {
            var connection = jwtBearerConnection();
            connection.getOauth().setAudience("https://oauth2.googleapis.com/token?custom");
            connection.getOauth().setPrivateKeyId("key-123");
            JsonNode[] parts = verify(JwtAssertions.jwtBearerGrant(connection, SigningKey.parse(pem, "x"), Instant.now()), keyPair);
            assertEquals("key-123", parts[0].get("kid").asText());
            assertEquals("https://oauth2.googleapis.com/token?custom", parts[1].get("aud").asText());
        }

        @Test
        @DisplayName("no scope claim when the connection names no scopes")
        void noScopes() throws Exception {
            var connection = jwtBearerConnection();
            connection.getOauth().setScopes(null);
            assertNull(verify(JwtAssertions.jwtBearerGrant(connection, SigningKey.parse(pem, "x"), Instant.now()), keyPair)[1].get("scope"));
        }
    }

    @Nested
    @DisplayName("Google service-account JSON key file as one vault entry")
    class JsonKeyFile {

        @Test
        @DisplayName("private_key signs, client_email is the issuer and private_key_id the kid when the connection names neither")
        void keyFileSuppliesIdentity() throws Exception {
            var connection = jwtBearerConnection();
            connection.getOauth().setClientId(null);
            SigningKey key = SigningKey.parse(jsonKeyFile(pem, SERVICE_ACCOUNT, "abc123"), "google-sa");
            JsonNode[] parts = verify(JwtAssertions.jwtBearerGrant(connection, key, Instant.now()), keyPair);
            assertEquals("abc123", parts[0].get("kid").asText());
            assertEquals(SERVICE_ACCOUNT, parts[1].get("iss").asText());
            assertEquals(SERVICE_ACCOUNT, parts[1].get("sub").asText());
            assertEquals(TOKEN_URL, parts[1].get("aud").asText(), "the key file's token_uri is never where the assertion is addressed");
        }

        @Test
        @DisplayName("a configured clientId that disagrees with the key file's client_email is refused before anything is signed")
        void mismatchIsRefused() throws Exception {
            SigningKey key = SigningKey.parse(jsonKeyFile(pem, "someone-else@project-2.iam.gserviceaccount.com", "k"), "google-sa");
            var e = assertThrows(ConnectionException.class, () -> JwtAssertions.jwtBearerGrant(jwtBearerConnection(), key, Instant.now()));
            assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, e.getReason());
            assertTrue(e.getMessage().contains("someone-else@project-2.iam.gserviceaccount.com"));
        }

        @Test
        @DisplayName("a bare PEM and no clientId leaves no issuer, and says so")
        void noIssuer() {
            var connection = jwtBearerConnection();
            connection.getOauth().setClientId(null);
            var e = assertThrows(ConnectionException.class,
                    () -> JwtAssertions.jwtBearerGrant(connection, SigningKey.parse(pem, "google-sa"), Instant.now()));
            assertTrue(e.getMessage().contains("oauth.clientId"));
        }

        @Test
        @DisplayName("a key file of another type is refused by name")
        void wrongType() {
            var e = assertThrows(ConnectionException.class,
                    () -> SigningKey.parse("{\"type\":\"authorized_user\",\"refresh_token\":\"1//secret-refresh\"}", "google-sa"));
            assertTrue(e.getMessage().contains("authorized_user"));
            assertFalse(e.getMessage().contains("1//secret-refresh"));
        }
    }

    @Nested
    @DisplayName("Key material never leaks")
    class Redaction {

        @Test
        @DisplayName("toString shows no key material")
        void toStringIsRedacted() throws Exception {
            SigningKey key = SigningKey.parse(jsonKeyFile(pem, SERVICE_ACCOUNT, "kid-1"), "google-sa");
            String rendered = key.toString();
            assertTrue(rendered.contains("<REDACTED>"));
            assertFalse(rendered.contains("MII"), "no base64 of the DER key");
            assertTrue(rendered.contains("kid-1"), "the key id is not a secret and helps diagnosis");
        }

        @Test
        @DisplayName("a malformed JSON key file is refused without quoting it, and without a parser cause that would")
        void malformedJsonDoesNotEcho() {
            String broken = "{\"private_key\": \"" + pem.replace("\n", "\\n") + "\", oops";
            var e = assertThrows(ConnectionException.class, () -> SigningKey.parse(broken, "google-sa"));
            assertFalse(e.getMessage().contains("MII"));
            assertNull(e.getCause(), "a Jackson exception quotes its source, and the source is the key");
        }

        @Test
        @DisplayName("garbage, PKCS#1 and encrypted keys are refused with advice and without echo")
        void unsupportedShapes() {
            String body = pem.substring(pem.indexOf('\n') + 1, pem.indexOf("-----END"));
            for (String material : List.of("not a key at all MIIsecret",
                    armour("BEGIN RSA PRIVATE KEY") + "\n" + body + armour("END RSA PRIVATE KEY"),
                    armour("BEGIN ENCRYPTED PRIVATE KEY") + "\n" + body + armour("END ENCRYPTED PRIVATE KEY"),
                    armour("BEGIN PRIVATE KEY") + "\n!!!notbase64!!!\n" + armour("END PRIVATE KEY"), "   ")) {
                var e = assertThrows(ConnectionException.class, () -> SigningKey.parse(material, "c"));
                assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, e.getReason());
                assertFalse(e.getMessage().contains("MII"), "no key material in: " + e.getMessage());
                assertNull(e.getCause());
            }
        }

        @Test
        @DisplayName("an RSA key below 2048 bits is refused")
        void smallKey() throws Exception {
            String small = pkcs8Pem(generate(1024));
            var e = assertThrows(ConnectionException.class, () -> SigningKey.parse(small, "c"));
            assertTrue(e.getMessage().contains("1024"));
        }

        @Test
        @DisplayName("a non-RSA key is refused: only RS256 is supported")
        void ecKey() throws Exception {
            KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
            ec.initialize(256);
            String ecPem = pkcs8Pem(ec.generateKeyPair());
            var e = assertThrows(ConnectionException.class, () -> SigningKey.parse(ecPem, "c"));
            assertTrue(e.getMessage().contains("RS256"));
        }
    }

    @Nested
    @DisplayName("private_key_jwt client assertion (RFC 7523 §2.2)")
    class ClientAssertion {

        @Test
        @DisplayName("iss = sub = client_id, aud = token URL, a unique jti, nbf and a short exp")
        void structure() throws Exception {
            var connection = privateKeyJwtConnection();
            Set<String> jtis = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                JsonNode[] parts = verify(JwtAssertions.clientAssertion(connection, SigningKey.parse(pem, "x"), Instant.now()), keyPair);
                JsonNode claims = parts[1];
                assertEquals(connection.getOauth().getClientId(), claims.get("iss").asText());
                assertEquals(connection.getOauth().getClientId(), claims.get("sub").asText());
                assertEquals(connection.getOauth().getTokenUrl(), claims.get("aud").asText());
                assertNotNull(claims.get("nbf"));
                assertTrue(claims.get("exp").asLong() - claims.get("iat").asLong() <= 600);
                assertNull(claims.get("scope"), "scope travels in the form, not in a client assertion");
                jtis.add(claims.get("jti").asText());
            }
            assertEquals(3, jtis.size(), "every assertion carries a fresh jti, so a provider can refuse a replay");
        }

        @Test
        @DisplayName("x5t#S256 carries the SHA-256 thumbprint as base64url, from hex input with colons")
        void sha256Thumbprint() throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest("certificate-der".getBytes(StandardCharsets.UTF_8));
            String hexWithColons = String.join(":", HexFormat.of().withUpperCase().formatHex(digest).split("(?<=\\G..)"));
            var connection = privateKeyJwtConnection();
            connection.getOauth().setCertificateThumbprint(hexWithColons);
            JsonNode header = verify(JwtAssertions.clientAssertion(connection, SigningKey.parse(pem, "x"), Instant.now()), keyPair)[0];
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest), header.get("x5t#S256").asText());
            assertNull(header.get("x5t"));
        }

        @Test
        @DisplayName("x5t carries a SHA-1 thumbprint when the connection asks for that header")
        void sha1Thumbprint() throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest("certificate-der".getBytes(StandardCharsets.UTF_8));
            var connection = privateKeyJwtConnection();
            connection.getOauth().setCertificateThumbprintHeader(OAuthConfig.THUMBPRINT_X5T);
            connection.getOauth().setCertificateThumbprint(Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
            JsonNode header = verify(JwtAssertions.clientAssertion(connection, SigningKey.parse(pem, "x"), Instant.now()), keyPair)[0];
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest), header.get("x5t").asText());
            assertNull(header.get("x5t#S256"));
        }
    }
}
