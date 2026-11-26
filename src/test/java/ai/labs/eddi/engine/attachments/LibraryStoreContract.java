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
import ai.labs.eddi.engine.attachments.IAttachmentStore.OwnerRef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The personal library as both attachment stores (GridFS, PostgreSQL) must keep
 * it — run by an IT per database against a real one: promotion moves a file and
 * its lineage to the library, the conversation-keyed methods leave a library
 * document alone, the library's buckets, soft removal, grant eviction, erasure
 * by owner key and the reaper's queries.
 */
public interface LibraryStoreContract {

    /** The store under test, with every quota off and a 1 MB size limit. */
    IAttachmentStore store();

    /** Sets the upload quota limits ({@code <= 0} disables one). */
    void setQuotas(long perConversation, long bytesPerConversation, long perUser, long bytesPerUser);

    /** Sets the library limits. */
    void setLibraryLimits(LibraryStoreSupport.Limits limits);

    /** Every {@link AttachmentRootDeleted} announced since the test started. */
    List<AttachmentRootDeleted> rootDeletions();

    /** Every {@link AttachmentOwnerChanged} announced since the test started. */
    List<AttachmentOwnerChanged> ownerChanges();

    /** Rows (files) whose lineage root is {@code rootRef}, read under the store. */
    long lineageRows(String rootRef);

    /** New-shape grant rows on the blob. */
    long grantRowsOn(String storageRef);

    /** New-shape grant rows held by the conversation, on any blob. */
    long grantRowsHeldBy(String conversationId);

    /** The legacy-shape grantee list of the blob, as stored. */
    List<String> legacyGrantees(String storageRef);

    /**
     * Writes a legacy-shape grant only — what a node of the previous release does.
     */
    void seedLegacyGrant(String storageRef, String conversationId);

    /** Sets a library root's last access, as the reaper would find it. */
    void setLastAccessed(String storageRef, Instant when);

    /** Writes a live quota reservation row directly. */
    void seedReservation(String ownerKey, String conversationId);

    /** Live and expired reservation rows of an owner key. */
    long reservationsOf(String ownerKey);

    /**
     * Takes the store's quota lock of a conversation's bucket, as another writer
     * would, until the returned handle is closed (idempotently).
     */
    AutoCloseable holdConversationQuotaLock(String conversationId) throws Exception;

    /** Realistic ids: 24-hex conversation ids, space-tenant-shaped owner keys. */
    String ORIGIN = "6aba70011a25f8be7920e101";
    String LIB_GRANTEE = "6aba70011a25f8be7920e102";
    String LIB_STRANGER = "6aba70011a25f8be7920e103";
    String LIB_OTHER = "6aba70011a25f8be7920e104";
    String LIB_READER_1 = "6aba70011a25f8be7920e105";
    String LIB_READER_2 = "6aba70011a25f8be7920e106";
    String LIB_READER_3 = "6aba70011a25f8be7920e107";
    String LIB_USER = "user-6aba70011a25f8be";
    String LIB_KEY = "space-tenant-6aba70011a25f8be7920e1a1";
    String LIB_OTHER_KEY = "space-tenant-6aba70011a25f8be7920e1a2";
    OwnerRef LIB_MINE = new OwnerRef(OwnerScope.USER, LIB_KEY, LIB_USER);

    private static byte[] text(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png(String body) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        byte[] tail = text(body);
        byte[] bytes = new byte[signature.length + tail.length];
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        System.arraycopy(tail, 0, bytes, signature.length, tail.length);
        return bytes;
    }

    private Attachment upload(String conversationId, String body) throws AttachmentStoreException {
        return store().store(text(body), "text/plain", "report.txt", conversationId, "default", LIB_USER);
    }

    private Attachment textView(String rootRef, String unitId, String body, String requester) throws AttachmentStoreException {
        return store().storeDerived(text(body), "text/plain", "part.txt", Derivation.of(rootRef, Derivation.Kind.TEXT_PART, unitId), requester);
    }

