/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentAccessDeniedException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentNotFoundException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour both attachment stores (GridFS, PostgreSQL) must share — run by
 * an IT per database against a real one. Covers ownership and grants, the
 * dual-shape grants of the release that moved them into
 * {@code attachment_grants} (written in both shapes, read as the union), their
 * pruning, quotas and the legacy-grant backfill.
 * <p>
 * The hooks below reach under the store to seed and inspect the legacy shape
 * and the grant rows directly; they are what an old node, or a node of this
 * release, would see.
 */
public interface AttachmentStoreContract {

    /** The store under test, with every quota off and a 1 MB size limit. */
    IAttachmentStore store();

    /** The same store, as the backfill sees it. */
    IAttachmentGrantMigration migration();

    /** Sets the quota limits ({@code <= 0} disables one). */
    void setQuotas(long perConversation, long bytesPerConversation, long perUser, long bytesPerUser);

    /** New-shape grant rows on the blob. */
    long grantRowsOn(String storageRef);

    /** New-shape grant rows held by the conversation, on any blob. */
    long grantRowsHeldBy(String conversationId);

    /** The legacy-shape grantee list of the blob, as stored. */
    List<String> legacyGrantees(String storageRef);

    /** How many blobs list the conversation in their legacy grantee list. */
    long legacyGrantsHeldBy(String conversationId);

    /**
     * Writes a legacy-shape grant only — what a node of the previous release does.
     */
    void seedLegacyGrant(String storageRef, String conversationId);

    /**
     * Removes every new-shape grant row — what a node of the previous release sees,
     * which reads only the legacy shape.
     */
    void dropGrantRows();

    /** Writes a new-shape grant row directly, whether or not its blob exists. */
    void seedGrantRow(String storageRef, String conversationId);

    /** The real migration log of this database, emptied before each test. */
    IMigrationLogStore migrationLog();

    /** Creates a group conversation in the real store and returns its id. */
    String createGroupConversation() throws Exception;

    /**
     * A backfill over the real conversation and group-conversation stores of this
     * database.
     */
    AttachmentGrantBackfill backfillWithRealStores();

    /** Realistic ids: 24-hex, the shape the ids take in production. */
    String OWNER = "6aba70011a25f8be7920e001";
    String GRANTEE = "6aba70011a25f8be7920e002";
    String STRANGER = "6aba70011a25f8be7920e003";
    String OTHER_OWNER = "6aba70011a25f8be7920e004";
    String GONE = "6aba70011a25f8be7920e005";
    String USER = "user-6aba70011a25f8be";

    private Attachment upload(String conversationId, String text) throws AttachmentStoreException {
        return store().store(text.getBytes(StandardCharsets.UTF_8), "text/plain", "notes.txt", conversationId, "default", null);
    }

    private static Set<String> refs(List<Attachment> attachments) {
        Set<String> refs = new LinkedHashSet<>();
        attachments.forEach(a -> refs.add(a.storageRef()));
        return refs;
    }

    // ------------------------------------------------------------ round trip

