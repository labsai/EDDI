/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentNotFoundException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.OwnerRef;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quota reservations, the same on both attachment stores — run against a real
 * database by {@code GridFsAttachmentStoreIT} and
 * {@code PostgresAttachmentStoreIT}. A reservation counts as usage of its
 * buckets for uploads, derived media and other reservations until it is
 * converted, released or expires by the database's clock; every write takes the
 * locks in one order.
 */
public interface QuotaReservationContract {

    /** The store under test, with every quota off and a 1 MB size limit. */
    IAttachmentStore store();

    /** Sets the quota limits ({@code <= 0} disables one). */
    void setQuotas(long perConversation, long bytesPerConversation, long perUser, long bytesPerUser);

    /**
     * Moves the expiry of every reservation into the past, as the database sees it.
     */
    void expireReservations();

    /** Reservation rows (documents) stored, live or not. */
    long reservationRows();

    /**
     * Stores an upload directly, without the quota check — what a write against a
     * reservation does before it converts the reservation.
     *
     * @return its storage reference
     */
    String storeUncharged(String conversationId, String userId, byte[] bytes);

    /**
     * The lifetime the store wrote for a reservation: its expiry minus the time it
     * was written, both as the database recorded them.
     */
    Duration storedLifetime(String reservationId);

    String CONVERSATION = "6aba70011a25f8be7920e101";
    String OTHER_CONVERSATION = "6aba70011a25f8be7920e102";
    String RESERVING_USER = "user-6aba70011a25f8bf";
    Duration TTL = Duration.ofMinutes(10);

    private static OwnerRef ownedBy(String conversationId) {
        return new OwnerRef(OwnerScope.CONVERSATION, conversationId, null);
    }

    private QuotaReservation reserve(String conversationId, String userId, long bytes, int count) throws AttachmentStoreException {
        return store().reserve(ownedBy(conversationId), conversationId, userId, bytes, count, TTL);
    }

    private Attachment upload(String conversationId, String userId, int size) throws AttachmentStoreException {
        return store().store(bytes(size), "text/plain", "notes.txt", conversationId, "default", userId);
    }

    private static byte[] bytes(int size) {
        return "x".repeat(size).getBytes(StandardCharsets.UTF_8);
    }

    /** Bytes with a PNG signature, so the store's MIME check accepts image/png. */
    private static byte[] png(int size) {
        byte[] bytes = bytes(size);
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }

    // ----------------------------------------------------------- what counts

    @Test
    default void aReservationCountsTowardTheConversationsBytes() throws Exception {
        setQuotas(0, 100, 0, 0);
        reserve(CONVERSATION, null, 80, 1);

        var refused = assertThrows(AttachmentQuotaExceededException.class, () -> upload(CONVERSATION, null, 21));
        assertEquals(AttachmentQuotaExceededException.SCOPE_CONVERSATION, refused.getScope());
        assertNotNull(upload(CONVERSATION, null, 20), "exactly the room the reservation left");
        assertNotNull(upload(OTHER_CONVERSATION, null, 90), "another conversation's bucket is its own");
    }

    @Test
    default void aReservationCountsTowardTheUsersBytesAcrossConversations() throws Exception {
        setQuotas(0, 0, 0, 100);
        reserve(CONVERSATION, RESERVING_USER, 80, 1);

        var refused = assertThrows(AttachmentQuotaExceededException.class, () -> upload(OTHER_CONVERSATION, RESERVING_USER, 21));
        assertEquals(AttachmentQuotaExceededException.SCOPE_USER, refused.getScope());
        assertNotNull(upload(OTHER_CONVERSATION, RESERVING_USER, 20));
    }

    @Test
    default void aReservationsFilesCountTowardTheFileLimits() throws Exception {
        setQuotas(3, 0, 0, 0);
        reserve(CONVERSATION, null, 0, 2);
        upload(CONVERSATION, null, 1);

        assertThrows(AttachmentQuotaExceededException.class, () -> upload(CONVERSATION, null, 1));
        assertThrows(AttachmentQuotaExceededException.class, () -> reserve(CONVERSATION, null, 0, 1));
        assertNotNull(reserve(CONVERSATION, null, 5, 0), "a bytes-only reservation needs no file");
    }

    @Test
    default void reservationsCountAgainstEachOtherAndAgainstUploads() throws Exception {
        setQuotas(0, 100, 0, 0);
        upload(CONVERSATION, null, 60);
        reserve(CONVERSATION, null, 30, 1);

        var refused = assertThrows(AttachmentQuotaExceededException.class, () -> reserve(CONVERSATION, null, 11, 1));
        assertEquals(AttachmentQuotaExceededException.SCOPE_CONVERSATION, refused.getScope());
        assertNotNull(reserve(CONVERSATION, null, 10, 1));
        assertEquals(2, reservationRows(), "a refused reservation holds nothing");
    }