    private Attachment imageView(String rootRef, String unitId, byte[] png, String requester) throws AttachmentStoreException {
        return store().storeDerived(png, "image/png", "image.png", Derivation.of(rootRef, Derivation.Kind.IMAGE, unitId), requester);
    }

    private static LibraryMeta meta(String slug) {
        return new LibraryMeta("Annual report", slug, null, null);
    }

    private LibraryEntry promote(String ref, String slug) throws AttachmentStoreException {
        return store().promote(ref, ORIGIN, LIB_MINE, meta(slug));
    }

    // ------------------------------------------------------------- promotion

    @Test
    default void promotionMovesTheRootAndItsLineageToTheLibraryAndAnnouncesItOnce() throws Exception {
        String root = upload(ORIGIN, "the annual report").storageRef();
        String text = textView(root, "u1", "chapter one", ORIGIN).storageRef();
        String image = imageView(root, "i1", png("a chart"), ORIGIN).storageRef();

        LibraryEntry entry = store().promote(root, ORIGIN, LIB_MINE, new LibraryMeta("Annual report 2026", "annual-report", "agent-1", 30));

        assertEquals(root, entry.storageRef());
        assertEquals("Annual report 2026", entry.title());
        assertEquals("annual-report", entry.slug());
        assertEquals(OwnerScope.USER, entry.scope());
        assertEquals(LIB_KEY, entry.ownerKey());
        assertEquals("agent-1", entry.agentScope());
        assertEquals(LIB_USER, entry.contributedBy());
        assertEquals("report.txt", entry.filename());
        assertEquals("text/plain", entry.mimeType());
        assertEquals(text("the annual report").length, entry.sizeBytes());
        assertEquals(30, entry.retentionDays());
        assertNotNull(entry.savedAt());
        assertEquals(entry.savedAt(), entry.lastAccessedAt(), "saving counts as an access");
        assertFalse(entry.removed());

        for (String ref : List.of(root, text, image)) {
            assertEquals(LIB_MINE, store().ownerOf(ref, ORIGIN), "the whole lineage moved: " + ref);
        }
        assertEquals(List.of(new AttachmentOwnerChanged(root, ORIGIN, LIB_KEY, OwnerScope.USER)), ownerChanges());
        assertTrue(rootDeletions().isEmpty(), "nothing was deleted: the file moved, nothing was copied");
        assertEquals(2, lineageRows(root));
    }

    @Test
    default void thePromotingConversationKeepsAGrantButNeitherListsNorDeletesTheDocument() throws Exception {
        String root = upload(ORIGIN, "kept").storageRef();
        String view = textView(root, "u1", "view", ORIGIN).storageRef();

        promote(root, "kept");

        assertArrayEquals(text("kept"), store().load(root, ORIGIN), "it reads it through its grant");
        assertEquals(1, grantRowsOn(root));
        assertEquals(1, grantRowsHeldBy(ORIGIN), "the grant row is the promoting conversation's");
        assertTrue(legacyGrantees(root).contains(ORIGIN), "in both shapes, like any grant");
        assertTrue(store().listByConversation(ORIGIN).isEmpty(), "no longer its own");
        assertEquals(List.of(root), store().listAccessible(ORIGIN).stream().map(Attachment::storageRef).toList());
        var denied = assertThrows(AttachmentAccessDeniedException.class, () -> store().delete(root, ORIGIN));
        assertTrue(denied.getMessage().contains("library"), denied.getMessage());
        assertThrows(AttachmentAccessDeniedException.class, () -> store().delete(view, ORIGIN), "nor a view of it");
        assertArrayEquals(text("kept"), store().load(root, ORIGIN), "still there");
        assertEquals(1, lineageRows(root));
    }

