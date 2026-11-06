/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.connections.ConnectionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds and signs the two JWTs an OAuth client may present (RFC 7523).
 * <ul>
 * <li>{@link #jwtBearerGrant} — the assertion IS the grant (§2.1): a service
 * account asking for a token in its own name. Google service accounts.</li>
 * <li>{@link #clientAssertion} — the assertion authenticates the client (§2.2,
 * OpenID Connect {@code private_key_jwt}) on an ordinary
 * {@code client_credentials}, {@code authorization_code} or
 * {@code refresh_token} request, in place of a client secret. Microsoft Entra
 * apps with a certificate credential.</li>
 * </ul>
 * RS256 with the JDK's own {@link Signature}; no JOSE library. The format is
 * three base64url segments and the only primitive is PKCS#1 v1.5 over SHA-256,
 * which the JDK has had forever — a dependency would add more surface than it
 * removes.
 */
public final class JwtAssertions {

    /**
     * How long an assertion is valid. Short on purpose: it is presented once,
     * immediately, and an intercepted one is replayable until it expires. RFC 7523
     * sets no figure; Google caps it at one hour and Entra accepts this.
     */
    static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(5);

    /** RFC 7523 §2.1. */
    public static final String JWT_BEARER_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    /** RFC 7523 §2.2. */
    public static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private JwtAssertions() {
    }

    /**
     * The assertion of a {@link AuthType#OAUTH2_JWT_BEARER} grant:
     * {@code iss = sub =} the service account, {@code aud =} {@code oauth.audience}
     * or the token URL, {@code scope} space-joined.
     */
    public static String jwtBearerGrant(ConnectionConfiguration connection, SigningKey key, Instant now) {
        OAuthConfig oauth = connection.getOauth();
        String issuer = issuer(connection, key);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        // sub = iss: the service account acts as itself. Acting as a Workspace user
        // (domain-wide delegation) puts that user here, and is a separate decision.
        claims.put("sub", issuer);
        claims.put("aud", isBlank(oauth.getAudience()) ? oauth.getTokenUrl() : oauth.getAudience());
        if (oauth.getScopes() != null && !oauth.getScopes().isEmpty()) {
            claims.put("scope", String.join(" ", oauth.getScopes()));
        }
        timeClaims(claims, now);
        return sign(header(oauth, key, false, connection.getName()), claims, key, connection.getName());
    }

    /**
     * The {@code client_assertion} of a {@code private_key_jwt} client:
     * {@code iss = sub = client_id}, {@code aud =} the token URL, a fresh
     * {@code jti} each time.
     */
    public static String clientAssertion(ConnectionConfiguration connection, SigningKey key, Instant now) {
        OAuthConfig oauth = connection.getOauth();
        if (isBlank(oauth.getClientId())) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "Connection '" + connection.getName() + "' authenticates with private_key_jwt but has no oauth.clientId. Nothing was sent.");
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", oauth.getClientId());
        claims.put("sub", oauth.getClientId());
        claims.put("aud", oauth.getTokenUrl());
        timeClaims(claims, now);
        return sign(header(oauth, key, true, connection.getName()), claims, key, connection.getName());
    }

    /**
     * {@code iat}, {@code nbf}, {@code exp} and a unique {@code jti}. Entra
     * requires {@code nbf} and {@code jti}; Google ignores both. A unique
     * {@code jti} is what lets a provider refuse a replayed assertion.
     */
    private static void timeClaims(Map<String, Object> claims, Instant now) {
        long issuedAt = now.getEpochSecond();
        claims.put("iat", issuedAt);
        claims.put("nbf", issuedAt);
        claims.put("exp", issuedAt + ASSERTION_LIFETIME.toSeconds());
        claims.put("jti", UUID.randomUUID().toString());
    }

    /**
     * The service account the assertion speaks for: {@code oauth.clientId}, or the
     * key file's {@code client_email}. When both are present they must agree — a
     * mismatch means the vault holds another account's key, and the provider's
     * answer to that ({@code invalid_grant}) would name nothing.
     */
    static String issuer(ConnectionConfiguration connection, SigningKey key) {
        String configured = connection.getOauth().getClientId();
        String fromKey = key.clientEmail();
        if (!isBlank(configured) && !isBlank(fromKey) && !configured.equals(fromKey)) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "Connection '" + connection.getName()
                    + "' names oauth.clientId '" + configured + "', but the key file in the vault belongs to '" + fromKey
                    + "'. Correct one of them. Nothing was sent.");
        }
        String issuer = isBlank(configured) ? fromKey : configured;
        if (isBlank(issuer)) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "Connection '" + connection.getName()
                    + "' has no oauth.clientId, and its private key is a bare PEM rather than a key file naming its client_email. Set "
                    + "oauth.clientId to the service account's email. Nothing was sent.");
        }
        return issuer;
    }

    private static Map<String, Object> header(OAuthConfig oauth, SigningKey key, boolean withThumbprint, String connectionName) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        String kid = isBlank(oauth.getPrivateKeyId()) ? key.keyId() : oauth.getPrivateKeyId();
        if (!isBlank(kid)) {
            header.put("kid", kid);
        }
        if (withThumbprint && !isBlank(oauth.getCertificateThumbprint())) {
            String headerName = isBlank(oauth.getCertificateThumbprintHeader())
                    ? OAuthConfig.THUMBPRINT_X5T_S256
                    : oauth.getCertificateThumbprintHeader();
            try {
                header.put(headerName, ConnectionConfiguration.canonicalThumbprint(oauth.getCertificateThumbprint(), headerName));
            } catch (IllegalArgumentException e) {
                throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                        "Connection '" + connectionName + "': " + e.getMessage() + " Nothing was sent.");
            }
        }
        return header;
    }

    private static String sign(Map<String, Object> header, Map<String, Object> claims, SigningKey key, String connectionName) {
        try {
            String signingInput = segment(header) + "." + segment(claims);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + BASE64URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException | JsonProcessingException e) {
            // The exception names an algorithm or a key class, never key material.
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "Could not sign the assertion for connection '"
                    + connectionName + "' (" + e.getClass().getSimpleName() + "). Nothing was sent.");
        }
    }

    private static String segment(Map<String, Object> json) throws JsonProcessingException {
        return BASE64URL.encodeToString(MAPPER.writeValueAsBytes(json));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
