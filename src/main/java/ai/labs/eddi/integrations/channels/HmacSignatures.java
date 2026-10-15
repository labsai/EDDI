/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 primitives shared by every inbound channel that authenticates a
 * request by its signature — the Slack Events API and the generic signed
 * webhook channel.
 * <p>
 * Kept deliberately small and free of any scheme knowledge: which bytes are
 * signed, which header carries the signature and how it is encoded is each
 * channel's business. What is shared is the part that must never be done
 * differently in two places — computing the MAC over the <em>raw</em> bytes,
 * comparing in constant time, and judging a timestamp's freshness.
 *
 * @since 6.6.0
 */
public final class HmacSignatures {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private HmacSignatures() {
    }

    /**
     * HMAC-SHA256 of the concatenation of {@code parts}, keyed by the UTF-8 bytes
     * of {@code secret}.
     *
     * @throws IllegalArgumentException
     *             when the secret is null or blank — an empty key would make every
     *             signature computable by anyone
     */
    public static byte[] hmacSha256(String secret, byte[]... parts) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("HMAC secret must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            for (byte[] part : parts) {
                if (part != null) {
                    mac.update(part);
                }
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is a mandatory JCA algorithm; this cannot happen on a
            // conforming JVM.
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /** Lower-case hex HMAC-SHA256 — the encoding Slack, GitHub and EDDI use. */
    public static String hmacSha256Hex(String secret, byte[]... parts) {
        return HEX.formatHex(hmacSha256(secret, parts));
    }

    /** Lower-case hex encoding of {@code bytes}. */
    public static String toHex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    /**
     * Constant-time comparison of two strings by their UTF-8 bytes; {@code false}
     * when either is {@code null}.
     */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /** Constant-time comparison of two byte arrays; {@code false} for null. */
    public static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Whether a Unix-seconds timestamp lies within {@code maxAgeSeconds} of
     * {@code nowEpochSeconds}, in either direction (a clock running ahead is as
     * suspicious as a replay).
     */
    public static boolean isFresh(long timestampEpochSeconds, long nowEpochSeconds, long maxAgeSeconds) {
        return Math.abs(nowEpochSeconds - timestampEpochSeconds) <= maxAgeSeconds;
    }
}