    @Test
    default void aReservationCountsTowardTheBytesOfDerivedMedia() throws Exception {
        String root = upload(CONVERSATION, null, 10).storageRef();
        setQuotas(0, 100, 0, 0);
        reserve(CONVERSATION, null, 80, 1);

        assertThrows(AttachmentQuotaExceededException.class,
                () -> store().storeDerived(png(11), "image/png", "i.png", Derivation.of(root, Derivation.Kind.IMAGE, "i1"), CONVERSATION));
        assertNotNull(store().storeDerived(png(10), "image/png", "i.png", Derivation.of(root, Derivation.Kind.IMAGE, "i2"), CONVERSATION));
    }

    @Test
    default void theExpiryIsTheDatabasesNowPlusTheLifetime() throws Exception {
        Instant before = Instant.now();
        QuotaReservation reservation = reserve(CONVERSATION, RESERVING_USER, 10, 1);

        assertEquals(CONVERSATION, reservation.ownerKey());
        assertEquals(10, reservation.bytes());
        assertEquals(1, reservation.count());
        // Loose bounds: the database's clock, not this JVM's, set it.
        assertTrue(reservation.expiresAt().isAfter(before.plus(TTL).minusSeconds(60)), reservation.expiresAt().toString());
        assertTrue(reservation.expiresAt().isBefore(Instant.now().plus(TTL).plusSeconds(60)), reservation.expiresAt().toString());
        assertEquals(TTL, storedLifetime(reservation.id()), "exactly the lifetime asked for, on the database's clock");
    }

    // -------------------------------------------------- expiry and release

    @Test
    default void anExpiredReservationFreesItsQuotaAndIsSweptByTheNextReserve() throws Exception {
        setQuotas(0, 100, 0, 100);
        reserve(CONVERSATION, RESERVING_USER, 90, 1);
        expireReservations();

        assertNotNull(upload(CONVERSATION, RESERVING_USER, 50));
        assertEquals(1, reservationRows(), "uploads do not sweep");
        reserve(CONVERSATION, RESERVING_USER, 50, 1);
        assertEquals(1, reservationRows(), "the expired reservation was swept");
    }

    @Test
    default void everyReserveSweepsExpiredReservationsOfAnyBucketEvenWithQuotasOff() throws Exception {
        reserve(CONVERSATION, RESERVING_USER, 90, 1);
        reserve(OTHER_CONVERSATION, null, 90, 1);
        expireReservations();

        reserve(OTHER_CONVERSATION, "user-6aba70011a25f8c0", 5, 1);

        assertEquals(1, reservationRows(), "both expired rows were swept, though no quota is on and neither is in the bucket reserved in");
    }

    @Test
    default void releaseGivesTheQuotaBackAndIsIdempotent() throws Exception {
        setQuotas(0, 100, 0, 0);
        QuotaReservation reservation = reserve(CONVERSATION, null, 90, 1);

        store().release(reservation.id());
        assertDoesNotThrow(() -> store().release(reservation.id()));
        assertDoesNotThrow(() -> store().release("8d4f6f1e-0000-4000-8000-00000000beef"));
        assertEquals(0, reservationRows());
        assertNotNull(upload(CONVERSATION, null, 100));
    }

    // ------------------------------------------------------------- convert

    @Test
    default void convertingHandsTheReservationsPlaceToItsBlob() throws Exception {
        setQuotas(0, 100, 0, 100);
        QuotaReservation reservation = reserve(CONVERSATION, RESERVING_USER, 50, 1);
        String blob = storeUncharged(CONVERSATION, RESERVING_USER, bytes(40));

        store().convert(reservation.id(), blob);

        assertEquals(0, reservationRows());
        // 40 bytes used now, not 50 (the reservation) and not 90 (both).
        assertThrows(AttachmentQuotaExceededException.class, () -> upload(CONVERSATION, RESERVING_USER, 61));
        assertNotNull(upload(CONVERSATION, RESERVING_USER, 60));
    }

    @Test
    default void convertingBeforeTheBlobIsStoredIsRefusedAndKeepsTheReservation() throws Exception {
        setQuotas(0, 100, 0, 0);
        QuotaReservation reservation = reserve(CONVERSATION, null, 90, 1);

        assertThrows(AttachmentNotFoundException.class, () -> store().convert(reservation.id(), "8d4f6f1e-0000-4000-8000-0000000c0ffe"));
        assertEquals(1, reservationRows());
        assertThrows(AttachmentQuotaExceededException.class, () -> upload(CONVERSATION, null, 11), "the reservation still counts");
    }

    @Test
    default void convertingIntoABlobOfAnotherConversationOrUserIsRefused() throws Exception {
        QuotaReservation reservation = reserve(CONVERSATION, RESERVING_USER, 90, 1);
        String elsewhere = storeUncharged(OTHER_CONVERSATION, RESERVING_USER, bytes(10));
        String otherUser = storeUncharged(CONVERSATION, "user-6aba70011a25f8c0", bytes(10));

        assertThrows(AttachmentNotFoundException.class, () -> store().convert(reservation.id(), elsewhere));
        assertThrows(AttachmentNotFoundException.class, () -> store().convert(reservation.id(), otherUser));
        assertEquals(1, reservationRows());
    }

