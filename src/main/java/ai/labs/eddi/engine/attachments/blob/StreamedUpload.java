/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import ai.labs.eddi.engine.attachments.MimeValidator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * An upload read from a stream, as both attachment stores take it: its first
 * bytes judged by {@link MimeValidator} before anything is stored, its SHA-256
 * computed while it streams, its length held to what was declared.
 * <p>
 * The first {@link #SNIFF_BYTES} bytes are read ahead (as many as the content
 * check of a text file looks at) and handed out again in front of the rest of
 * the stream, so the bytes stored are exactly the bytes sent. The digest sees
 * each byte once, as it comes off the source.
 */
public final class StreamedUpload {

    /** How many leading bytes the content check reads. */
    public static final int SNIFF_BYTES = 8192;

    private final MessageDigest digest;
    private final InputStream stream;
    private final String resolvedMime;
    private final long declaredLength;

    private StreamedUpload(MessageDigest digest, InputStream stream, String resolvedMime, long declaredLength) {
        this.digest = digest;
        this.stream = stream;
        this.resolvedMime = resolvedMime;
        this.declaredLength = declaredLength;
    }

    /**
     * Reads the head of {@code in} and judges the declared type on it.
     *
     * @param declaredLength
     *            the exact length, at least 1
     * @throws AttachmentStoreException
     *             if the length is not positive, the stream ends before its first
     *             byte, the content does not match the declared type, or reading
     *             fails
     */
    public static StreamedUpload begin(InputStream in, long declaredLength, String declaredMime) throws AttachmentStoreException {
        if (in == null || declaredLength < 1) {
            throw new AttachmentStoreException("Attachment data is empty");
        }
        MessageDigest digest = sha256();
        DigestInputStream digesting = new DigestInputStream(in, digest);
        byte[] head = new byte[(int) Math.min(SNIFF_BYTES, declaredLength)];
        int filled;
        try {
            filled = digesting.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            throw new AttachmentStoreException("Failed to read the attachment", e);
        }
        if (filled == 0) {
            throw new AttachmentStoreException("Attachment data is empty");
        }
        byte[] sniffed = filled == head.length ? head : Arrays.copyOf(head, filled);
        if (!MimeValidator.isCompatibleContent(declaredMime, sniffed)) {
            throw new AttachmentStoreException(
                    "MIME type mismatch: declared='%s', detected='%s'".formatted(declaredMime, MimeValidator.detectMime(sniffed)));
        }
        String resolved = MimeValidator.normalize(declaredMime != null ? declaredMime : MimeValidator.detectMime(sniffed));
        InputStream whole = new SequenceInputStream(new ByteArrayInputStream(sniffed), digesting);
        return new StreamedUpload(digest, whole, resolved, declaredLength);
    }

    /** The upload's bytes, from the first; not closed by the store. */
    public InputStream stream() {
        return stream;
    }

    /**
     * The declared type, normalized (or the detected one when none was declared).
     */
    public String resolvedMime() {
        return resolvedMime;
    }

    public long declaredLength() {
        return declaredLength;
    }

    /**
     * Reads the whole upload into one array — for an upload small enough to be
     * stored inline, which is at most {@code eddi.attachments.max-size-bytes}, the
     * bound every upload had before streaming existed.
     *
     * @throws AttachmentStoreException
     *             if the stream is longer or shorter than declared, or reading
     *             fails
     */
    public byte[] readFully() throws AttachmentStoreException {
        if (declaredLength > Integer.MAX_VALUE - 8) {
            throw new AttachmentStoreException("Attachment is too large to be stored inline: " + declaredLength + " bytes");
        }
        try {
            byte[] bytes = stream.readNBytes((int) declaredLength);
            if (bytes.length < declaredLength) {
                throw new AttachmentStoreException(
                        "Attachment is shorter than its declared length: %d of %d bytes".formatted(bytes.length, declaredLength));
            }
            if (stream.read() >= 0) {
                throw new AttachmentStoreException("Attachment is longer than its declared length of %d bytes".formatted(declaredLength));
            }
            return bytes;
        } catch (IOException e) {
            throw new AttachmentStoreException("Failed to read the attachment", e);
        }
    }

    /**
     * The SHA-256 of everything read so far, as lower-case hex — of the whole
     * upload once it was read to its end.
     */
    public String sha256Hex() {
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The exception a store throws for a failed backend {@code put}: a length
     * problem names itself, anything else is a store failure.
     */
    public static AttachmentStoreException putFailed(IOException e) {
        if (e instanceof BlobLengthException length) {
            return new AttachmentStoreException(switch (length.reason()) {
                case EXCEEDS_DECLARED_LENGTH -> "Attachment is longer than its declared length";
                case SHORTER_THAN_DECLARED -> "Attachment is shorter than its declared length";
                case EXCEEDS_MAX_BYTES -> "Attachment exceeds the largest size accepted";
            }, e);
        }
        return new AttachmentStoreException("Failed to store the attachment's bytes", e);
    }

    /**
     * Copies {@code in} into a new file in {@code workDir} with a name of its own
     * and, where the file system supports POSIX permissions, owner-only access. On
     * failure the file is removed.
     */
    public static Path copyToNewFile(InputStream in, Path workDir) throws IOException {
        Path file;
        try {
            file = Files.createTempFile(workDir, "eddi-blob-", ".bin",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException noPosix) {
            file = Files.createTempFile(workDir, "eddi-blob-", ".bin");
        }
        try {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException deleteFailure) {
                e.addSuppressed(deleteFailure);
            }
            throw e;
        }
    }

    /**
     * Whether a {@link BlobBackend#put} failure means the key was taken — the one
     * case where the object under it is not this upload's and must not be deleted.
     */
    public static boolean keyTaken(IOException e) {
        return e instanceof FileAlreadyExistsException;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
