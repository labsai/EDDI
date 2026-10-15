/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Turns arbitrary identifiers into NATS KV keys.
 * <p>
 * A KV key may contain letters, digits, {@code -}, {@code _}, {@code =},
 * {@code /} and {@code .} (the last as a token separator, never leading,
 * trailing or doubled). Conversation ids (ObjectId hex or UUID) already qualify
 * and are kept readable; anything else — user ids, nonces, Slack event ids,
 * peer-scoped A2A ids — is replaced by {@code h_} plus the base64url SHA-256 of
 * the value, so no caller-supplied string can address a key it does not own and
 * no personal data lands in a key name.
 */
public final class KvKeys {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private KvKeys() {
    }

    /** {@code value} itself when it is a plain token, else its hash. */
    public static String safe(String value) {
        if (value != null && SAFE.matcher(value).matches() && !value.startsWith("h_")) {
            return value;
        }
        return hashed(value);
    }

    /** Always the hash — for values that must never appear in clear. */
    public static String hashed(String value) {
        return "h_" + sha256(value == null ? "" : value);
    }

    /** Base64url (no padding) of the SHA-256 of {@code value}. */
    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
