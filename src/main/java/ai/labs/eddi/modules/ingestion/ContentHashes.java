/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content hashing for ingestion dedup.
 *
 * <p>
 * A static utility rather than a method on {@link IIngestionStateStore}: the
 * hash must be identical whichever backend stores it, and putting it on the
 * interface meant each implementation carried its own copy — two chances to
 * drift, and a drift would silently re-embed an entire knowledge base on the
 * next run after a backend switch.
 *
 * <p>
 * Not a security primitive: this detects change, not tampering.
 */
public final class ContentHashes {

    private ContentHashes() {
    }

    /**
     * SHA-256 of the given content, lowercase hex. Null is hashed as empty so a
     * caller cannot accidentally key on the string "null".
     */
    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }
}