    @Test
    default void storeAndLoadRoundTripsBytesAndMetadata() throws Exception {
        Attachment stored = upload(OWNER, "hello world");

        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), store().load(stored.storageRef(), OWNER));
        Attachment meta = store().getMetadata(stored.storageRef(), OWNER);
        assertEquals(stored.storageRef(), meta.storageRef());
        assertEquals("notes.txt", meta.filename());
        assertEquals("text/plain", meta.mimeType());
        assertEquals(11, meta.sizeBytes());
        assertEquals(OWNER, meta.conversationId());
    }

    @Test
    default void anUnknownRefIsNotFound() {
        assertThrows(AttachmentNotFoundException.class, () -> store().load("8d4f6f1e-0000-4000-8000-000000000000", OWNER));
        assertThrows(AttachmentNotFoundException.class, () -> store().getMetadata("8d4f6f1e-0000-4000-8000-000000000000", OWNER));
        assertThrows(AttachmentNotFoundException.class, () -> store().grantAccess("8d4f6f1e-0000-4000-8000-000000000000", GRANTEE));
    }

    // ----------------------------------------------------- owner/grantee/stranger

    @Test
    default void ownerAndGranteeReadAndAStrangerIsDenied() throws Exception {
        Attachment stored = upload(OWNER, "shared");
        String ref = stored.storageRef();

        assertThrows(AttachmentAccessDeniedException.class, () -> store().load(ref, GRANTEE), "no grant yet");

        store().grantAccess(ref, GRANTEE);

        assertArrayEquals("shared".getBytes(StandardCharsets.UTF_8), store().load(ref, GRANTEE));
        assertEquals(OWNER, store().getMetadata(ref, GRANTEE).conversationId());
        assertThrows(AttachmentAccessDeniedException.class, () -> store().load(ref, STRANGER));
        assertThrows(AttachmentAccessDeniedException.class, () -> store().getMetadata(ref, STRANGER));
        assertThrows(AttachmentAccessDeniedException.class, () -> store().load(ref, null));
    }

    @Test
    default void aGrantIsWrittenInBothShapes() throws Exception {
        String ref = upload(OWNER, "both").storageRef();

        store().grantAccess(ref, GRANTEE);

        assertEquals(1, grantRowsOn(ref));
        assertEquals(List.of(GRANTEE), legacyGrantees(ref));
    }

    @Test
    default void grantingTwiceIsIdempotentInBothShapes() throws Exception {
        String ref = upload(OWNER, "twice").storageRef();

        store().grantAccess(ref, GRANTEE);
        store().grantAccess(ref, GRANTEE);

        assertEquals(1, grantRowsOn(ref));
        assertEquals(List.of(GRANTEE), legacyGrantees(ref));
    }

    // ---------------------------------------------------- rolling upgrade

    @Test
    default void aGrantMadeHereAuthorizesThroughTheLegacyReadPath() throws Exception {
        String ref = upload(OWNER, "new-to-old").storageRef();
        store().grantAccess(ref, GRANTEE);

        // A node of the previous release reads only the legacy shape.
        dropGrantRows();

        assertArrayEquals("new-to-old".getBytes(StandardCharsets.UTF_8), store().load(ref, GRANTEE));
        assertTrue(refs(store().listAccessible(GRANTEE)).contains(ref));
    }

    @Test
    default void aLegacyOnlyGrantStillAuthorizes() throws Exception {
        String ref = upload(OWNER, "old-to-new").storageRef();

        // Written by a node of the previous release: legacy shape only.
        seedLegacyGrant(ref, GRANTEE);

        assertEquals(0, grantRowsOn(ref));
        assertArrayEquals("old-to-new".getBytes(StandardCharsets.UTF_8), store().load(ref, GRANTEE));
        assertEquals(OWNER, store().getMetadata(ref, GRANTEE).conversationId());
        assertTrue(refs(store().listAccessible(GRANTEE)).contains(ref));
    }

    @Test
    default void aNewShapeOnlyGrantAuthorizes() throws Exception {
        String ref = upload(OWNER, "rows-only").storageRef();

        assertTrue(migration().copyGrant(ref, GRANTEE));

        assertTrue(legacyGrantees(ref).isEmpty());
        assertArrayEquals("rows-only".getBytes(StandardCharsets.UTF_8), store().load(ref, GRANTEE));
        assertEquals(List.of(ref), List.copyOf(refs(store().listAccessible(GRANTEE))));
    }

    // -------------------------------------------------------------- listing

    @Test
    default void listingSeparatesOwnedFromAccessibleAndListsEachBlobOnce() throws Exception {
        String own = upload(GRANTEE, "mine").storageRef();
        String shared = upload(OWNER, "theirs").storageRef();
        String foreign = upload(OTHER_OWNER, "not shared").storageRef();
        store().grantAccess(shared, GRANTEE);

        assertEquals(Set.of(own), refs(store().listByConversation(GRANTEE)));
        List<Attachment> accessible = store().listAccessible(GRANTEE);
        assertEquals(2, accessible.size(), "granted in both shapes, still listed once: " + accessible);
        assertEquals(Set.of(own, shared), refs(accessible));
        assertFalse(refs(accessible).contains(foreign));
        Attachment sharedEntry = accessible.stream().filter(a -> a.storageRef().equals(shared)).findFirst().orElseThrow();
        assertEquals(OWNER, sharedEntry.conversationId(), "the owner is reported, not the grantee");
    }

    // ------------------------------------------------------------- deletion

    @Test
    default void onlyTheOwnerDeletesAndTheGrantsOnTheBlobGoWithIt() throws Exception {
        String ref = upload(OWNER, "doomed").storageRef();
        store().grantAccess(ref, GRANTEE);

        assertThrows(AttachmentAccessDeniedException.class, () -> store().delete(ref, GRANTEE), "a grantee cannot delete");
        assertThrows(AttachmentAccessDeniedException.class, () -> store().delete(ref, STRANGER));

        assertTrue(store().delete(ref, OWNER));

        assertEquals(0, grantRowsOn(ref), "a grant never outlives its blob");
        assertThrows(AttachmentNotFoundException.class, () -> store().load(ref, GRANTEE));
        assertFalse(store().delete(ref, OWNER), "already gone");
    }

    @Test
    default void deletingTheOwnersConversationRemovesItsBlobsAndTheGrantsOnThem() throws Exception {
        String a = upload(OWNER, "a").storageRef();
        String b = upload(OWNER, "b").storageRef();
        String kept = upload(OTHER_OWNER, "kept").storageRef();
        store().grantAccess(a, GRANTEE);
        store().grantAccess(kept, GRANTEE);

        assertEquals(2, store().deleteByConversation(OWNER));

        assertEquals(0, grantRowsOn(a));
        assertEquals(0, grantRowsOn(b));
        assertTrue(store().listByConversation(OWNER).isEmpty());
        assertEquals(Set.of(kept), refs(store().listAccessible(GRANTEE)), "grants on other blobs are untouched");
    }

    @Test
    default void deletingAGranteesConversationPrunesTheGrantsItHeldInBothShapes() throws Exception {
        String viaRows = upload(OWNER, "granted now").storageRef();
        String viaLegacy = upload(OTHER_OWNER, "granted by an old node").storageRef();
        store().grantAccess(viaRows, GRANTEE);
        seedLegacyGrant(viaLegacy, GRANTEE);
        store().grantAccess(viaRows, STRANGER);

        assertEquals(0, store().deleteByConversation(GRANTEE), "the grantee owns no blob");

        assertEquals(0, grantRowsHeldBy(GRANTEE), "no grant row mentions the deleted conversation");
        assertEquals(0, legacyGrantsHeldBy(GRANTEE), "and neither does the legacy shape");
        assertThrows(AttachmentAccessDeniedException.class, () -> store().load(viaRows, GRANTEE));
        assertThrows(AttachmentAccessDeniedException.class, () -> store().load(viaLegacy, GRANTEE));
        // The owners and the other grantee are untouched.
        assertArrayEquals("granted now".getBytes(StandardCharsets.UTF_8), store().load(viaRows, OWNER));
        assertArrayEquals("granted now".getBytes(StandardCharsets.UTF_8), store().load(viaRows, STRANGER));
        assertEquals(List.of(STRANGER), legacyGrantees(viaRows));
    }

    // --------------------------------------------------------------- quotas

    @Test
    default void theConversationQuotaRefusesWithItsScope() throws Exception {
        setQuotas(1, 0, 0, 0);
        upload(OWNER, "first");

        var refused = assertThrows(AttachmentQuotaExceededException.class, () -> upload(OWNER, "second"));
        assertEquals(AttachmentQuotaExceededException.SCOPE_CONVERSATION, refused.getScope());
        assertNotNull(upload(OTHER_OWNER, "another conversation has its own quota"));
    }

    @Test
    default void theUserQuotaRefusesWithItsScopeAcrossConversations() throws Exception {
        setQuotas(0, 0, 0, 10);
        store().store("12345678".getBytes(StandardCharsets.UTF_8), "text/plain", "a.txt", OWNER, "default", USER);

        var refused = assertThrows(AttachmentQuotaExceededException.class,
                () -> store().store("12345".getBytes(StandardCharsets.UTF_8), "text/plain", "b.txt", OTHER_OWNER, "default", USER));
        assertEquals(AttachmentQuotaExceededException.SCOPE_USER, refused.getScope());
    }

    // ------------------------------------------------------------- backfill

    private AttachmentGrantBackfill backfill() {
        return new AttachmentGrantBackfill(migration(), migrationLog(), null, null);
    }

    private boolean marked() {
        return migrationLog().readMigrationLog(AttachmentGrantBackfill.MIGRATION_KEY) != null;
    }

    @Test
    default void theBackfillCopiesLegacyGrantsAndSkipsGranteesThatNoLongerExist() throws Exception {
        String ref = upload(OWNER, "legacy").storageRef();
        seedLegacyGrant(ref, GRANTEE);
        seedLegacyGrant(ref, GONE);

        AttachmentGrantBackfill.Result result = backfill().run(id -> !GONE.equals(id), 500, 10);

        assertTrue(result.complete());
        assertEquals(1, result.grantsCopied());
        assertEquals(1, result.granteesSkipped());
        assertEquals(1, grantRowsHeldBy(GRANTEE));
        assertEquals(0, grantRowsHeldBy(GONE), "a dangling grant is not re-created");
        assertTrue(marked(), "completion is recorded");
        assertEquals(1, grantRowsOn(ref));
        assertEquals(List.of(GRANTEE, GONE), legacyGrantees(ref), "the legacy shape is left as it was for this release");
    }

    @Test
    default void theBackfillChecksGranteesAgainstTheRealStores() throws Exception {
        String group = createGroupConversation();
        String ref = upload(OWNER, "shared with a group").storageRef();
        seedLegacyGrant(ref, group);
        seedLegacyGrant(ref, GONE);

        AttachmentGrantBackfill real = backfillWithRealStores();
        AttachmentGrantBackfill.Result result = real.run(real::granteeExists, 500, 10);

        assertTrue(result.complete(), result.toString());
        assertEquals(1, grantRowsHeldBy(group), "an existing group conversation keeps its grant");
        assertEquals(0, grantRowsHeldBy(GONE), "an id that names nothing is skipped");
    }

    @Test
    default void theBackfillIsResumableAcrossStartsAndIdempotent() throws Exception {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String ref = upload(OWNER, "blob " + i).storageRef();
            seedLegacyGrant(ref, GRANTEE);
            refs.add(ref);
        }
        upload(OWNER, "never granted");

        // Two pages of two: cut short, no marker; the cursor is kept in the store.
        AttachmentGrantBackfill.Result first = backfill().run(id -> true, 2, 2);
        assertFalse(first.complete());
        assertEquals(4, first.grantsCopied());
        assertFalse(marked());

        // The next start — a new instance — continues from the kept cursor.
        AttachmentGrantBackfill.Result second = backfill().run(id -> true, 2, 10);
        assertTrue(second.complete());
        assertEquals(1, second.blobsRead(), "resumed after the first four");
        assertEquals(1, second.grantsCopied());
        assertEquals(5, grantRowsHeldBy(GRANTEE));

        // And the start after that runs the whole catch-up again: nothing new, and
        // no grantee lookup for a grant already copied — a lookup here would fail.
        AttachmentGrantBackfill.Result again = backfill().run(id -> {
            throw new IllegalStateException("no lookup expected for a copied grant");
        }, 2, 10);
        assertTrue(again.complete(), again.toString());
        assertEquals(0, again.lookupsFailed());
        assertEquals(0, again.grantsCopied());
        assertEquals(5, again.grantsAlreadyPresent());
        for (String ref : refs) {
            assertEquals(1, grantRowsOn(ref));
        }
    }

    @Test
    default void aLegacyOnlyGrantWrittenAfterCompletionIsCopiedOnTheNextStart() throws Exception {
        String first = upload(OWNER, "first").storageRef();
        seedLegacyGrant(first, GRANTEE);
        assertTrue(backfill().run(id -> true, 500, 10).complete());
        assertTrue(marked());

        // A node of the previous release, still running, grants in the legacy shape
        // only.
        String later = upload(OTHER_OWNER, "later").storageRef();
        seedLegacyGrant(later, GRANTEE);

        AttachmentGrantBackfill.Result catchUp = backfill().run(id -> true, 500, 10);

        assertEquals(1, catchUp.grantsCopied(), "the marker does not stop the catch-up");
        assertEquals(1, grantRowsOn(later));
    }

    @Test
    default void aFailedLookupSkipsThatGrantOnlyAndKeepsThePassIncomplete() throws Exception {
        String ref = upload(OWNER, "two grantees").storageRef();
        seedLegacyGrant(ref, STRANGER);
        seedLegacyGrant(ref, GRANTEE);

        AttachmentGrantBackfill.Result result = backfill().run(id -> {
            if (STRANGER.equals(id)) {
                throw new IllegalStateException("database unreachable");
            }
            return true;
        }, 500, 10);

        assertEquals(1, result.lookupsFailed());
        assertEquals(1, grantRowsHeldBy(GRANTEE), "the other grantee on the same blob is still copied");
        assertEquals(0, grantRowsHeldBy(STRANGER), "could not tell is never copied");
        assertFalse(result.complete());
        assertFalse(marked(), "a pass with a failed lookup is not complete");
    }

    @Test
    default void aGranteeThatDisappearsDuringTheCopyIsTakenBack() throws Exception {
        String ref = upload(OWNER, "erased meanwhile").storageRef();
        seedLegacyGrant(ref, GRANTEE);
        int[] calls = {0};

        // Exists at the first check, gone at the re-check after the write.
        backfill().run(id -> calls[0]++ == 0, 500, 10);

        assertEquals(0, grantRowsHeldBy(GRANTEE));
    }

    @Test
    default void copyingAGrantForADeletedBlobWritesNothing() throws Exception {
        String ref = upload(OWNER, "short-lived").storageRef();
        assertTrue(store().delete(ref, OWNER));

        assertFalse(migration().copyGrant(ref, GRANTEE));
        assertEquals(0, grantRowsOn(ref));
    }

    @Test
    default void grantRowsWhoseBlobIsGoneArePrunedOnTheNextStart() throws Exception {
        String kept = upload(OWNER, "kept").storageRef();
        store().grantAccess(kept, GRANTEE);
        // An old node deleted a blob: the blob is gone, its grant row is not.
        seedGrantRow("8d4f6f1e-0000-4000-8000-00000000dead", GRANTEE);

        AttachmentGrantBackfill.Result result = backfill().run(id -> true, 500, 10);

        assertEquals(1, result.danglingRowsRemoved());
        assertEquals(0, grantRowsOn("8d4f6f1e-0000-4000-8000-00000000dead"));
        assertEquals(1, grantRowsOn(kept), "a row whose blob exists stays");
    }

    // ------------------------------------------------------------ concurrency

    @Test
    default void crossGrantedConversationsCanBeDeletedConcurrently() throws Exception {
        for (int round = 0; round < 20; round++) {
            String a = "6aba70011a25f8be7920" + "%04x".formatted(round * 2 + 0x100);
            String b = "6aba70011a25f8be7920" + "%04x".formatted(round * 2 + 0x101);
            List<String> aBlobs = new ArrayList<>();
            List<String> bBlobs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                aBlobs.add(upload(a, "a" + i).storageRef());
                bBlobs.add(upload(b, "b" + i).storageRef());
            }
            for (String ref : aBlobs) {
                store().grantAccess(ref, b);
            }
            for (String ref : bBlobs) {
                store().grantAccess(ref, a);
            }
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Long> deleteA = pool.submit(() -> store().deleteByConversation(a));
                Future<Long> deleteB = pool.submit(() -> store().deleteByConversation(b));
                assertEquals(3, deleteA.get(30, TimeUnit.SECONDS), "round " + round);
                assertEquals(3, deleteB.get(30, TimeUnit.SECONDS), "round " + round);
            } finally {
                pool.shutdownNow();
            }
            assertEquals(0, grantRowsHeldBy(a));
            assertEquals(0, grantRowsHeldBy(b));
        }
    }
}
