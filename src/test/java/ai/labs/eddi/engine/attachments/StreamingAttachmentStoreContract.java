/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentAccessDeniedException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentNotFoundException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentTooLargeForMemoryException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.OwnerRef;
import ai.labs.eddi.engine.attachments.blob.FaultInjectingBlobBackend;
import ai.labs.eddi.testing.ChildJvm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The streaming store API, the same on both attachment stores — run against a
 * real database and its real blob backend by {@code GridFsAttachmentStoreIT}
 * and {@code PostgresAttachmentStoreIT}. A file up to the memory guard (1 MiB
 * here) is stored inline and loads as before; a larger one, accepted only in
 * {@link QuotaMode#LARGE_PER_USER}, streams to the backend and is read as a
 * stream. A reservation holds the quota from before the first byte; every
 * failure leaves no object and no row; a delete the backend cannot finish
 * leaves a tombstone the sweep retries.
 */
public interface StreamingAttachmentStoreContract {

    /** The store under test, with every quota off and a 1 MiB size limit. */
    IAttachmentStore store();

    /** The real blob backend wired into the store, with its fault switches off. */
    FaultInjectingBlobBackend blobBackend();

    /** Sets the quota limits ({@code <= 0} disables one). */
    void setQuotas(long perConversation, long bytesPerConversation, long perUser, long bytesPerUser);

    /** Sets {@code eddi.attachments.max-size-bytes} on the store. */
    void setMaxSizeBytes(long maxSizeBytes);

    /** Reservation rows (documents) stored, live or not. */
    long reservationRows();

    /**
     * Moves the expiry of every reservation into the past, as the database sees it.
     */
    void expireReservations();

    /** Makes every tombstone due now, as the database sees it. */
    void makeTombstonesDue();

    /**
     * Writes a tombstone due now for {@code key} of the backend, as a crashed
     * writer leaves one.
     */
    void seedDueTombstone(String key, String ownerKey);

    /**
     * The backend-specific arguments of {@link AttachmentStoreHeapProbe}, or
     * {@code null} where no child JVM can reach the database.
     */
    default List<String> heapProbeArgs() {
        return null;
    }

    String CONVERSATION = "6aba70011a25f8be7920e201";
    String OTHER_CONVERSATION = "6aba70011a25f8be7920e202";
    String STREAM_STRANGER = "6aba70011a25f8be7920e203";
    String STREAM_USER = "user-6aba70011a25f8c0";
    String OTHER_USER = "user-6aba70011a25f8c1";
    long GUARD = 1_048_576;
    long LARGE = 3L * 1024 * 1024 + 17;
    long SEED = 0x5EED_5717L;

    private Attachment streamLarge(String conversationId, String userId, long size) throws AttachmentStoreException {
        return store().store(new PdfLikeStream(SEED, size), size, "application/pdf", "large.pdf", conversationId, "default", userId, null,
                QuotaMode.LARGE_PER_USER);
    }

    private Attachment streamSmall(String conversationId, String userId, long size) throws AttachmentStoreException {
        return store().store(new PdfLikeStream(SEED, size), size, "application/pdf", "small.pdf", conversationId, "default", userId, null,
                QuotaMode.STANDARD);
    }

    private void assertNothingLeft(String conversationId) throws Exception {
        assertEquals(0, blobBackend().keyCount(), "no object in the backend");
        assertTrue(store().listByConversation(conversationId).isEmpty(), "no row");
        assertEquals(0, reservationRows(), "no reservation held");
    }

    // ------------------------------------------------------------ round trips

    @Test
    default void aSmallStreamIsStoredInlineAndLoadsAsBefore() throws Exception {
        Attachment stored = streamSmall(CONVERSATION, STREAM_USER, 10_000);

        assertEquals("application/pdf", stored.mimeType());
        assertEquals(10_000, stored.sizeBytes());
        assertArrayEquals(PdfLikeStream.bytes(SEED, 0, 10_000), store().load(stored.storageRef(), CONVERSATION));
        try (InputStream in = store().open(stored.storageRef(), CONVERSATION)) {
            assertArrayEquals(store().load(stored.storageRef(), CONVERSATION), in.readAllBytes(), "open equals load");
        }
        assertEquals(0, blobBackend().keyCount(), "inline: nothing in the backend");
        assertEquals(0, reservationRows(), "the reservation was converted");
    }

    @Test
    default void aLargeStreamGoesToTheBackendAndReadsBackAsAStream(@TempDir Path workDir) throws Exception {
        Attachment stored = streamLarge(CONVERSATION, STREAM_USER, LARGE);

        assertEquals(LARGE, stored.sizeBytes());
        assertEquals(1, blobBackend().keyCount(), "one object in the backend");
        assertEquals(LARGE, store().getMetadata(stored.storageRef(), CONVERSATION).sizeBytes());
        assertEquals(LARGE, store().listByConversation(CONVERSATION).get(0).sizeBytes(), "listed with its real size");
        try (InputStream in = store().open(stored.storageRef(), CONVERSATION)) {
            assertEquals(PdfLikeStream.sha256(SEED, LARGE), PdfLikeStream.sha256(in));
        }
        try (InputStream in = store().openRange(stored.storageRef(), CONVERSATION, 1_000_000, 70_000)) {
            assertArrayEquals(PdfLikeStream.bytes(SEED, 1_000_000, 70_000), in.readAllBytes());
        }
        try (InputStream in = store().openRange(stored.storageRef(), CONVERSATION, LARGE - 10, 1_000)) {
            assertEquals(10, in.readAllBytes().length, "clipped at the end");
        }
        Path file = store().materialize(stored.storageRef(), CONVERSATION, workDir);
        assertEquals(workDir, file.getParent());
        assertFalse(file.getFileName().toString().contains("large"), "a name of the store's choosing");
        try (InputStream in = Files.newInputStream(file)) {
            assertEquals(PdfLikeStream.sha256(SEED, LARGE), PdfLikeStream.sha256(in));
        }
        assertEquals(0, reservationRows(), "the reservation was converted");
    }

    @Test
    default void loadRefusesALargeBlobWithoutReadingIt() throws Exception {
        Attachment stored = streamLarge(CONVERSATION, STREAM_USER, LARGE);
        blobBackend().failReads(true);

        AttachmentTooLargeForMemoryException refused = assertThrows(AttachmentTooLargeForMemoryException.class,
                () -> store().load(stored.storageRef(), CONVERSATION));
        assertEquals(LARGE, refused.getSizeBytes());
        assertEquals(GUARD, refused.getLimitBytes());
    }

    @Test
    default void theGuardIsJudgedOnSizeNotOnWhereTheBytesAre() throws Exception {
        Attachment inline = streamSmall(CONVERSATION, STREAM_USER, 900_000);
        Attachment large = streamLarge(CONVERSATION, STREAM_USER, LARGE);

        setMaxSizeBytes(500_000);
        assertThrows(AttachmentTooLargeForMemoryException.class, () -> store().load(inline.storageRef(), CONVERSATION),
                "an inline blob above a lowered guard is refused too");
        try (InputStream in = store().open(inline.storageRef(), CONVERSATION)) {
            assertEquals(900_000, in.readAllBytes().length, "but still opens");
        }

        setMaxSizeBytes(8L * 1024 * 1024);
        assertEquals(PdfLikeStream.sha256(SEED, LARGE), PdfLikeStream.sha256(new ByteArrayInputStream(
                store().load(large.storageRef(), CONVERSATION))), "a located blob within a raised guard loads");
    }

    @Test
    default void openAndMaterializeAnswerStrangersAsLoadDoes(@TempDir Path workDir) throws Exception {
        Attachment stored = streamLarge(CONVERSATION, STREAM_USER, LARGE);

        assertThrows(AttachmentAccessDeniedException.class, () -> store().open(stored.storageRef(), STREAM_STRANGER));
        assertThrows(AttachmentAccessDeniedException.class, () -> store().openRange(stored.storageRef(), STREAM_STRANGER, 0, 10));
        assertThrows(AttachmentAccessDeniedException.class, () -> store().materialize(stored.storageRef(), STREAM_STRANGER, workDir));
        try (var files = Files.list(workDir)) {
            assertEquals(0, files.count(), "a refused materialize leaves no file");
        }
        assertThrows(AttachmentNotFoundException.class, () -> store().open("8d4f6f1e-0000-4000-8000-0000000000aa", CONVERSATION));

        store().grantAccess(stored.storageRef(), OTHER_CONVERSATION);
        try (InputStream in = store().openRange(stored.storageRef(), OTHER_CONVERSATION, 0, 9)) {
            assertEquals("%PDF-1.7\n", new String(in.readAllBytes(), StandardCharsets.US_ASCII), "a grantee reads");
        }
    }

    // ------------------------------------------------------------ refusals

    @Test
    default void standardModeRefusesAboveTheGuardBeforeReadingAByte() throws Exception {
        PdfLikeStream source = new PdfLikeStream(SEED, LARGE);
        AttachmentStoreException refused = assertThrows(AttachmentStoreException.class,
                () -> store().store(source, LARGE, "application/pdf", "big.pdf", CONVERSATION, "default", STREAM_USER, null,
                        QuotaMode.STANDARD));
        assertFalse(refused instanceof AttachmentQuotaExceededException);
        assertTrue(refused.getMessage().contains("max size"), refused.getMessage());
        assertEquals(0, source.position(), "nothing read");
        assertNothingLeft(CONVERSATION);
    }

    @Test
    default void aLargeUploadNeedsAUser() {
        assertThrows(IllegalArgumentException.class, () -> streamLarge(CONVERSATION, null, LARGE));
        assertThrows(IllegalArgumentException.class, () -> store().reserve(new OwnerRef(OwnerScope.CONVERSATION, CONVERSATION, null),
                CONVERSATION, null, LARGE, 1, Duration.ofMinutes(5), QuotaMode.LARGE_PER_USER));
    }

    @Test
    default void aMismatchedTypeIsRefusedOnTheFirstBytesAndLeavesNothing() throws Exception {
        PdfLikeStream source = new PdfLikeStream(SEED, LARGE);
        AttachmentStoreException refused = assertThrows(AttachmentStoreException.class,
                () -> store().store(source, LARGE, "image/png", "fake.png", CONVERSATION, "default", STREAM_USER, null,
                        QuotaMode.LARGE_PER_USER));
        assertTrue(refused.getMessage().contains("MIME type mismatch"), refused.getMessage());
        assertTrue(source.position() <= 8192, "only the head was read: " + source.position());
        assertNothingLeft(CONVERSATION);
    }

    @Test
    default void aStreamShorterThanDeclaredLeavesNothing() throws Exception {
        assertThrows(AttachmentStoreException.class, () -> store().store(new PdfLikeStream(SEED, LARGE - 1), LARGE, "application/pdf",
                "short.pdf", CONVERSATION, "default", STREAM_USER, null, QuotaMode.LARGE_PER_USER));
        assertThrows(AttachmentStoreException.class, () -> store().store(new PdfLikeStream(SEED, 5_000), 5_001, "application/pdf",
                "short.pdf", CONVERSATION, "default", STREAM_USER, null, QuotaMode.STANDARD));
        assertNothingLeft(CONVERSATION);
        assertEquals(0, store().pendingTombstones(null));
    }

    @Test
    default void aStreamLongerThanDeclaredLeavesNothing() throws Exception {
        assertThrows(AttachmentStoreException.class, () -> store().store(new PdfLikeStream(SEED, LARGE + 1), LARGE, "application/pdf",
                "long.pdf", CONVERSATION, "default", STREAM_USER, null, QuotaMode.LARGE_PER_USER));
        assertThrows(AttachmentStoreException.class, () -> store().store(new PdfLikeStream(SEED, 5_001), 5_000, "application/pdf",
                "long.pdf", CONVERSATION, "default", STREAM_USER, null, QuotaMode.STANDARD));
        assertNothingLeft(CONVERSATION);
        assertEquals(0, store().pendingTombstones(null));
    }

    // ------------------------------------------------------------ quota

    @Test
    default void aCallersReservationIsConvertedIntoTheBlob() throws Exception {
        setQuotas(0, 0, 0, LARGE + 100);
        QuotaReservation reservation = store().reserve(new OwnerRef(OwnerScope.CONVERSATION, CONVERSATION, STREAM_USER), CONVERSATION,
                STREAM_USER, LARGE, 1, Duration.ofMinutes(10), QuotaMode.LARGE_PER_USER);

        Attachment stored = store().store(new PdfLikeStream(SEED, LARGE), LARGE, "application/pdf", "large.pdf", CONVERSATION, "default",
                STREAM_USER, reservation.id(), QuotaMode.LARGE_PER_USER);

        assertEquals(0, reservationRows(), "converted, not left behind");
        assertEquals(LARGE, store().getMetadata(stored.storageRef(), CONVERSATION).sizeBytes());
        var refused = assertThrows(AttachmentQuotaExceededException.class, () -> store().reserve(
                new OwnerRef(OwnerScope.CONVERSATION, CONVERSATION, STREAM_USER), CONVERSATION, STREAM_USER, 200, 0, Duration.ofMinutes(1),
                QuotaMode.LARGE_PER_USER));
        assertEquals(AttachmentQuotaExceededException.SCOPE_USER, refused.getScope(), "the blob counts in the reservation's place");
    }

    @Test
    default void aReservationThatCannotCoverTheUploadIsRefusedBeforeTheFirstByte() throws Exception {
        QuotaReservation tooSmall = store().reserve(new OwnerRef(OwnerScope.CONVERSATION, CONVERSATION, STREAM_USER), CONVERSATION,
                STREAM_USER, LARGE - 1, 1, Duration.ofMinutes(10), QuotaMode.LARGE_PER_USER);
        QuotaReservation otherUser = store().reserve(new OwnerRef(OwnerScope.CONVERSATION, CONVERSATION, OTHER_USER), CONVERSATION,
                OTHER_USER, LARGE, 1, Duration.ofMinutes(10), QuotaMode.LARGE_PER_USER);
        QuotaReservation standard = store().reserve(new OwnerRef(OwnerScope.CONVERSATION, CONVERSATION, STREAM_USER), CONVERSATION,
                STREAM_USER, LARGE, 1, Duration.ofMinutes(10), QuotaMode.STANDARD);

        for (QuotaReservation reservation : List.of(tooSmall, otherUser, standard)) {
            PdfLikeStream source = new PdfLikeStream(SEED, LARGE);
            var refused = assertThrows(AttachmentQuotaExceededException.class, () -> store().store(source, LARGE, "application/pdf",
                    "large.pdf", CONVERSATION, "default", STREAM_USER, reservation.id(), QuotaMode.LARGE_PER_USER));
            assertEquals(AttachmentQuotaExceededException.SCOPE_RESERVATION, refused.getScope());
            assertEquals(0, source.position(), "nothing read");
        }
        assertEquals(0, blobBackend().keyCount());
        assertTrue(store().listByConversation(CONVERSATION).isEmpty());
    }

    @Test
    default void anExpiredReservationIsRefused() throws Exception {
        QuotaReservation reservation = store().reserve(new OwnerRef(OwnerScope.CONVERSATION, CONVERSATION, STREAM_USER), CONVERSATION,
                STREAM_USER, LARGE, 1, Duration.ofMinutes(10), QuotaMode.LARGE_PER_USER);
        expireReservations();

        assertThrows(AttachmentQuotaExceededException.class, () -> store().store(new PdfLikeStream(SEED, LARGE), LARGE, "application/pdf",
                "large.pdf", CONVERSATION, "default", STREAM_USER, reservation.id(), QuotaMode.LARGE_PER_USER));
        assertEquals(0, blobBackend().keyCount());
    }

    @Test
    default void aReservationThatExpiresWhileTheBytesStreamLeavesNothing() throws Exception {
        PdfLikeStream source = new PdfLikeStream(SEED, LARGE, LARGE / 2, this::expireReservations);
        var refused = assertThrows(AttachmentQuotaExceededException.class, () -> store().store(source, LARGE, "application/pdf",
                "large.pdf", CONVERSATION, "default", STREAM_USER, null, QuotaMode.LARGE_PER_USER));
        assertEquals(AttachmentQuotaExceededException.SCOPE_RESERVATION, refused.getScope());
        assertEquals(0, blobBackend().keyCount(), "the object is deleted again");
        assertTrue(store().listByConversation(CONVERSATION).isEmpty(), "and no row was kept");
        assertEquals(0, store().pendingTombstones(null));
    }

    @Test
    default void aLargeUploadIsExemptFromTheConversationByteQuotaButCountsForItsUser() throws Exception {
        setQuotas(0, GUARD, 0, LARGE + GUARD);

        streamLarge(CONVERSATION, STREAM_USER, LARGE);
        streamSmall(CONVERSATION, STREAM_USER, GUARD - 10);

        var conversationFull = assertThrows(AttachmentQuotaExceededException.class, () -> streamSmall(CONVERSATION, OTHER_USER, 100));
        assertEquals(AttachmentQuotaExceededException.SCOPE_CONVERSATION, conversationFull.getScope(),
                "the conversation's bytes are the small file's only");
        var userFull = assertThrows(AttachmentQuotaExceededException.class, () -> streamLarge(OTHER_CONVERSATION, STREAM_USER, LARGE));
        assertEquals(AttachmentQuotaExceededException.SCOPE_USER, userFull.getScope(), "the large file counts for its user");
        assertEquals(1, blobBackend().keyCount());
    }

    @Test
    default void aLargeUploadStillCountsTowardTheConversationsFileCount() throws Exception {
        setQuotas(1, 0, 0, 0);
        streamLarge(CONVERSATION, STREAM_USER, LARGE);

        var refused = assertThrows(AttachmentQuotaExceededException.class, () -> streamSmall(CONVERSATION, STREAM_USER, 100));
        assertEquals(AttachmentQuotaExceededException.SCOPE_CONVERSATION, refused.getScope());
    }

    // ------------------------------------------------------------ deletes and
    // tombstones

    @Test
    default void deletingALargeBlobDeletesItsObject() throws Exception {
        Attachment stored = streamLarge(CONVERSATION, STREAM_USER, LARGE);

        assertTrue(store().delete(stored.storageRef(), CONVERSATION));

        assertEquals(0, blobBackend().keyCount());
        assertThrows(AttachmentNotFoundException.class, () -> store().open(stored.storageRef(), CONVERSATION));
        assertEquals(0, store().pendingTombstones(null));
    }

    @Test
    default void aDeleteTheBackendCannotFinishLeavesATombstoneTheSweepRetries() throws Exception {
        Attachment stored = streamLarge(CONVERSATION, STREAM_USER, LARGE);
        blobBackend().failDeletes(true);

        assertTrue(store().delete(stored.storageRef(), CONVERSATION));

        assertThrows(AttachmentNotFoundException.class, () -> store().open(stored.storageRef(), CONVERSATION), "gone for every reader");
        assertEquals(1, blobBackend().keyCount(), "the bytes are still there");
        assertEquals(1, store().pendingTombstones(CONVERSATION), "and reported pending for their owner");
        assertEquals(0, store().pendingTombstones(OTHER_CONVERSATION));
        assertEquals(1, store().pendingTombstones(null));

        blobBackend().failDeletes(false);
        makeTombstonesDue();
        assertEquals(1, store().sweepTombstones(10));
        assertEquals(0, blobBackend().keyCount());
        assertEquals(0, store().pendingTombstones(null));
    }

    @Test
    default void erasingAConversationDeletesItsLargeObjectsOrLeavesThemPending() throws Exception {
        streamLarge(CONVERSATION, STREAM_USER, LARGE);
        streamLarge(CONVERSATION, STREAM_USER, LARGE);
        streamLarge(OTHER_CONVERSATION, STREAM_USER, LARGE);
        blobBackend().failDeletes(true);

        assertEquals(2, store().deleteByConversation(CONVERSATION));

        assertEquals(2, store().pendingTombstones(CONVERSATION));
        blobBackend().failDeletes(false);
        makeTombstonesDue();
        assertEquals(2, store().sweepTombstones(10));
        assertEquals(1, blobBackend().keyCount(), "the other conversation's object stays");
        assertEquals(1, store().listByConversation(OTHER_CONVERSATION).size());
    }

    @Test
    default void anErasureWhileTheBytesStreamLeavesNothing() throws Exception {
        PdfLikeStream source = new PdfLikeStream(SEED, LARGE, LARGE / 2, () -> store().deleteByConversation(CONVERSATION));

        assertThrows(AttachmentStoreException.class, () -> store().store(source, LARGE, "application/pdf", "large.pdf", CONVERSATION,
                "default", STREAM_USER, null, QuotaMode.LARGE_PER_USER));

        assertNothingLeft(CONVERSATION);
        assertEquals(0, store().pendingTombstones(null));
    }

    @Test
    default void anUploadInFlightIsNotSwept() throws Exception {
        List<String> seen = new ArrayList<>();
        PdfLikeStream source = new PdfLikeStream(SEED, LARGE, LARGE / 2, () -> {
            seen.add("pending=" + store().pendingTombstones(CONVERSATION));
            seen.add("swept=" + store().sweepTombstones(10));
        });

        Attachment stored = store().store(source, LARGE, "application/pdf", "large.pdf", CONVERSATION, "default", STREAM_USER, null,
                QuotaMode.LARGE_PER_USER);

        assertEquals(List.of("pending=1", "swept=0"), seen, "the intent is recorded, and not due while the upload can complete");
        assertEquals(0, store().pendingTombstones(null), "and removed when it does");
        try (InputStream in = store().open(stored.storageRef(), CONVERSATION)) {
            assertEquals(PdfLikeStream.sha256(SEED, LARGE), PdfLikeStream.sha256(in));
        }
    }

    @Test
    default void theSweepDeletesAnObjectNoBlobReferencesAndKeepsOneThatIsReferenced() throws Exception {
        Attachment stored = streamLarge(CONVERSATION, STREAM_USER, LARGE);
        String referenced = blobBackend().onlyKey();
        // What a writer that died after recording its blob, or before, leaves behind.
        seedDueTombstone(referenced, CONVERSATION);
        String orphan = "0123456789abcdef0123456789abcdef";
        blobBackend().put(orphan, new PdfLikeStream(SEED, 1_000), 1_000, 1_000);
        seedDueTombstone(orphan, CONVERSATION);

        assertEquals(2, store().sweepTombstones(10));

        assertEquals(1, blobBackend().keyCount(), "the orphan is gone");
        assertEquals(referenced, blobBackend().onlyKey(), "the referenced object stays");
        try (InputStream in = store().open(stored.storageRef(), CONVERSATION)) {
            assertEquals(PdfLikeStream.sha256(SEED, LARGE), PdfLikeStream.sha256(in));
        }
        assertEquals(0, store().pendingTombstones(null));
    }

    @Test
    default void aFailedSweepKeepsTheTombstoneForLater() throws Exception {
        String orphan = "0123456789abcdef0123456789abcdee";
        blobBackend().put(orphan, new PdfLikeStream(SEED, 1_000), 1_000, 1_000);
        seedDueTombstone(orphan, CONVERSATION);
        blobBackend().failDeletes(true);

        assertEquals(0, store().sweepTombstones(10));
        assertEquals(1, store().pendingTombstones(CONVERSATION));

        blobBackend().failDeletes(false);
        assertEquals(0, store().sweepTombstones(10), "backed off: not due again at once, though the backend would delete it now");
        assertEquals(1, blobBackend().keyCount());
        makeTombstonesDue();
        assertEquals(1, store().sweepTombstones(10));
        assertEquals(0, blobBackend().keyCount());
    }

    // ------------------------------------------------------------ backend failures

    @Test
    default void anUnreachableBackendIsAStoreFailureNeverNotFound() throws Exception {
        Attachment stored = streamLarge(CONVERSATION, STREAM_USER, LARGE);
        blobBackend().failReads(true);

        AttachmentStoreException open = assertThrows(AttachmentStoreException.class, () -> store().open(stored.storageRef(), CONVERSATION));
        assertFalse(open instanceof AttachmentNotFoundException, "not 'not found'");
        AttachmentStoreException range = assertThrows(AttachmentStoreException.class,
                () -> store().openRange(stored.storageRef(), CONVERSATION, 0, 10));
        assertFalse(range instanceof AttachmentNotFoundException, "not 'not found'");
    }

    @Test
    default void anObjectTheBackendLostIsAStoreFailureNeverNotFound() throws Exception {
        Attachment stored = streamLarge(CONVERSATION, STREAM_USER, LARGE);
        blobBackend().clear();

        AttachmentStoreException open = assertThrows(AttachmentStoreException.class, () -> store().open(stored.storageRef(), CONVERSATION));
        assertFalse(open instanceof AttachmentNotFoundException, "the row exists, so the bytes are lost, not absent");
        assertInstanceOf(AttachmentStoreException.class, open);
    }

    // ------------------------------------------------------------ heap

    /**
     * 64 MiB through the real store and backend in a child JVM limited to 128 MiB
     * of heap: a store that held the upload or the download in a {@code byte[]}
     * runs out of memory.
     */
    @Test
    default void aLargeUploadRoundTripsWithBoundedHeapInAChildJvm() throws Exception {
        List<String> tail = heapProbeArgs();
        assumeTrue(tail != null, "no child JVM can reach this database");
        long size = 64L * 1024 * 1024;
        List<String> args = new ArrayList<>(List.of(Long.toString(size)));
        args.addAll(tail);

        ChildJvm.Result result = ChildJvm.run(AttachmentStoreHeapProbe.class, List.of("-Xmx128m"), args, Duration.ofMinutes(5));

        assertEquals(0, result.exitCode(), result.output());
        String expected = "OK " + PdfLikeStream.sha256(AttachmentStoreHeapProbe.SEED, size);
        assertTrue(result.output().lines().map(String::strip).anyMatch(expected::equals), result.output());
        assertEquals(0, blobBackend().keyCount(), "the probe deleted its blob");
    }
}
