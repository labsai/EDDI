/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamedUploadTest {

    private static byte[] pdf(int size) {
        byte[] bytes = new byte[size];
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, bytes, 0, Math.min(header.length, size));
        for (int i = header.length; i < size; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Hands out at most a few bytes per read, as a network stream may. */
    private static InputStream trickling(byte[] bytes) {
        return new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 7));
            }
        };
    }

    @Test
    void theBytesHandedOutAreExactlyTheBytesSentAndTheDigestSeesEachOnce() throws Exception {
        byte[] sent = pdf(50_000);
        StreamedUpload upload = StreamedUpload.begin(trickling(sent), sent.length, "application/pdf");

        byte[] received = upload.stream().readAllBytes();

        assertArrayEquals(sent, received, "the sniffed head is handed out again, in front of the rest");
        assertEquals(sha256(sent), upload.sha256Hex());
        assertEquals("application/pdf", upload.resolvedMime());
    }

    @Test
    void theHeadIsFilledEvenFromAStreamThatTrickles() throws Exception {
        // A WebP is recognised by bytes 8 to 11 ("RIFF....WEBP"), and its type needs
        // a signature: a head of one short read (7 bytes) would refuse it.
        byte[] sent = new byte[20_000];
        byte[] header = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, sent, 0, header.length);

        StreamedUpload upload = StreamedUpload.begin(trickling(sent), sent.length, "image/webp");

        assertEquals("image/webp", upload.resolvedMime());
        assertArrayEquals(sent, upload.stream().readAllBytes());
    }

    @Test
    void aMismatchIsRefusedOnTheHeadAlone() {
        CountingStream source = new CountingStream(pdf(100_000));
        var refused = assertThrows(AttachmentStoreException.class, () -> StreamedUpload.begin(source, 100_000, "image/png"));
        assertTrue(refused.getMessage().contains("MIME type mismatch"), refused.getMessage());
        assertEquals(StreamedUpload.SNIFF_BYTES, source.read, "only the head was read");
    }

    @Test
    void textDeclaredAsTextPassesWhateverItsFirstBytesLookLike() throws Exception {
        byte[] sent = "GIF8 is how these notes start".getBytes(StandardCharsets.UTF_8);
        StreamedUpload upload = StreamedUpload.begin(new ByteArrayInputStream(sent), sent.length, "text/plain; charset=utf-8");

        assertEquals("text/plain", upload.resolvedMime());
        assertArrayEquals(sent, upload.readFully());
    }

    @Test
    void anEmptyStreamOrLengthIsRefused() {
        assertThrows(AttachmentStoreException.class, () -> StreamedUpload.begin(new ByteArrayInputStream(new byte[0]), 10, "text/plain"));
        assertThrows(AttachmentStoreException.class, () -> StreamedUpload.begin(new ByteArrayInputStream(pdf(10)), 0, "application/pdf"));
        assertThrows(AttachmentStoreException.class, () -> StreamedUpload.begin(null, 10, "application/pdf"));
    }

    @Test
    void readFullyHoldsTheStreamToItsDeclaredLength() throws Exception {
        byte[] sent = pdf(20_000);
        assertArrayEquals(sent, StreamedUpload.begin(new ByteArrayInputStream(sent), sent.length, "application/pdf").readFully());

        var shorter = assertThrows(AttachmentStoreException.class,
                () -> StreamedUpload.begin(new ByteArrayInputStream(sent), sent.length + 1, "application/pdf").readFully());
        assertTrue(shorter.getMessage().contains("shorter"), shorter.getMessage());
        var longer = assertThrows(AttachmentStoreException.class,
                () -> StreamedUpload.begin(new ByteArrayInputStream(sent), sent.length - 1, "application/pdf").readFully());
        assertTrue(longer.getMessage().contains("longer"), longer.getMessage());
    }

    @Test
    void aPutFailureNamesALengthProblemAndCallsAnythingElseAStoreFailure() {
        assertTrue(StreamedUpload.putFailed(new BlobLengthException(BlobLengthException.Reason.SHORTER_THAN_DECLARED, "x")).getMessage()
                .contains("shorter"));
        assertTrue(StreamedUpload.putFailed(new BlobLengthException(BlobLengthException.Reason.EXCEEDS_DECLARED_LENGTH, "x")).getMessage()
                .contains("longer"));
        assertTrue(StreamedUpload.putFailed(new IOException("connection reset")).getMessage().contains("Failed to store"));
        assertTrue(StreamedUpload.keyTaken(new FileAlreadyExistsException("k")));
        assertFalse(StreamedUpload.keyTaken(new IOException("k")));
    }

    @Test
    void copyToNewFileUsesANameOfItsOwnAndRemovesAPartialFile(@TempDir Path workDir) throws Exception {
        byte[] sent = pdf(10_000);
        Path file = StreamedUpload.copyToNewFile(new ByteArrayInputStream(sent), workDir);
        assertEquals(workDir, file.getParent());
        assertTrue(file.getFileName().toString().startsWith("eddi-blob-"));
        assertArrayEquals(sent, Files.readAllBytes(file));

        InputStream breaks = new InputStream() {
            private int handedOut;

            @Override
            public int read() throws IOException {
                if (handedOut++ > 100) {
                    throw new IOException("connection reset");
                }
                return 1;
            }
        };
        assertThrows(IOException.class, () -> StreamedUpload.copyToNewFile(breaks, workDir));
        try (var files = Files.list(workDir)) {
            assertEquals(1, files.count(), "only the complete copy is left");
        }
    }

    private static final class CountingStream extends ByteArrayInputStream {
        int read;

        CountingStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            int n = super.read(b, off, len);
            if (n > 0) {
                read += n;
            }
            return n;
        }

        @Override
        public synchronized int read() {
            int b = super.read();
            if (b >= 0) {
                read++;
            }
            return b;
        }
    }
}
