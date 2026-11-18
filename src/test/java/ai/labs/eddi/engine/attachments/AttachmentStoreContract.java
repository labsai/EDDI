/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentAccessDeniedException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentNotFoundException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** An in-memory migration log that keeps entries, as the MongoDB one does. */
    final class MemoryMigrationLog implements IMigrationLogStore {
        final Map<String, MigrationLog> logs = new HashMap<>();
        final Map<String, List<String>> entries = new HashMap<>();

        @Override
        public MigrationLog readMigrationLog(String name) {
            return logs.get(name);
        }

        @Override
        public void createMigrationLog(MigrationLog migrationLog) {
            logs.put(migrationLog.getName(), migrationLog);
        }

        @Override
        public List<String> readMigrationEntries(String name) {
            return entries.getOrDefault(name, List.of());
        }

        @Override
        public void writeMigrationEntries(String name, List<String> list) {
            entries.put(name, new ArrayList<>(list));
        }
    }

    @Test
    default void theBackfillCopiesLegacyGrantsAndSkipsGranteesThatNoLongerExist() throws Exception {
        String ref = upload(OWNER, "legacy").storageRef();
        seedLegacyGrant(ref, GRANTEE);
        seedLegacyGrant(ref, GONE);
        var log = new MemoryMigrationLog();
        var backfill = new AttachmentGrantBackfill(migration(), log, null, null);

        AttachmentGrantBackfill.Result result = backfill.run(id -> !GONE.equals(id), 500, 10);

        assertTrue(result.complete());
        assertEquals(1, result.grantsCopied());
        assertEquals(1, result.granteesSkipped());
        assertEquals(1, grantRowsHeldBy(GRANTEE));
        assertEquals(0, grantRowsHeldBy(GONE), "a dangling grant is not re-created");
        assertNotNull(log.readMigrationLog(AttachmentGrantBackfill.MIGRATION_KEY), "completion is recorded");
        assertEquals(1, grantRowsOn(ref));
        assertEquals(List.of(GRANTEE, GONE), legacyGrantees(ref), "the legacy shape is left as it was for this release");
    }

    @Test
    default void theBackfillIsResumableAndIdempotent() throws Exception {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String ref = upload(OWNER, "blob " + i).storageRef();
            seedLegacyGrant(ref, GRANTEE);
            refs.add(ref);
        }
        upload(OWNER, "never granted");
        var log = new MemoryMigrationLog();
        var backfill = new AttachmentGrantBackfill(migration(), log, null, null);

        // Two pages of two: cut short, no marker.
        AttachmentGrantBackfill.Result first = backfill.run(id -> true, 2, 2);
        assertFalse(first.complete());
        assertEquals(4, first.grantsCopied());
        assertTrue(log.readMigrationLog(AttachmentGrantBackfill.MIGRATION_KEY) == null);

        // The next start continues from the kept cursor.
        AttachmentGrantBackfill.Result second = backfill.run(id -> true, 2, 10);
        assertTrue(second.complete());
        assertEquals(1, second.blobsRead(), "resumed after the first four");
        assertEquals(1, second.grantsCopied());
        assertEquals(5, grantRowsHeldBy(GRANTEE));

        // Run again from scratch: nothing new.
        AttachmentGrantBackfill.Result again = new AttachmentGrantBackfill(migration(), new MemoryMigrationLog(), null, null)
                .run(id -> true, 2, 10);
        assertTrue(again.complete());
        assertEquals(0, again.grantsCopied());
        assertEquals(5, again.grantsAlreadyPresent());
        for (String ref : refs) {
            assertEquals(1, grantRowsOn(ref));
        }
    }

    @Test
    default void aFailedExistenceCheckEndsThePassWithoutTheMarker() throws Exception {
        String ref = upload(OWNER, "unknown").storageRef();
        seedLegacyGrant(ref, GRANTEE);
        var log = new MemoryMigrationLog();

        AttachmentGrantBackfill.Result result = new AttachmentGrantBackfill(migration(), log, null, null).run(id -> {
            throw new IllegalStateException("database unreachable");
        }, 500, 10);

        assertFalse(result.complete());
        assertEquals(0, grantRowsHeldBy(GRANTEE));
        assertTrue(log.readMigrationLog(AttachmentGrantBackfill.MIGRATION_KEY) == null, "could not tell is never gone");
    }
}
