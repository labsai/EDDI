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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** Sets the per-source caps on derived blobs. */
    void setDerivedLimits(DerivedCaps.Limits limits);

    /**
     * Every {@link AttachmentRootDeleted} the store announced since the test
     * started, in order.
     */
    List<AttachmentRootDeleted> rootDeletions();

    /** Rows (files) whose lineage root is {@code rootRef}, read under the store. */
    long lineageRows(String rootRef);

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

    // -------------------------------------------------------------- lineage

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Bytes with a PNG signature, so the store's MIME check accepts image/png. */
    private static byte[] png(String body) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        byte[] tail = text(body);
        byte[] bytes = new byte[signature.length + tail.length];
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        System.arraycopy(tail, 0, bytes, signature.length, tail.length);
        return bytes;
    }

    private static byte[] pdf(String body) {
        return text("%PDF-1.4\n" + body);
    }

    private Attachment derive(String rootRef, Derivation.Kind kind, String unitId, byte[] bytes, String mime, String requester)
            throws AttachmentStoreException {
        return store().storeDerived(bytes, mime, kind.name().toLowerCase() + ".bin", Derivation.of(rootRef, kind, unitId), requester);
    }

    private Attachment deriveText(String rootRef, String unitId, String body, String requester) throws AttachmentStoreException {
        return derive(rootRef, Derivation.Kind.TEXT_PART, unitId, text(body), "text/plain", requester);
    }

    @Test
    default void aViewAGranteeStoresBelongsToTheRootOwnerAndSurvivesTheGranteesDeletion() throws Exception {
        String root = upload(OWNER, "the owner's file").storageRef();
        store().grantAccess(root, GRANTEE);
        store().grantAccess(root, STRANGER);

        Attachment view = deriveText(root, "u1", "chapter one", GRANTEE);

        assertEquals(OWNER, view.conversationId(), "the root owner's, not the grantee's");
        assertEquals(new IAttachmentStore.OwnerRef(OwnerScope.CONVERSATION, OWNER, null), store().ownerOf(view.storageRef(), OWNER));
        assertEquals(root, store().rootOf(view.storageRef(), GRANTEE));
        assertArrayEquals(text("chapter one"), store().load(view.storageRef(), OWNER), "the owner reads it");
        assertArrayEquals(text("chapter one"), store().load(view.storageRef(), STRANGER), "another grantee of the root reads it");

        assertEquals(0, store().deleteByConversation(GRANTEE));

        assertArrayEquals(text("chapter one"), store().load(view.storageRef(), OWNER), "it does not die with the grantee");
        assertEquals(1, lineageRows(root));
        assertThrows(AttachmentNotFoundException.class, () -> store().load(view.storageRef(), GRANTEE),
                "the grantee's grant is gone, and with it its access to the view");
    }

    @Test
    default void theSameBytesAreSharedWithinAnOwnersRootOnlyNeverAcrossOwners() throws Exception {
        String mine = upload(OWNER, "same pdf").storageRef();
        String theirs = upload(OTHER_OWNER, "same pdf").storageRef();

        Attachment first = deriveText(mine, "u1", "identical text", OWNER);
        Attachment again = deriveText(mine, "u1", "identical text", OWNER);
        Attachment other = deriveText(theirs, "u1", "identical text", OTHER_OWNER);

        assertEquals(first.storageRef(), again.storageRef(), "dedup within (owner, root)");
        assertEquals(1, lineageRows(mine));
        // The dedup key is (owner, root, sha256): the same bytes under another key
        // return the first blob, which keeps its own key.
        assertEquals(first.storageRef(), deriveText(mine, "u2", "identical text", OWNER).storageRef());
        assertTrue(store().findDerived(mine, Derivation.Kind.TEXT_PART, "u2", OWNER).isEmpty());
        assertFalse(first.storageRef().equals(other.storageRef()), "two owners with the same bytes share nothing");
        assertThrows(AttachmentNotFoundException.class, () -> store().load(first.storageRef(), OTHER_OWNER));
        assertThrows(AttachmentNotFoundException.class, () -> store().load(other.storageRef(), OWNER));
    }

    @Test
    default void aStrangerIsToldADerivedBlobDoesNotExist() throws Exception {
        String root = upload(OWNER, "private").storageRef();
        String view = deriveText(root, "u1", "private text", OWNER).storageRef();

        assertThrows(AttachmentNotFoundException.class, () -> store().load(view, STRANGER));
        assertThrows(AttachmentNotFoundException.class, () -> store().getMetadata(view, STRANGER));
        assertThrows(AttachmentNotFoundException.class, () -> store().getDerivation(view, STRANGER));
        assertThrows(AttachmentNotFoundException.class, () -> store().rootOf(view, STRANGER));
        assertThrows(AttachmentNotFoundException.class, () -> store().ownerOf(view, STRANGER));
        assertThrows(AttachmentNotFoundException.class, () -> store().pageCount(view, STRANGER));
        assertThrows(AttachmentNotFoundException.class, () -> store().listDerived(root, null, STRANGER));
        assertThrows(AttachmentNotFoundException.class, () -> store().findDerived(root, Derivation.Kind.TEXT_PART, "u1", STRANGER));
        assertThrows(AttachmentNotFoundException.class, () -> deriveText(root, "u2", "planted", STRANGER), "nor may it add one");
        assertEquals(1, lineageRows(root));
    }

    @Test
    default void deletingARootLeavesNoLineageNoGrantsAndAnnouncesItOnce() throws Exception {
        Attachment rootBlob = store().store(text("root"), "text/plain", "a.txt", OWNER, "default", USER);
        String root = rootBlob.storageRef();
        store().grantAccess(root, GRANTEE);
        String manifest = derive(root, Derivation.Kind.MANIFEST, "m", text("{}"), "application/json", OWNER).storageRef();
        store().storeDerived(text("part"), "text/plain", "p.txt", Derivation.of(root, Derivation.Kind.MANIFEST_PART, "m.1").withParent(manifest),
                GRANTEE);
        assertEquals(2, lineageRows(root));

        assertTrue(store().delete(root, OWNER));

        assertEquals(0, lineageRows(root), "the whole lineage, including a view of a view");
        assertEquals(0, grantRowsOn(root));
        assertThrows(AttachmentNotFoundException.class, () -> store().load(manifest, OWNER));
        assertEquals(List.of(new AttachmentRootDeleted(root, OwnerScope.CONVERSATION, OWNER, USER)), rootDeletions());
    }

    @Test
    default void deletingAConversationAnnouncesEachRootAndNoView() throws Exception {
        String a = upload(OWNER, "a").storageRef();
        String b = upload(OWNER, "b").storageRef();
        deriveText(a, "u1", "a1", OWNER);
        deriveText(a, "u2", "a2", OWNER);

        assertEquals(2, store().deleteByConversation(OWNER), "roots are counted, views are not");

        assertEquals(0, lineageRows(a));
        Set<String> announced = new LinkedHashSet<>();
        rootDeletions().forEach(e -> announced.add(e.rootRef()));
        assertEquals(Set.of(a, b), announced);
        assertEquals(2, rootDeletions().size());
    }

    @Test
    default void deletingAViewDeletesThatViewOnlyAndAnnouncesNothing() throws Exception {
        String root = upload(OWNER, "kept").storageRef();
        String one = deriveText(root, "u1", "one", OWNER).storageRef();
        deriveText(root, "u2", "two", OWNER);

        assertThrows(AttachmentAccessDeniedException.class, () -> store().delete(one, GRANTEE));
        assertTrue(store().delete(one, OWNER));

        assertEquals(1, lineageRows(root));
        assertArrayEquals(text("kept"), store().load(root, OWNER));
        assertTrue(rootDeletions().isEmpty());
    }

    @Test
    default void listingsAndFileCountsSeeRootsOnly() throws Exception {
        setQuotas(2, 0, 0, 0);
        String root = upload(OWNER, "root").storageRef();
        store().grantAccess(root, GRANTEE);
        String view = deriveText(root, "u1", "view", GRANTEE).storageRef();
        deriveText(root, "u2", "view two", OWNER);

        assertEquals(Set.of(root), refs(store().listByConversation(OWNER)));
        assertEquals(Set.of(root), refs(store().listAccessible(OWNER)));
        assertEquals(Set.of(root), refs(store().listAccessible(GRANTEE)));
        List<Attachment> views = store().listDerived(root, Derivation.Kind.TEXT_PART, OWNER);
        assertEquals(2, views.size());
        assertEquals(view, views.get(0).storageRef(), "oldest first");
        assertEquals(2, store().listDerived(root, null, GRANTEE).size());
        assertTrue(store().listDerived(root, Derivation.Kind.IMAGE, OWNER).isEmpty());

        // Two views stored; the conversation's file count is still one.
        assertNotNull(upload(OWNER, "second upload fits the count of 2"));
        assertThrows(AttachmentQuotaExceededException.class, () -> upload(OWNER, "a third does not"));
    }

    @Test
    default void textViewsAndSlicesNeverUseTheOwnersUploadQuotaButMediaDo() throws Exception {
        String root = upload(OWNER, "1234567890").storageRef(); // 10 bytes
        store().grantAccess(root, GRANTEE);
        setQuotas(0, 20, 0, 0); // 10 bytes of room left

        // A grantee reads the file into far more than the owner's quota: text and
        // slices.
        deriveText(root, "u1", "x".repeat(500), GRANTEE);
        for (int i = 0; i < 20; i++) {
            derive(root, Derivation.Kind.PDF_SLICE, "p" + i, pdf("slice " + i + " " + "y".repeat(100)), "application/pdf", GRANTEE);
        }
        assertNotNull(upload(OWNER, "123456789"), "the owner's upload quota is untouched: 9 of the 10 bytes left still fit");

        // Media views are the owner's bytes: 1 byte left now.
        var refused = assertThrows(AttachmentQuotaExceededException.class,
                () -> derive(root, Derivation.Kind.IMAGE, "i1", png("an image"), "image/png", GRANTEE));
        assertEquals(AttachmentQuotaExceededException.SCOPE_CONVERSATION, refused.getScope());
    }

    @Test
    default void aMediaViewCountsTowardTheOwnersBytesExactlyOnce() throws Exception {
        String root = upload(OWNER, "12345").storageRef(); // 5 bytes
        byte[] image = png("img"); // 11 bytes
        derive(root, Derivation.Kind.IMAGE, "i1", image, "image/png", OWNER);
        setQuotas(0, 5 + image.length + 4, 0, 0);

        assertNotNull(upload(OWNER, "1234"), "5 + 11 + 4 fits exactly");
        assertThrows(AttachmentQuotaExceededException.class, () -> upload(OWNER, "1"));
    }

    @Test
    default void aViewOfAViewKeepsTheOriginalRootAndRecordsItsParent() throws Exception {
        String root = upload(OWNER, "root").storageRef();
        String manifest = derive(root, Derivation.Kind.MANIFEST, "m", text("{\"units\":[]}"), "application/json", OWNER).storageRef();

        // Named by the view alone: the store finds the original root.
        Attachment part = store().storeDerived(text("part"), "text/plain", "p.txt",
                new Derivation(manifest, null, Derivation.Kind.MANIFEST_PART, "m.1", 3, "a caller-supplied hash is ignored"), OWNER);

        Derivation recorded = store().getDerivation(part.storageRef(), OWNER).orElseThrow();
        assertEquals(root, recorded.rootRef());
        assertEquals(manifest, recorded.parentRef());
        assertEquals(Derivation.Kind.MANIFEST_PART, recorded.kind());
        assertEquals("m.1", recorded.unitId());
        assertEquals(3, recorded.page());
        assertEquals(DerivedBlobs.sha256Hex(text("part")), recorded.sha256());
        assertTrue(store().getDerivation(root, OWNER).isEmpty(), "a root has no derivation");
        assertNull(store().getDerivation(manifest, OWNER).orElseThrow().parentRef(), "taken from the root itself");
    }

    @Test
    default void aParentOfAnotherRootIsRefused() throws Exception {
        String root = upload(OWNER, "root").storageRef();
        String otherRoot = upload(OWNER, "other").storageRef();
        String otherView = deriveText(otherRoot, "u1", "other view", OWNER).storageRef();

        assertThrows(AttachmentStoreException.class, () -> store().storeDerived(text("x"), "text/plain", "x.txt",
                Derivation.of(root, Derivation.Kind.TEXT_PART, "u9").withParent(otherView), OWNER));
        assertEquals(0, lineageRows(root));
    }

    @Test
    default void findDerivedIsAnExactKeyAndTheNewestWriteWins() throws Exception {
        String root = upload(OWNER, "root").storageRef();
        Attachment old = deriveText(root, "u1", "first version", OWNER);
        Thread.sleep(20);
        Attachment newer = deriveText(root, "u1", "second version", OWNER);
        Attachment unkeyed = derive(root, Derivation.Kind.SUMMARY, null, text("a summary"), "text/plain", OWNER);

        assertEquals(newer.storageRef(), store().findDerived(root, Derivation.Kind.TEXT_PART, "u1", OWNER).orElseThrow().storageRef());
        assertFalse(old.storageRef().equals(newer.storageRef()));
        assertTrue(store().findDerived(root, Derivation.Kind.TEXT_PART, "u2", OWNER).isEmpty());
        assertTrue(store().findDerived(root, Derivation.Kind.SUMMARY, "u1", OWNER).isEmpty(), "the kind is part of the key");
        assertEquals(unkeyed.storageRef(), store().findDerived(root, Derivation.Kind.SUMMARY, null, OWNER).orElseThrow().storageRef());
    }

    @Test
    default void theMediaCapRefusesWithItsName() throws Exception {
        setDerivedLimits(new DerivedCaps.Limits(2, 0, 0, 0, 0));
        String root = upload(OWNER, "root").storageRef();
        derive(root, Derivation.Kind.IMAGE, "i1", png("one"), "image/png", OWNER);
        derive(root, Derivation.Kind.TABLE, "t1", text("a,b"), "text/csv", OWNER);
        deriveText(root, "u1", "text does not count against the media cap", OWNER);

        var refused = assertThrows(IAttachmentStore.AttachmentDerivedCapException.class,
                () -> derive(root, Derivation.Kind.AUDIO, "a1", png("three"), "image/png", OWNER));
        assertEquals("max-per-source", refused.getCap());
        assertEquals(3, lineageRows(root));
    }

    @Test
    default void slicesEvictTheLeastRecentlyLoadedInsteadOfRefusing() throws Exception {
        setDerivedLimits(new DerivedCaps.Limits(0, 0, 2, 0, 0));
        String root = upload(OWNER, "root").storageRef();
        String first = derive(root, Derivation.Kind.PDF_SLICE, "p1-2", pdf("first"), "application/pdf", OWNER).storageRef();
        Thread.sleep(20);
        String second = derive(root, Derivation.Kind.PDF_SLICE, "p3-4", pdf("second"), "application/pdf", OWNER).storageRef();
        Thread.sleep(20);
        store().load(first, OWNER); // the first is now the most recently used
        Thread.sleep(20);

        String third = derive(root, Derivation.Kind.PDF_SLICE, "p5-6", pdf("third"), "application/pdf", OWNER).storageRef();

        Set<String> slices = refs(store().listDerived(root, Derivation.Kind.PDF_SLICE, OWNER));
        assertEquals(Set.of(first, third), slices, "the second, least recently used, was evicted");
        assertThrows(AttachmentNotFoundException.class, () -> store().load(second, OWNER));
    }

    @Test
    default void aGrantOnAViewIsAGrantOnItsRoot() throws Exception {
        String root = upload(OWNER, "root").storageRef();
        String view = deriveText(root, "u1", "view", OWNER).storageRef();

        store().grantAccess(view, GRANTEE);

        assertEquals(1, grantRowsOn(root));
        assertEquals(0, grantRowsOn(view));
        assertArrayEquals(text("root"), store().load(root, GRANTEE));
        assertArrayEquals(text("view"), store().load(view, GRANTEE));
    }

    @Test
    default void thePageCountIsRecordedOnTheRootAndReadThroughAnyView() throws Exception {
        String root = upload(OWNER, "root").storageRef();
        String view = deriveText(root, "u1", "view", OWNER).storageRef();
        assertNull(store().pageCount(root, OWNER), "unknown until recorded");

        store().setPageCount(root, 42);
        store().setPageCount(view, 7); // a derived ref is ignored

        assertEquals(42, store().pageCount(root, OWNER));
        assertEquals(42, store().pageCount(view, OWNER));
    }

    @Test
    default void aViewOfADeletedRootIsRefused() throws Exception {
        String root = upload(OWNER, "gone soon").storageRef();
        assertTrue(store().delete(root, OWNER));

        assertThrows(AttachmentNotFoundException.class, () -> deriveText(root, "u1", "too late", OWNER));
        assertEquals(0, lineageRows(root));
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