    @Test
    default void deletingTheOriginConversationKeepsTheLibraryDocumentAndRemovesOnlyItsGrant() throws Exception {
        String promoted = upload(ORIGIN, "for the library").storageRef();
        String view = textView(promoted, "u1", "its view", ORIGIN).storageRef();
        String ordinary = upload(ORIGIN, "an ordinary upload").storageRef();
        promote(promoted, "kept");

        assertEquals(1, store().deleteByConversation(ORIGIN), "only the conversation's own file");

        assertEquals(List.of(ordinary), rootDeletions().stream().map(AttachmentRootDeleted::rootRef).toList());
        assertThrows(AttachmentNotFoundException.class, () -> store().load(ordinary, LIB_OTHER), "a conversation's own file still dies with it");
        assertEquals(0, grantRowsOn(promoted), "its grant went with it");
        assertFalse(legacyGrantees(promoted).contains(ORIGIN), "in both shapes");
        assertEquals(1, lineageRows(promoted), "the library document and its view stay");
        assertEquals(List.of(promoted), store().listByOwner(LIB_KEY, null, false, 0, 10).stream().map(LibraryEntry::storageRef).toList());
        assertThrows(AttachmentAccessDeniedException.class, () -> store().load(promoted, ORIGIN),
                "the origin conversation's id is origin information only, not ownership");
        store().grantAccess(promoted, LIB_GRANTEE);
        assertArrayEquals(text("its view"), store().load(view, LIB_GRANTEE), "and the document reads as before");
    }

    @Test
    default void onlyTheOwningConversationPromotesAndOnlyAnUploadOnce() throws Exception {
        String root = upload(ORIGIN, "mine").storageRef();
        store().grantAccess(root, LIB_GRANTEE);
        String view = textView(root, "u1", "a view", ORIGIN).storageRef();

        assertThrows(AttachmentAccessDeniedException.class, () -> store().promote(root, LIB_GRANTEE, LIB_MINE, meta("x")), "a grantee");
        assertThrows(AttachmentAccessDeniedException.class, () -> store().promote(root, LIB_STRANGER, LIB_MINE, meta("x")), "a stranger");
        assertThrows(AttachmentStoreException.class, () -> store().promote(view, ORIGIN, LIB_MINE, meta("x")), "a view");
        assertThrows(AttachmentNotFoundException.class, () -> store().promote("6aba70011a25f8be7920eeee", ORIGIN, LIB_MINE, meta("x")));
        assertThrows(IllegalArgumentException.class,
                () -> store().promote(root, ORIGIN, new OwnerRef(OwnerScope.TEAM, LIB_OTHER_KEY, LIB_USER), meta("x")), "no team library yet");
        assertThrows(IllegalArgumentException.class,
                () -> store().promote(root, ORIGIN, new OwnerRef(OwnerScope.CONVERSATION, LIB_OTHER, LIB_USER), meta("x")));
        assertTrue(ownerChanges().isEmpty());
        assertEquals(OwnerScope.CONVERSATION, store().ownerOf(root, ORIGIN).scope(), "every refusal left it where it was");

        promote(root, "mine");
        assertThrows(AttachmentAccessDeniedException.class, () -> promote(root, "again"), "already in a library");
        assertEquals(1, ownerChanges().size());
    }

    // --------------------------------------------------------------- buckets

    @Test
    default void promotionMovesTheBytesFromTheUploadBucketsToTheLibrarysExactly() throws Exception {
        String root = upload(ORIGIN, "1234567890").storageRef(); // 10 bytes
        byte[] image = png("img"); // 11 bytes
        imageView(root, "i1", image, ORIGIN);
        textView(root, "u1", "x".repeat(400), ORIGIN); // never counts
        // The conversation and the user hold 21 bytes; the library takes exactly them.
        setQuotas(2, 21 + 5, 2, 21 + 5);
        setLibraryLimits(new LibraryStoreSupport.Limits(1, 20, 365, 1000));
        var tooSmall = assertThrows(AttachmentQuotaExceededException.class, () -> promote(root, "exact"));
        assertTrue(tooSmall.getMessage().contains("0 + 21 bytes"), "the root and its media view, not its text view: " + tooSmall.getMessage());
        setLibraryLimits(new LibraryStoreSupport.Limits(1, 21, 365, 1000));

        promote(root, "exact");

        // Back to zero in both upload buckets: 2 files and 26 bytes fit again.
        assertNotNull(upload(ORIGIN, "x".repeat(25)), "the conversation's and the user's bytes are free again");
        assertNotNull(upload(ORIGIN, "y"), "and so is their file count: 2 files and 26 bytes fit exactly");
        var full = assertThrows(AttachmentQuotaExceededException.class, () -> upload(ORIGIN, "z"));
        assertEquals(AttachmentQuotaExceededException.SCOPE_CONVERSATION, full.getScope());
        // The library is full to the byte and to the document.
        // Another user's conversation: the first user's upload quota is full again.
        String next = store().store(text("1"), "text/plain", "next.txt", LIB_OTHER, "default", null).storageRef();
        var library = assertThrows(AttachmentQuotaExceededException.class, () -> store().promote(next, LIB_OTHER, LIB_MINE, meta("next")));
        assertEquals(AttachmentQuotaExceededException.SCOPE_LIBRARY, library.getScope());
        assertTrue(library.getMessage().contains("1 of 1 documents"), library.getMessage());
    }

