/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The decoded bytes of one attachment, held in memory.
 * <p>
 * In memory because the message already is: the parser reads a byte array
 * bounded by {@link MailLimits#maxRawBytes()}, and an attachment's decoded size
 * by {@link MailLimits#maxAttachmentBytes()}. A caller that stores it (the
 * attachment store, a RAG converter) streams it from {@link #openStream()}. The
 * array is never exposed, so the content cannot change after parsing.
 */
public final class AttachmentContent {

    private final byte[] bytes;

    AttachmentContent(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Decoded size in bytes. */
    public int size() {
        return bytes.length;
    }

    /** A fresh stream over the content. */
    public InputStream openStream() {
        return new ByteArrayInputStream(bytes);
    }

    /** A copy of the content. */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /** The SHA-256 of the content, lower-case hex — a dedup key. */
    public String sha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    @Override
    public String toString() {
        return "AttachmentContent[" + bytes.length + " bytes]";
    }
}
