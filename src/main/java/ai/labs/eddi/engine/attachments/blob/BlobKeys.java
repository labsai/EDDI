/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Blob keys: random, opaque, and restricted to letters, digits and {@code -},
 * so that no backend has to escape them in a {@code LIKE} pattern or a regular
 * expression, and none can name a path.
 */
public final class BlobKeys {

    /** The longest key a backend accepts. */
    public static final int MAX_LENGTH = 128;

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0," + (MAX_LENGTH - 1) + "}");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9-]{0," + MAX_LENGTH + "}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private BlobKeys() {
    }

    /** A new key: 128 random bits, hex-encoded. */
    public static String random() {
        byte[] bits = new byte[16];
        RANDOM.nextBytes(bits);
        return HexFormat.of().formatHex(bits);
    }

    /**
     * @return the key
     * @throws IllegalArgumentException
     *             unless the key is 1 to 128 letters, digits or {@code -}, starting
     *             with a letter or digit
     */
    public static String requireValidKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid blob key");
        }
        return key;
    }

    /**
     * @return the prefix
     * @throws IllegalArgumentException
     *             unless the prefix is at most 128 letters, digits or {@code -};
     *             the empty prefix matches every key
     */
    public static String requireValidPrefix(String prefix) {
        if (prefix == null || !PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid blob key prefix");
        }
        return prefix;
    }
}