    @Test
    default void aFullLibraryRefusesWithTheNumbersAndMovesNothing() throws Exception {
        setLibraryLimits(new LibraryStoreSupport.Limits(0, 15, 365, 1000));
        promote(upload(ORIGIN, "1234567890").storageRef(), "first"); // 10 of 15 bytes
        String second = upload(ORIGIN, "123456").storageRef(); // 6 more: one too many

        var refused = assertThrows(AttachmentQuotaExceededException.class, () -> promote(second, "second"));

        assertEquals(AttachmentQuotaExceededException.SCOPE_LIBRARY, refused.getScope());
        assertTrue(refused.getMessage().contains("10 + 6 bytes") && refused.getMessage().contains("15"), refused.getMessage());
        assertEquals(OwnerScope.CONVERSATION, store().ownerOf(second, ORIGIN).scope());
        assertEquals(List.of(second), store().listByConversation(ORIGIN).stream().map(Attachment::storageRef).toList());
        assertEquals(1, ownerChanges().size(), "only the first moved");
        assertEquals(1, store().listByOwner(LIB_KEY, null, true, 0, 10).size());
    }

    @Test
    default void aViewOfALibraryDocumentBelongsToTheLibraryAndItsMediaUseTheLibrarysBytes() throws Exception {
        String root = upload(ORIGIN, "1234567890").storageRef(); // 10 bytes
        promote(root, "doc");
        byte[] image = png("img"); // 11 bytes
        setLibraryLimits(new LibraryStoreSupport.Limits(50, 10 + image.length, 365, 1000));
        setQuotas(0, 1, 0, 1); // the upload buckets have no room at all

        Attachment view = imageView(root, "i1", image, ORIGIN);

        assertEquals(LIB_MINE, store().ownerOf(view.storageRef(), ORIGIN), "a view stored after the move has the new owner");
        var full = assertThrows(AttachmentQuotaExceededException.class, () -> imageView(root, "i2", png("i2"), ORIGIN));
        assertEquals(AttachmentQuotaExceededException.SCOPE_LIBRARY, full.getScope(), "charged to the library, not the conversation");
        assertNotNull(textView(root, "u1", "x".repeat(500), ORIGIN), "text views count against no quota");
        assertEquals(0, store().deleteByConversation(ORIGIN));
        assertEquals(2, lineageRows(root), "the views stay with the library document");
    }

