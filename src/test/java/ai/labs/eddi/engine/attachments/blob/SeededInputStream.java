/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.SplittableRandom;

/**
 * A deterministic pseudo-random stream of a given length, generated as it is
 * read, so a test can push 64 MB through a backend without ever holding 64 MB.
 * The same seed and length always give the same bytes.
 */
final class SeededInputStream extends InputStream {

    private final SplittableRandom random;
    private long remaining;

    SeededInputStream(long seed, long length) {
        this.random = new SplittableRandom(seed);
        this.remaining = length;
    }

    @Override
    public int read() {
        if (remaining <= 0) {
            return -1;
        }
        remaining--;
        return random.nextInt(256);
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (len == 0) {
            return 0;
        }
        if (remaining <= 0) {
            return -1;
        }
        int n = (int) Math.min(len, remaining);
        for (int i = 0; i < n; i++) {
            b[off + i] = (byte) random.nextInt(256);
        }
        remaining -= n;
        return n;
    }

    /** The bytes, for sizes small enough to compare in memory. */
    static byte[] bytes(long seed, int length) {
        try {
            return new SeededInputStream(seed, length).readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 of the stream, computed while draining it. */
    static String sha256(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        try (OutputStream sink = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            in.transferTo(sink);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(long seed, long length) throws IOException {
        return sha256(new SeededInputStream(seed, length));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
