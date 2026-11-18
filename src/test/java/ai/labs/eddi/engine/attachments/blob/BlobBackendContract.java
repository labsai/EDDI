/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import ai.labs.eddi.engine.attachments.blob.BlobLengthException.Reason;
import ai.labs.eddi.testing.ChildJvm;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The behaviour every {@link BlobBackend} must have, run against each one: the
 * in-memory test double in the unit run, and {@code gridfs} and
 * {@code pg-chunks} against real databases in their {@code *IT}s.
 * <p>
 * Each test starts from an empty backend (the implementing class clears it in
 * {@code @BeforeEach}), so {@link #storedRecordCount()} can prove that a failed
 * {@code put} left nothing behind. The failing inputs fail only after more than
 * two chunks have been read, so chunks are already durably written when the
 * failure comes: a backend that merely rolled back would still pass a test that
 * failed in the first chunk.
 */
public interface BlobBackendContract {

    /** The backend under test, writing chunks of {@link #chunkBytes()}. */
    BlobBackend backend();

    /** A second backend over the same storage, writing chunks of another size. */
    BlobBackend backendWithChunkBytes(int chunkBytes);

    /** The chunk size {@link #backend()} writes. */
    int chunkBytes();

    /**
     * Every stored record of any object (rows, files documents and chunks): zero
     * means the backend holds nothing at all, partial objects included.
     */
    long storedRecordCount() throws Exception;

    /**
     * The backend-specific tail of the {@link BlobBackendHeapProbe} arguments, or
     * {@code null} where no child JVM can reach the backend.
     */
    default List<String> heapProbeArgs() {
        return null;
    }

    /**
     * The chunk size the heap probe writes with: by default {@link #chunkBytes()}.
     */
    default int heapProbeChunkBytes() {
        return chunkBytes();
    }

    // --- round trips ----------------------------------------------------------

    @Test
    default void roundTripsEveryChunkBoundarySize() throws Exception {
        int c = chunkBytes();
        for (int size : new int[]{0, 1, c - 1, c, c + 1, 3 * c + 17}) {
            String key = BlobKeys.random();
            backend().put(key, new SeededInputStream(size, size), size, Math.max(1, size));

            try (InputStream in = backend().get(key)) {
                assertEquals(SeededInputStream.sha256(size, size), SeededInputStream.sha256(in), "content of a " + size + " B object");
            }
            assertEquals(Optional.of(new BlobStat(key, size)), backend().stat(key), "stat of a " + size + " B object");
        }
    }

    @Test
    default void roundTripsAStreamOfUnknownLengthWithinMaxBytes() throws Exception {
        int size = 2 * chunkBytes() + 5;
        String key = BlobKeys.random();

        backend().put(key, new SeededInputStream(42, size), -1, 10L * chunkBytes());

        try (InputStream in = backend().get(key)) {
            assertEquals(SeededInputStream.sha256(42, size), SeededInputStream.sha256(in));
        }
    }

    @Test
    default void refusesAnExistingKeyAndKeepsTheOriginal() throws Exception {
        int size = 2 * chunkBytes() + 3;
        String key = BlobKeys.random();
        backend().put(key, new SeededInputStream(1, size), size, size);

        assertThrows(FileAlreadyExistsException.class, () -> backend().put(key, new SeededInputStream(2, size), size, size));
        assertThrows(FileAlreadyExistsException.class, () -> backend().put(key, InputStream.nullInputStream(), 0, 1));

        try (InputStream in = backend().get(key)) {
            assertEquals(SeededInputStream.sha256(1, size), SeededInputStream.sha256(in));
        }
    }

    // --- failed puts leave nothing --------------------------------------------

    @Test
    default void anOverrunPastTheDeclaredLengthLeavesNothing() throws Exception {
        int c = chunkBytes();
        String key = BlobKeys.random();

        BlobLengthException e = assertThrows(BlobLengthException.class,
                () -> backend().put(key, new SeededInputStream(3, 3L * c + 5), 2L * c + 10, 100L * c));

        assertEquals(Reason.EXCEEDS_DECLARED_LENGTH, e.reason());
        assertNothingStored(key);
    }

    @Test
    default void anOverrunPastMaxBytesLeavesNothing() throws Exception {
        int c = chunkBytes();
        String key = BlobKeys.random();

        BlobLengthException e = assertThrows(BlobLengthException.class,
                () -> backend().put(key, new SeededInputStream(4, 3L * c), -1, 2L * c + 10));

        assertEquals(Reason.EXCEEDS_MAX_BYTES, e.reason());
        assertNothingStored(key);
    }

    @Test
    default void aDeclaredLengthAboveMaxBytesIsRefusedBeforeReading() throws Exception {
        String key = BlobKeys.random();
        AtomicLong read = new AtomicLong();
        InputStream counting = new FilterInputStream(new SeededInputStream(5, 100)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                read.addAndGet(Math.max(n, 0));
                return n;
            }
        };

        BlobLengthException e = assertThrows(BlobLengthException.class, () -> backend().put(key, counting, 100, 50));

        assertEquals(Reason.EXCEEDS_MAX_BYTES, e.reason());
        assertEquals(0, read.get(), "no byte read");
        assertNothingStored(key);
    }

    @Test
    default void aStreamShorterThanDeclaredLeavesNothing() throws Exception {
        int c = chunkBytes();
        String key = BlobKeys.random();

        BlobLengthException e = assertThrows(BlobLengthException.class,
                () -> backend().put(key, new SeededInputStream(6, 2L * c + 5), 3L * c, 3L * c));

        assertEquals(Reason.SHORTER_THAN_DECLARED, e.reason());
        assertNothingStored(key);
    }

    @Test
    default void aSourceFailureMidStreamLeavesNothingAndIsRethrown() throws Exception {
        int c = chunkBytes();
        String key = BlobKeys.random();
        IOException boom = new IOException("source failed");
        InputStream failing = new FilterInputStream(new SeededInputStream(7, 4L * c)) {
            private long delivered;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (delivered >= 2L * c + 5) {
                    throw boom;
                }
                int n = super.read(b, off, (int) Math.min(len, 2L * c + 5 - delivered));
                delivered += Math.max(n, 0);
                return n;
            }
        };

        IOException thrown = assertThrows(IOException.class, () -> backend().put(key, failing, 4L * c, 4L * c));

        assertSame(boom, thrown);
        assertNothingStored(key);
    }

    // --- reads ----------------------------------------------------------------

    @Test
    default void readsRangesWithinAndAcrossChunks() throws Exception {
        int c = chunkBytes();
        int size = 3 * c + 17;
        byte[] content = SeededInputStream.bytes(8, size);
        String key = BlobKeys.random();
        backend().put(key, new ByteArrayInputStream(content), size, size);

        assertTrue(backend().supportsRange());
        assertRangesMatch(backend(), key, content);
    }

    @Test
    default void readsRangesByTheChunkSizeTheObjectWasWrittenWith() throws Exception {
        int c = chunkBytes();
        int size = 3 * c + 17;
        byte[] content = SeededInputStream.bytes(9, size);
        String key = BlobKeys.random();
        backend().put(key, new ByteArrayInputStream(content), size, size);

        assertRangesMatch(backendWithChunkBytes(2 * c + 1), key, content);
        assertRangesMatch(backendWithChunkBytes(c / 2 + 3), key, content);
    }

    @Test
    default void aMissingKeyIsNoSuchFileNotAFailure() throws Exception {
        String key = BlobKeys.random();

        assertThrows(NoSuchFileException.class, () -> backend().get(key));
        assertThrows(NoSuchFileException.class, () -> backend().getRange(key, 0, 10));
        assertEquals(Optional.empty(), backend().stat(key));
    }

    @Test
    default void rejectsNegativeRanges() throws Exception {
        String key = BlobKeys.random();
        backend().put(key, new SeededInputStream(10, 10), 10, 10);

        assertThrows(IllegalArgumentException.class, () -> backend().getRange(key, -1, 5));
        assertThrows(IllegalArgumentException.class, () -> backend().getRange(key, 0, -5));
    }

    // --- delete and list ------------------------------------------------------

    @Test
    default void deleteRemovesTheObjectAndIsIdempotent() throws Exception {
        int size = 2 * chunkBytes() + 1;
        String key = BlobKeys.random();
        String other = BlobKeys.random();
        backend().put(key, new SeededInputStream(11, size), size, size);
        backend().put(other, new SeededInputStream(12, size), size, size);

        backend().delete(key);
        assertDoesNotThrow(() -> backend().delete(key));
        assertDoesNotThrow(() -> backend().delete(BlobKeys.random()));

        assertEquals(Optional.empty(), backend().stat(key));
        assertThrows(NoSuchFileException.class, () -> backend().get(key));
        assertEquals(Optional.of(new BlobStat(other, size)), backend().stat(other), "the other object is untouched");
        try (Stream<String> keys = backend().list("")) {
            assertEquals(List.of(other), keys.toList());
        }
    }

    @Test
    default void listsKeysByPrefix() throws Exception {
        String a1 = "a0a0a0-" + BlobKeys.random();
        String a2 = "a0a0a0-" + BlobKeys.random();
        String b1 = "b1b1b1-" + BlobKeys.random();
        for (String key : List.of(a1, a2, b1)) {
            backend().put(key, new SeededInputStream(13, 5), 5, 5);
        }
        backend().put("a0a0a0-empty", InputStream.nullInputStream(), 0, 1);

        assertEquals(Set.of(a1, a2, "a0a0a0-empty"), listed("a0a0a0-"));
        assertEquals(Set.of(b1), listed("b1b1b1"));
        assertEquals(Set.of(a1, a2, b1, "a0a0a0-empty"), listed(""));
        assertEquals(Set.of(), listed("c2"));
    }

    @Test
    default void refusesKeysAndPrefixesOutsideTheKeyAlphabet() {
        for (String bad : Arrays.asList(null, "", "-leading", "../etc", "a%b", "a_b", "a.b", "a/b", "a b", "x".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> backend().put(bad, InputStream.nullInputStream(), 0, 1), "put " + bad);
            assertThrows(IllegalArgumentException.class, () -> backend().get(bad), "get " + bad);
            assertThrows(IllegalArgumentException.class, () -> backend().delete(bad), "delete " + bad);
            assertThrows(IllegalArgumentException.class, () -> backend().stat(bad), "stat " + bad);
        }
        for (String bad : Arrays.asList(null, "a%", "_", ".*", "^a", "x".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> backend().list(bad), "list " + bad);
        }
    }

    // --- heap bound -----------------------------------------------------------

    /**
     * 64 MiB through the real backend in a child JVM limited to 128 MiB of heap: a
     * backend that held the object in a {@code byte[]} (and the copies a driver
     * makes of it) runs out of memory.
     */
    @Test
    default void roundTripsWithBoundedHeapInAChildJvm() throws Exception {
        List<String> tail = heapProbeArgs();
        assumeTrue(tail != null, "no child JVM can reach this backend");
        long size = 64L * 1024 * 1024;
        List<String> args = new ArrayList<>(List.of(Long.toString(size), Integer.toString(heapProbeChunkBytes())));
        args.addAll(tail);

        ChildJvm.Result result = ChildJvm.run(BlobBackendHeapProbe.class,
                List.of("-Xmx128m"), args,
                Duration.ofMinutes(5));

        System.out.println("heap probe: " + result.output().lines().filter(line -> line.startsWith("put ")).findFirst().orElse(""));
        assertEquals(0, result.exitCode(), result.output());
        String expected = "OK " + SeededInputStream.sha256(BlobBackendHeapProbe.SEED, size);
        assertTrue(result.output().lines().map(String::strip).anyMatch(expected::equals), result.output());
        assertEquals(0, storedRecordCount(), "the probe deleted its object");
    }

    // --- helpers --------------------------------------------------------------

    private void assertNothingStored(String key) throws Exception {
        assertEquals(Optional.empty(), backend().stat(key), "stat");
        assertEquals(Set.of(), listed(""), "list");
        assertEquals(0, storedRecordCount(), "stored records");
    }

    private Set<String> listed(String prefix) throws IOException {
        try (Stream<String> keys = backend().list(prefix)) {
            return keys.collect(Collectors.toSet());
        }
    }

    private static void assertRangesMatch(BlobBackend reader, String key, byte[] content) throws IOException {
        int size = content.length;
        int c = Math.max(1, size / 3);
        long[][] ranges = {{0, 1}, {0, size}, {c - 1, 2}, {c, c}, {5, 2L * c + 3}, {size - 3, 100}, {size, 10}, {size + 50, 10}, {7, 0},
                {0, Long.MAX_VALUE}};
        for (long[] range : ranges) {
            int from = (int) Math.min(range[0], size);
            int to = (int) Math.min((long) size, range[0] + Math.min(range[1], size));
            byte[] expected = Arrays.copyOfRange(content, from, Math.max(from, to));
            try (InputStream in = reader.getRange(key, range[0], range[1])) {
                assertArrayEquals(expected, in.readAllBytes(), "range " + range[0] + "+" + range[1]);
            }
        }
    }
}