    @Test
    default void aViewBeingStoredWhileItsRootIsPromotedFollowsTheNewOwner() throws Exception {
        String root = upload(ORIGIN, "1234567890").storageRef(); // 10 bytes
        byte[] image = png("mid"); // 11 bytes
        setQuotas(0, 1_000_000, 0, 0); // so a media view of a conversation's file takes the conversation's lock
        setLibraryLimits(new LibraryStoreSupport.Limits(50, 10 + image.length, 365, 1000));
        ExecutorService writer = Executors.newSingleThreadExecutor();
        AutoCloseable held = holdConversationQuotaLock(ORIGIN);
        try {
            // The writer reads the root as the conversation's and waits for that lock;
            // meanwhile the root moves to the library, which takes no conversation lock.
            Future<Attachment> pending = writer.submit(() -> imageView(root, "i1", image, ORIGIN));
            Thread.sleep(1500);
            promote(root, "moved");
            held.close();

            Attachment view = pending.get(60, TimeUnit.SECONDS);

            assertEquals(LIB_MINE, store().ownerOf(view.storageRef(), ORIGIN));
            var full = assertThrows(AttachmentQuotaExceededException.class, () -> imageView(root, "i2", png("i2"), ORIGIN));
            assertEquals(AttachmentQuotaExceededException.SCOPE_LIBRARY, full.getScope(), "the view was charged to the library: it is full");
            assertEquals(0, store().deleteByConversation(ORIGIN));
            assertEquals(1, lineageRows(root), "stored under the new owner, it stays with the library document");
        } finally {
            held.close();
            writer.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- grants

    @Test
    default void pastTheCapTheOldestGrantOfALibraryDocumentIsEvictedNeverTheNewest() throws Exception {
        setLibraryLimits(new LibraryStoreSupport.Limits(50, 0, 365, 2));
        String root = upload(ORIGIN, "popular").storageRef();
        promote(root, "popular"); // ORIGIN's grant

        store().grantAccess(root, LIB_READER_1);
        assertArrayEquals(text("popular"), store().load(root, ORIGIN), "two grants, within the cap");
        store().grantAccess(root, LIB_READER_2);

        assertThrows(AttachmentAccessDeniedException.class, () -> store().load(root, ORIGIN), "the oldest grant went");
        assertTrue(store().listAccessible(ORIGIN).isEmpty(), "and the conversation it came from no longer lists it as its own");
        assertFalse(legacyGrantees(root).contains(ORIGIN), "in both shapes, or it would still read");
        assertArrayEquals(text("popular"), store().load(root, LIB_READER_1));
        assertArrayEquals(text("popular"), store().load(root, LIB_READER_2), "never the one just written");
        assertEquals(2, grantRowsOn(root));
        store().grantAccess(root, ORIGIN);
        assertArrayEquals(text("popular"), store().load(root, ORIGIN), "an evicted conversation simply opens it again");
    }

    @Test
    default void aLegacyOnlyGrantIsEvictedFirstAndAConversationsFileIsNeverEvicted() throws Exception {
        setLibraryLimits(new LibraryStoreSupport.Limits(50, 0, 365, 2));
        String root = upload(ORIGIN, "doc").storageRef();
        promote(root, "doc");
        store().grantAccess(root, LIB_READER_1);
        seedLegacyGrant(root, LIB_READER_2); // an old node's grant: of unknown age

        store().grantAccess(root, LIB_READER_3);

        assertThrows(AttachmentAccessDeniedException.class, () -> store().load(root, LIB_READER_2));
        assertThrows(AttachmentAccessDeniedException.class, () -> store().load(root, ORIGIN), "then the oldest row");
        assertArrayEquals(text("doc"), store().load(root, LIB_READER_1));
        assertArrayEquals(text("doc"), store().load(root, LIB_READER_3));

        String ordinary = upload(LIB_OTHER, "ordinary").storageRef();
        for (String reader : List.of(LIB_READER_1, LIB_READER_2, LIB_READER_3)) {
            store().grantAccess(ordinary, reader);
        }
        for (String reader : List.of(LIB_READER_1, LIB_READER_2, LIB_READER_3)) {
            assertArrayEquals(text("ordinary"), store().load(ordinary, reader), "the cap is a library document's only");
        }
    }

    // ------------------------------------------------------- listing, removal

    @Test
    default void theLibraryListsByAgentScopeMostRecentlyAccessedFirstAndInPages() throws Exception {
        String forA = upload(ORIGIN, "a").storageRef();
        String forB = upload(ORIGIN, "b").storageRef();
        String global = upload(ORIGIN, "g").storageRef();
        store().promote(forA, ORIGIN, LIB_MINE, new LibraryMeta("For A", "for-a", "agent-a", null));
        store().promote(forB, ORIGIN, LIB_MINE, new LibraryMeta("For B", "for-b", "agent-b", null));
        store().promote(global, ORIGIN, LIB_MINE, new LibraryMeta("Global", "global", null, null));
        Instant now = Instant.now();
        setLastAccessed(forA, now.minus(Duration.ofHours(3)));
        setLastAccessed(forB, now.minus(Duration.ofHours(2)));
        setLastAccessed(global, now.minus(Duration.ofHours(1)));

        assertEquals(List.of(global, forA), refs(store().listByOwner(LIB_KEY, "agent-a", false, 0, 10)), "its own and the global ones");
        assertEquals(List.of(global, forB, forA), refs(store().listByOwner(LIB_KEY, null, false, 0, 10)));
        assertEquals(List.of(forB), refs(store().listByOwner(LIB_KEY, null, false, 1, 1)), "a page");
        assertTrue(store().listByOwner(LIB_OTHER_KEY, null, true, 0, 10).isEmpty());
        assertTrue(store().listByOwner(ORIGIN, null, true, 0, 10).isEmpty(), "a conversation id is no library");

        store().touchAccess(forA);
        assertEquals(forA, refs(store().listByOwner(LIB_KEY, null, false, 0, 10)).get(0), "opened just now");
    }

    @Test
    default void getLibraryEntryAnswersOnlyForItsOwnersKey() throws Exception {
        String root = upload(ORIGIN, "doc").storageRef();
        String ordinary = upload(ORIGIN, "ordinary").storageRef();
        promote(root, "doc");

        assertEquals("doc", store().getLibraryEntry(LIB_KEY, root).orElseThrow().slug());
        assertTrue(store().getLibraryEntry(LIB_OTHER_KEY, root).isEmpty());
        assertTrue(store().getLibraryEntry(ORIGIN, ordinary).isEmpty(), "a conversation's file is no library entry");
        assertTrue(store().getLibraryEntry(LIB_KEY, ordinary).isEmpty());
    }

    @Test
    default void slugsAreUniquePerOwnerOnly() throws Exception {
        LibraryEntry first = promote(upload(ORIGIN, "one").storageRef(), "report");
        LibraryEntry second = promote(upload(ORIGIN, "two").storageRef(), "report");
        LibraryEntry third = promote(upload(ORIGIN, "three").storageRef(), "report");
        LibraryEntry elsewhere = store().promote(upload(LIB_OTHER, "four").storageRef(), LIB_OTHER,
                new OwnerRef(OwnerScope.USER, LIB_OTHER_KEY, null),
                meta("report"));

        assertEquals(List.of("report", "report-2", "report-3"), List.of(first.slug(), second.slug(), third.slug()));
        assertEquals("report", elsewhere.slug(), "another library has its own names");
        assertNull(elsewhere.contributedBy(), "no user known");
    }

    @Test
    default void softRemovalHidesTheDocumentFromItsListingsUntilItIsRestored() throws Exception {
        String root = upload(ORIGIN, "doc").storageRef();
        promote(root, "doc");
        Instant when = Instant.parse("2026-11-01T10:00:00Z");

        assertFalse(store().softRemove(LIB_OTHER_KEY, root, when), "only its owner removes it");
        assertTrue(store().softRemove(LIB_KEY, root, when));
        assertFalse(store().softRemove(LIB_KEY, root, when), "once");

        assertTrue(store().listByOwner(LIB_KEY, null, false, 0, 10).isEmpty());
        LibraryEntry removed = store().listByOwner(LIB_KEY, null, true, 0, 10).get(0);
        assertEquals(when, removed.removedAt());
        assertTrue(store().listAccessible(ORIGIN).isEmpty(), "nor do its grantees list it");
        assertEquals(when, store().getLibraryEntry(LIB_KEY, root).orElseThrow().removedAt());

        assertFalse(store().restore(LIB_OTHER_KEY, root));
        assertTrue(store().restore(LIB_KEY, root));
        assertFalse(store().restore(LIB_KEY, root), "once");
        assertEquals(List.of(root), refs(store().listByOwner(LIB_KEY, null, false, 0, 10)));
        assertEquals(List.of(root), store().listAccessible(ORIGIN).stream().map(Attachment::storageRef).toList());
    }

    // -------------------------------------------------------------- deletion

    @Test
    default void deleteByOwnerLeavesNothingOfTheLibraryAndAnnouncesEachDocument() throws Exception {
        String first = upload(ORIGIN, "first").storageRef();
        String second = upload(ORIGIN, "second").storageRef();
        textView(first, "u1", "view one", ORIGIN);
        imageView(second, "i1", png("view two"), ORIGIN);
        promote(first, "first");
        promote(second, "second");
        store().grantAccess(first, LIB_READER_1);
        String someoneElses = upload(LIB_OTHER, "someone else's").storageRef();
        seedReservation(LIB_KEY, null);
        seedReservation(LIB_OTHER, LIB_OTHER); // a conversation's reservation

        assertEquals(0, store().deleteByOwner(LIB_OTHER), "a conversation id deletes nothing");
        assertEquals(1, reservationsOf(LIB_OTHER), "not even its reservations");
        assertEquals(2, store().deleteByOwner(LIB_KEY));

        for (String root : List.of(first, second)) {
            assertEquals(0, lineageRows(root));
            assertEquals(0, grantRowsOn(root));
            assertThrows(AttachmentNotFoundException.class, () -> store().load(root, LIB_READER_1));
        }
        assertEquals(0, reservationsOf(LIB_KEY));
        assertTrue(store().listByOwner(LIB_KEY, null, true, 0, 10).isEmpty());
        assertEquals(List.of(first, second), rootDeletions().stream().map(AttachmentRootDeleted::rootRef).sorted(
                (a, b) -> Integer.compare(List.of(first, second).indexOf(a), List.of(first, second).indexOf(b))).toList());
        for (AttachmentRootDeleted deleted : rootDeletions()) {
            assertEquals(OwnerScope.USER, deleted.scope());
            assertEquals(LIB_KEY, deleted.ownerKey());
        }
        assertArrayEquals(text("someone else's"), store().load(someoneElses, LIB_OTHER), "another owner's file stays");
        assertEquals(0, store().deleteByOwner(LIB_KEY), "idempotent");
    }

    @Test
    default void deleteLibraryDocumentDeletesOneDocumentOfItsOwner() throws Exception {
        String kept = upload(ORIGIN, "kept").storageRef();
        String gone = upload(ORIGIN, "gone").storageRef();
        textView(gone, "u1", "its view", ORIGIN);
        promote(kept, "kept");
        promote(gone, "gone");
        String ordinary = upload(ORIGIN, "ordinary").storageRef();

        assertFalse(store().deleteLibraryDocument(LIB_OTHER_KEY, gone), "only for its owner");
        assertFalse(store().deleteLibraryDocument(ORIGIN, ordinary), "never a conversation's file");
        assertTrue(store().deleteLibraryDocument(LIB_KEY, gone));

        assertEquals(0, lineageRows(gone));
        assertEquals(List.of(gone), rootDeletions().stream().map(AttachmentRootDeleted::rootRef).toList());
        assertEquals(List.of(kept), refs(store().listByOwner(LIB_KEY, null, true, 0, 10)));
        assertArrayEquals(text("ordinary"), store().load(ordinary, ORIGIN));
        assertFalse(store().deleteLibraryDocument(LIB_KEY, gone), "once");
    }

    // ---------------------------------------------------------------- reaper

    @Test
    default void retentionRunsOutAfterTheLastAccessByTheDocumentsOwnRetentionOrTheDefault() throws Exception {
        setLibraryLimits(new LibraryStoreSupport.Limits(50, 0, 30, 1000));
        String oneDay = upload(ORIGIN, "one day").storageRef();
        String byDefault = upload(ORIGIN, "default").storageRef();
        String forever = upload(ORIGIN, "forever").storageRef();
        store().promote(oneDay, ORIGIN, LIB_MINE, new LibraryMeta("One day", "one-day", null, 1));
        store().promote(byDefault, ORIGIN, LIB_MINE, new LibraryMeta("Default", "default", null, null));
        store().promote(forever, ORIGIN, LIB_MINE, new LibraryMeta("Forever", "forever", null, 0));
        Instant now = Instant.now();

        assertTrue(store().expired(now, 10).isEmpty(), "just saved");
        for (String ref : List.of(oneDay, byDefault, forever)) {
            setLastAccessed(ref, now.minus(Duration.ofDays(2)));
        }
        assertEquals(List.of(oneDay), refs(store().expired(now, 10)));
        setLastAccessed(byDefault, now.minus(Duration.ofDays(31)));
        setLastAccessed(forever, now.minus(Duration.ofDays(3650)));
        assertEquals(List.of(byDefault, oneDay), refs(store().expired(now, 10)), "oldest access first; zero never runs out");
        assertEquals(List.of(byDefault), refs(store().expired(now, 1)));

        setLibraryLimits(new LibraryStoreSupport.Limits(50, 0, 0, 1000));
        assertEquals(List.of(oneDay), refs(store().expired(now, 10)), "a default of zero keeps the documents without their own");
    }

    @Test
    default void removedBeforeListsTheDocumentsRemovedLongestAgo() throws Exception {
        String early = upload(ORIGIN, "early").storageRef();
        String late = upload(ORIGIN, "late").storageRef();
        String kept = upload(ORIGIN, "kept").storageRef();
        promote(early, "early");
        promote(late, "late");
        promote(kept, "kept");
        Instant now = Instant.now();
        store().softRemove(LIB_KEY, early, now.minus(Duration.ofDays(40)));
        store().softRemove(LIB_KEY, late, now.minus(Duration.ofDays(35)));

        assertEquals(List.of(early, late), refs(store().removedBefore(now.minus(Duration.ofDays(30)), 10)));
        assertEquals(List.of(early), refs(store().removedBefore(now.minus(Duration.ofDays(38)), 10)));
        assertEquals(List.of(early), refs(store().removedBefore(now, 1)));
    }

    // ------------------------------------------------------------------ move

    @Test
    default void moveOwnerReKeysTheLineageKeepsTheGrantsAndAnnouncesIt() throws Exception {
        String root = upload(ORIGIN, "moving").storageRef();
        String view = textView(root, "u1", "its view", ORIGIN).storageRef();
        promote(root, "report");
        store().promote(upload(LIB_OTHER, "there").storageRef(), LIB_OTHER, new OwnerRef(OwnerScope.USER, LIB_OTHER_KEY, null), meta("report"));
        OwnerRef theirs = new OwnerRef(OwnerScope.USER, LIB_OTHER_KEY, null);

        assertThrows(AttachmentNotFoundException.class, () -> store().moveOwner(root, LIB_OTHER_KEY, LIB_MINE), "not from a key that lacks it");
        assertThrows(IllegalArgumentException.class, () -> store().moveOwner(root, LIB_KEY, LIB_MINE), "not to itself");
        assertThrows(IllegalArgumentException.class,
                () -> store().moveOwner(root, LIB_KEY, new OwnerRef(OwnerScope.TEAM, LIB_OTHER_KEY, null)), "no team library yet");
        LibraryEntry moved = store().moveOwner(root, LIB_KEY, theirs);

        assertEquals(LIB_OTHER_KEY, moved.ownerKey());
        assertEquals("report-2", moved.slug(), "the slug is suffixed where it is taken");
        OwnerRef viewOwner = store().ownerOf(view, ORIGIN);
        assertEquals(OwnerScope.USER, viewOwner.scope());
        assertEquals(LIB_OTHER_KEY, viewOwner.ownerKey(), "the lineage moved with it");
        assertArrayEquals(text("its view"), store().load(view, ORIGIN), "the grants stay");
        assertEquals(view, textView(root, "u1", "its view", ORIGIN).storageRef(),
                "the view is keyed by its new owner: the same bytes deduplicate against it");
        assertEquals(1, lineageRows(root));
        assertTrue(store().listByOwner(LIB_KEY, null, true, 0, 10).isEmpty());
        assertEquals(new AttachmentOwnerChanged(root, LIB_KEY, LIB_OTHER_KEY, OwnerScope.USER), ownerChanges().get(ownerChanges().size() - 1));
    }

    private static List<String> refs(List<LibraryEntry> entries) {
        return entries.stream().map(LibraryEntry::storageRef).toList();
    }
}
