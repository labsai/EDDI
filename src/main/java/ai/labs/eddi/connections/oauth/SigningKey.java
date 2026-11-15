/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.connections.ConnectionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * A private key read from the vault, ready to sign a JWT, plus whatever a
 * Google service-account key file says about whose key it is.
 *
 * <h3>Two accepted shapes, both in ONE vault entry</h3>
 * <ul>
 * <li>An unencrypted PKCS#8 PEM — the {@code BEGIN PRIVATE KEY} armour.</li>
 * <li>A Google service-account JSON key file, as downloaded. Its
 * {@code private_key} is the PEM above; {@code client_email} and
 * {@code private_key_id} become the issuer and {@code kid} when the connection
 * does not name them. Its {@code token_uri} is deliberately <b>ignored</b>:
 * where a credential is sent is decided by the connection document and the
 * operator's credential-endpoint allowlist, never by the secret itself.</li>
 * </ul>
 *
 * <h3>Nothing here may leak key material</h3> Every refusal is written by hand
 * and carries no cause: a JSON parser's exception quotes the source it choked
 * on, and the source is the key. {@link #toString()} is redacted for the same
 * reason — a key that reaches a log line by accident is a key to rotate.
 */
public final class SigningKey {

    /** Below this an RSA key is not a credential worth sending a token for. */
    static final int MIN_RSA_BITS = 2048;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The armour is assembled rather than written out whole: a complete PEM
    // header in a source file trips secret scanners on every change near it.
    private static final String ARMOUR = "-----";
    private static final String PKCS8_BEGIN = ARMOUR + "BEGIN PRIVATE KEY" + ARMOUR;
    private static final String PKCS8_END = ARMOUR + "END PRIVATE KEY" + ARMOUR;

    private final PrivateKey privateKey;
    private final String keyId;
    private final String clientEmail;

    private SigningKey(PrivateKey privateKey, String keyId, String clientEmail) {
        this.privateKey = privateKey;
        this.keyId = keyId;
        this.clientEmail = clientEmail;
    }

    /**
     * Parses the resolved vault value.
     *
     * @param material
     *            a PKCS#8 PEM or a service-account JSON key file
     * @param connectionName
     *            for the refusal only
     * @throws ConnectionException
     *             {@code INVALID_CONFIGURATION} naming what is wrong, never quoting
     *             the material
     */
    public static SigningKey parse(String material, String connectionName) {
        if (material == null || material.isBlank()) {
            throw invalid(connectionName, "the vault entry referenced by oauth.privateKey is empty");
        }
        String trimmed = material.strip();
        if (trimmed.startsWith("{")) {
            return parseJsonKeyFile(trimmed, connectionName);
        }
        return new SigningKey(parsePem(trimmed, connectionName), null, null);
    }

    private static SigningKey parseJsonKeyFile(String json, String connectionName) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            // No cause, on purpose: the parser's message quotes the text around the
            // error, and that text is the private key.
            throw invalid(connectionName, "the vault entry referenced by oauth.privateKey starts like a JSON key file but is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw invalid(connectionName, "the vault entry referenced by oauth.privateKey is JSON but not an object");
        }
        String type = text(node, "type");
        if (type != null && !"service_account".equals(type)) {
            throw invalid(connectionName, "the JSON key file in the vault is of type '" + (type.length() > 40 ? type.substring(0, 40) + "…" : type)
                    + "', not 'service_account'. Download a service-account key (IAM > Service accounts > Keys > Add key > JSON).");
        }
        String pem = text(node, "private_key");
        if (pem == null || pem.isBlank()) {
            throw invalid(connectionName, "the JSON key file in the vault has no private_key field");
        }
        return new SigningKey(parsePem(pem.strip(), connectionName), text(node, "private_key_id"), text(node, "client_email"));
    }

    private static PrivateKey parsePem(String pem, String connectionName) {
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw invalid(connectionName, "the key is PKCS#1 (BEGIN RSA PRIVATE KEY). Convert it to PKCS#8 with "
                    + "'openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem' and store the result in the vault");
        }
        if (pem.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            throw invalid(connectionName, "the key is encrypted. The vault already encrypts it at rest; store the unencrypted PKCS#8 "
                    + "form (openssl pkcs8 -topk8 -nocrypt)");
        }
        int begin = pem.indexOf(PKCS8_BEGIN);
        int end = pem.indexOf(PKCS8_END);
        if (begin < 0 || end < begin) {
            throw invalid(connectionName, "the vault entry referenced by oauth.privateKey is neither a PKCS#8 PEM (" + PKCS8_BEGIN + ") "
                    + "nor a service-account JSON key file");
        }
        String body = pem.substring(begin + PKCS8_BEGIN.length(), end).replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw invalid(connectionName, "the PEM body of the private key is not valid base64");
        }
        PrivateKey key;
        try {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw invalid(connectionName, "the private key is not an RSA key in PKCS#8 form. Only RS256 signing is supported");
        }
        if (key instanceof RSAPrivateKey rsa && rsa.getModulus().bitLength() < MIN_RSA_BITS) {
            throw invalid(connectionName, "the RSA key is " + rsa.getModulus().bitLength() + " bits; at least " + MIN_RSA_BITS + " are required");
        }
        return key;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static ConnectionException invalid(String connectionName, String why) {
        return new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                "Cannot use the signing key of connection '" + connectionName + "': " + why + ". Nothing was sent.");
    }

    PrivateKey privateKey() {
        return privateKey;
    }

    /** The JSON key file's {@code private_key_id}, or null. Not secret. */
    public String keyId() {
        return keyId;
    }

    /** The JSON key file's {@code client_email}, or null. Not secret. */
    public String clientEmail() {
        return clientEmail;
    }

    @Override
    public String toString() {
        return "SigningKey[key=<REDACTED>, keyId=" + keyId + ", clientEmail=" + clientEmail + "]";
    }
}