    @Test
    default void convertingAnExpiredOrReleasedReservationIsRefused() throws Exception {
        QuotaReservation expiring = reserve(CONVERSATION, null, 90, 1);
        QuotaReservation released = reserve(CONVERSATION, null, 90, 1);
        String blob = storeUncharged(CONVERSATION, null, bytes(10));
        store().release(released.id());
        expireReservations();

        var expired = assertThrows(AttachmentQuotaExceededException.class, () -> store().convert(expiring.id(), blob));
        assertEquals(AttachmentQuotaExceededException.SCOPE_RESERVATION, expired.getScope());
        var gone = assertThrows(AttachmentQuotaExceededException.class, () -> store().convert(released.id(), blob));
        assertEquals(AttachmentQuotaExceededException.SCOPE_RESERVATION, gone.getScope());
    }

    @Test
    default void aBlobLargerThanItsReservationIsRefused() throws Exception {
        QuotaReservation reservation = reserve(CONVERSATION, null, 10, 1);
        String blob = storeUncharged(CONVERSATION, null, bytes(11));

        var refused = assertThrows(AttachmentQuotaExceededException.class, () -> store().convert(reservation.id(), blob));
        assertEquals(AttachmentQuotaExceededException.SCOPE_RESERVATION, refused.getScope());
        assertEquals(1, reservationRows());
    }

    // ------------------------------------------------------- erasure, input

    @Test
    default void deletingAConversationDeletesItsReservations() throws Exception {
        reserve(CONVERSATION, RESERVING_USER, 10, 1);
        reserve(CONVERSATION, null, 10, 1);
        reserve(OTHER_CONVERSATION, RESERVING_USER, 10, 1);

        store().deleteByConversation(CONVERSATION);

        assertEquals(1, reservationRows(), "only the other conversation's stays");
    }

    @Test
    default void onlyAConversationsOwnQuotaCanBeReserved() {
        assertThrows(IllegalArgumentException.class, () -> store().reserve(new OwnerRef(OwnerScope.USER, "tenant-1", RESERVING_USER),
                CONVERSATION, RESERVING_USER, 10, 1, TTL));
        assertThrows(IllegalArgumentException.class, () -> store().reserve(ownedBy(OTHER_CONVERSATION), CONVERSATION, null, 10, 1, TTL));
        assertThrows(IllegalArgumentException.class, () -> store().reserve(ownedBy(CONVERSATION), CONVERSATION, null, 10, 1,
                QuotaReservation.MAX_TTL.plusMillis(1)));
        assertEquals(0, reservationRows());
    }

    // --------------------------------------------------------- concurrency

    /**
     * N writers at once, half reserving and half uploading, against a quota with
     * room for exactly ten: exactly ten get in, whatever the interleaving.
     */
    @RepeatedTest(20)
    default void concurrentReservationsAndUploadsNeverExceedTheQuota() throws Exception {
        setQuotas(0, 100, 0, 100);
        int writers = 20;
        AtomicInteger admitted = new AtomicInteger();
        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            boolean reserving = i % 2 == 0;
            work.add(() -> {
                try {
                    if (reserving) {
                        reserve(CONVERSATION, RESERVING_USER, 10, 1);
                    } else {
                        upload(CONVERSATION, RESERVING_USER, 10);
                    }
                    admitted.incrementAndGet();
                } catch (AttachmentQuotaExceededException refused) {
                    // expected for all but ten
                }
                return null;
            });
        }
        runAllAtOnce(work);

        assertEquals(10, admitted.get());
    }

    /**
     * Every kind of writer at once on the same buckets — reservations, uploads
     * (conversation and user) and derived media (conversation, user and source).
     * With one lock order none waits on another in a cycle. If one kind took two
     * scopes the other way round (a reservation locking user, then conversation),
     * PostgreSQL would report a deadlock and the MongoDB lease locks would wait out
     * their 15 s and refuse. Four writers of each kind: the MongoDB lease locks are
     * polled, not queued, so many more on one bucket can starve a writer past its
     * wait on a loaded machine without any cycle.
     */
    @RepeatedTest(20)
    default void writersThatNeedTheScopesInOppositeOrdersNeverDeadlock() throws Exception {
        String root = upload(CONVERSATION, RESERVING_USER, 10).storageRef();
        setQuotas(1000, 1_000_000, 1000, 1_000_000);
        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int n = i;
            work.add(() -> {
                store().release(reserve(CONVERSATION, RESERVING_USER, 10, 1).id());
                return null;
            });
            work.add(() -> {
                upload(CONVERSATION, RESERVING_USER, 10);
                return null;
            });
            work.add(() -> {
                store().storeDerived(png(20 + n), "image/png", "i.png", Derivation.of(root, Derivation.Kind.IMAGE, "i" + n), CONVERSATION);
                return null;
            });
        }
        long started = System.nanoTime();
        runAllAtOnce(work);
        // Under the 15 s a MongoDB writer waits for a lease lock before it refuses.
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(14), "no writer waited out a lock");
    }

    /**
     * Starts every task at the same moment on its own virtual thread and rethrows
     * the first failure.
     */
    private static void runAllAtOnce(List<Callable<Void>> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : work) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
    }
}
