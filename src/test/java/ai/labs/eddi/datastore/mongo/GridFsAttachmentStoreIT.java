/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.mongo.GroupConversationStore;
import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.MigrationLogStore;
import ai.labs.eddi.engine.attachments.AttachmentGrantBackfill;
import ai.labs.eddi.engine.attachments.AttachmentLockOrder;
import ai.labs.eddi.engine.attachments.AttachmentLockOrder.Lock;
import ai.labs.eddi.engine.attachments.AttachmentOwnerChanged;
import ai.labs.eddi.engine.attachments.AttachmentRootDeleted;
import ai.labs.eddi.engine.attachments.LibraryStoreContract;
import ai.labs.eddi.engine.attachments.LibraryStoreSupport;
import ai.labs.eddi.engine.attachments.AttachmentStoreContract;
import ai.labs.eddi.engine.attachments.Derivation;
import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.attachments.OwnerScope;
import ai.labs.eddi.engine.attachments.RecordingEvent;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.QuotaReservationContract;
import ai.labs.eddi.engine.attachments.StreamingAttachmentStoreContract;
import ai.labs.eddi.engine.attachments.blob.BlobBackendSelector;
import ai.labs.eddi.engine.attachments.blob.FaultInjectingBlobBackend;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GridFsAttachmentStore} against a real MongoDB (Testcontainers): the
 * shared {@link AttachmentStoreContract}, plus the grant indexes and a blob
 * stored before {@code storageRef} existed.
 */
@DisplayName("Attachment store (MongoDB GridFS)")
class GridFsAttachmentStoreIT extends MongoTestBase
        implements
            AttachmentStoreContract,
            QuotaReservationContract,
            StreamingAttachmentStoreContract,
            LibraryStoreContract {

    private GridFsAttachmentStore store;

    @BeforeEach
    void setUp() {
        dropCollections("attachments.files", "attachments.chunks", GridFsAttachmentStore.LOCKS_COLLECTION,
                GridFsAttachmentStore.GRANTS_COLLECTION, GridFsAttachmentStore.BACKFILL_COLLECTION, GridFsAttachmentStore.RESERVATIONS_COLLECTION,
                GridFsAttachmentStore.TOMBSTONES_COLLECTION, GridFsBlobBackend.BUCKET_NAME + ".files", GridFsBlobBackend.BUCKET_NAME + ".chunks",
                "migrationlog",
                "groupconversations");
        store = new GridFsAttachmentStore(getDatabase());
        store.maxSizeBytes = 1_048_576;
        setQuotas(0, 0, 0, 0);
        rootDeleted = new RecordingEvent<>();
        store.rootDeletedEvent = rootDeleted;
        ownerChanged = new RecordingEvent<>();
        store.ownerChangedEvent = ownerChanged;
        setLibraryLimits(LibraryStoreSupport.Limits.defaults());
        store.ensureIndexes();
        store.ensureLineageIndexes();
        store.ensureLibraryIndexes();
        // The real GridFS backend, with 255 KiB chunks so a large test file spans
        // several.
        blobBackend = new FaultInjectingBlobBackend(new GridFsBlobBackend(getDatabase(), 255 * 1024));
        store.blobBackends = BlobBackendSelector.of(blobBackend);
        // Production reads MigrationLog through its POJO codec; the bare test client
        // has none.
        migrationLog = new MigrationLogStore(getDatabase().withCodecRegistry(CodecRegistries.fromRegistries(getDatabase().getCodecRegistry(),
                CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build()))));
        groupConversations = new GroupConversationStore(new MongoResourceStorageFactory(getDatabase()), documentBuilder);
    }

    private MigrationLogStore migrationLog;
    private FaultInjectingBlobBackend blobBackend;

    @Override
    public FaultInjectingBlobBackend blobBackend() {
        return blobBackend;
    }

    @Override
    public void setMaxSizeBytes(long maxSizeBytes) {
        store.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public void makeTombstonesDue() {
        tombstones().updateMany(new Document(), List.of(new Document("$set",
                new Document(GridFsAttachmentStore.TOMBSTONE_NOT_BEFORE, new Document("$subtract", List.of("$$NOW", 1000L))))));
    }

    @Override
    public void seedDueTombstone(String key, String ownerKey) {
        tombstones().insertOne(new Document("_id", GridFsBlobBackend.NAME + "/" + key).append(GridFsAttachmentStore.TOMBSTONE_BACKEND,
                GridFsBlobBackend.NAME).append(GridFsAttachmentStore.TOMBSTONE_KEY, key).append(GridFsAttachmentStore.TOMBSTONE_OWNER_KEY, ownerKey)
                .append(GridFsAttachmentStore.TOMBSTONE_ATTEMPTS, 0).append(GridFsAttachmentStore.TOMBSTONE_NOT_BEFORE, new Date(0)));
    }

    @Override
    public List<String> heapProbeArgs() {
        return List.of("mongodb", MONGO.getConnectionString(), getDatabase().getName());
    }

    private static MongoCollection<Document> tombstones() {
        return getDatabase().getCollection(GridFsAttachmentStore.TOMBSTONES_COLLECTION);
    }

    private GroupConversationStore groupConversations;
    private RecordingEvent<AttachmentRootDeleted> rootDeleted;
    private RecordingEvent<AttachmentOwnerChanged> ownerChanged;

    @Override
    public void setLibraryLimits(LibraryStoreSupport.Limits limits) {
        store.libraryMaxPerUser = limits.maxPerOwner();
        store.libraryMaxTotalBytesPerUser = limits.maxTotalBytesPerOwner();
        store.libraryRetentionDays = limits.defaultRetentionDays();
        store.libraryMaxGrantsPerDocument = limits.maxGrantsPerDocument();
    }

    @Override
    public List<AttachmentOwnerChanged> ownerChanges() {
        return ownerChanged.fired();
    }

    @Override
    public void setLastAccessed(String storageRef, Instant when) {
        assertEquals(1, getDatabase().getCollection("attachments.files")
                .updateOne(Filters.eq("metadata.storageRef", storageRef), Updates.set("metadata.lastAccessedAt", Date.from(when))).getMatchedCount(),
                "no file " + storageRef);
    }

    @Override
    public void seedReservation(String ownerKey, String conversationId) {
        getDatabase().getCollection(GridFsAttachmentStore.RESERVATIONS_COLLECTION).insertOne(new Document("_id", new ObjectId().toHexString())
                .append(GridFsAttachmentStore.RESERVATION_OWNER_KEY, ownerKey).append("conversationId", conversationId)
                .append(GridFsAttachmentStore.RESERVATION_BYTES, 1L).append(GridFsAttachmentStore.RESERVATION_COUNT, 1)
                .append(GridFsAttachmentStore.RESERVATION_EXPIRES_AT, new Date(System.currentTimeMillis() + 3_600_000L)));
    }

    @Override
    public AutoCloseable holdConversationQuotaLock(String conversationId) {
        String key = GridFsAttachmentStore.lockKey(new Lock(AttachmentLockOrder.Scope.CONVERSATION, conversationId));
        MongoCollection<Document> locks = getDatabase().getCollection(GridFsAttachmentStore.LOCKS_COLLECTION);
        locks.insertOne(new Document("_id", key).append("owner", "test").append("expiresAt", new Date(System.currentTimeMillis() + 60_000L)));
        return () -> locks.deleteOne(Filters.and(Filters.eq("_id", key), Filters.eq("owner", "test")));
    }

    @Override
    public long reservationsOf(String ownerKey) {
        return getDatabase().getCollection(GridFsAttachmentStore.RESERVATIONS_COLLECTION)
                .countDocuments(Filters.eq(GridFsAttachmentStore.RESERVATION_OWNER_KEY, ownerKey));
    }

    @Override
    public IMigrationLogStore migrationLog() {
        return migrationLog;
    }

    @Override
    public String createGroupConversation() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setGroupId("g-1");
        gc.setState(GroupConversation.GroupConversationState.CREATED);
        return groupConversations.create(gc);
    }

    @Override
    public AttachmentGrantBackfill backfillWithRealStores() {
        return new AttachmentGrantBackfill(store, migrationLog, new ConversationMemoryStore(getDatabase()), groupConversations);
    }

    @Override
    public void seedGrantRow(String storageRef, String conversationId) {
        grants().insertOne(new Document("storageRef", storageRef).append("conversationId", conversationId).append("createdAt", new Date()));
    }

    @Override
    public IAttachmentStore store() {
        return store;
    }

    @Override
    public IAttachmentGrantMigration migration() {
        return store;
    }

    @Override
    public void setQuotas(long perConversation, long bytesPerConversation, long perUser, long bytesPerUser) {
        store.maxPerConversation = perConversation;
        store.maxTotalBytesPerConversation = bytesPerConversation;
        store.maxPerUser = perUser;
        store.maxTotalBytesPerUser = bytesPerUser;
    }

    @Override
    public void expireReservations() {
        // The server's clock, as the store reads it.
        reservations().updateMany(new Document(), List.of(new Document("$set",
                new Document(GridFsAttachmentStore.RESERVATION_EXPIRES_AT, new Document("$subtract", List.of("$$NOW", 1000L))))));
    }

    @Override
    public Duration storedLifetime(String reservationId) {
        Document stored = reservations().find(new Document("_id", reservationId)).first();
        assertTrue(stored != null, "no reservation " + reservationId);
        return Duration.ofMillis(stored.getDate(GridFsAttachmentStore.RESERVATION_EXPIRES_AT).getTime()
                - stored.getDate(GridFsAttachmentStore.RESERVATION_CREATED_AT).getTime());
    }

    @Override
    public long reservationRows() {
        return reservations().countDocuments();
    }

    @Override
    public String storeUncharged(String conversationId, String userId, byte[] bytes) {
        long[] quotas = {store.maxPerConversation, store.maxTotalBytesPerConversation, store.maxPerUser, store.maxTotalBytesPerUser};
        setQuotas(0, 0, 0, 0);
        try {
            return store.store(bytes, "text/plain", "uncharged.txt", conversationId, "default", userId).storageRef();
        } catch (IAttachmentStore.AttachmentStoreException e) {
            throw new IllegalStateException(e);
        } finally {
            setQuotas(quotas[0], quotas[1], quotas[2], quotas[3]);
        }
    }

    private static MongoCollection<Document> reservations() {
        return getDatabase().getCollection(GridFsAttachmentStore.RESERVATIONS_COLLECTION);
    }

    @Test
    void theReservationIndexesIncludeATtlBackstopOnTheExpiry() {
        store.ensureReservationIndexes();

        List<Document> indexes = reservations().listIndexes().into(new ArrayList<>());
        assertTrue(indexes.stream().anyMatch(index -> index.get("key", Document.class).containsKey(GridFsAttachmentStore.RESERVATION_EXPIRES_AT)
                && Long.valueOf(0).equals(((Number) index.get("expireAfterSeconds")).longValue())), indexes.toString());
    }

    @Override
    public void setDerivedLimits(DerivedCaps.Limits limits) {
        store.derivedMaxPerSource = limits.maxPerSource();
        store.derivedMaxTotalBytesPerSource = limits.maxTotalBytesPerSource();
        store.derivedMaxSlicesPerSource = limits.maxSlicesPerSource();
        store.derivedMaxSliceBytesPerSource = limits.maxSliceBytesPerSource();
        store.derivedMaxTextBytesPerSource = limits.maxTextBytesPerSource();
    }

    @Override
    public List<AttachmentRootDeleted> rootDeletions() {
        return rootDeleted.fired();
    }

    @Override
    public long lineageRows(String rootRef) {
        return files().countDocuments(Filters.eq("metadata.rootRef", rootRef));
    }

    private static MongoCollection<Document> grants() {
        return getDatabase().getCollection(GridFsAttachmentStore.GRANTS_COLLECTION);
    }

    private static MongoCollection<Document> files() {
        return getDatabase().getCollection("attachments.files");
    }

    @Override
    public long grantRowsOn(String storageRef) {
        return grants().countDocuments(Filters.eq("storageRef", storageRef));
    }

    @Override
    public long grantRowsHeldBy(String conversationId) {
        return grants().countDocuments(Filters.eq("conversationId", conversationId));
    }

    @Override
    public List<String> legacyGrantees(String storageRef) {
        Document file = files().find(Filters.eq("metadata.storageRef", storageRef)).first();
        List<String> grants = file == null ? null : file.get("metadata", Document.class).getList("grants", String.class);
        return grants == null ? List.of() : grants;
    }

    @Override
    public long legacyGrantsHeldBy(String conversationId) {
        return files().countDocuments(Filters.eq("metadata.grants", conversationId));
    }

    @Override
    public void seedLegacyGrant(String storageRef, String conversationId) {
        files().updateOne(Filters.eq("metadata.storageRef", storageRef), Updates.addToSet("metadata.grants", conversationId));
    }

    @Override
    public void dropGrantRows() {
        grants().deleteMany(new Document());
    }

    @Test
    void createsTheUniqueGrantIndexAndTheGranteeIndex() {
        List<Document> indexes = grants().listIndexes().into(new ArrayList<>());
        Document pair = indexes.stream().filter(index -> index.get("key", Document.class).keySet().equals(
                Set.of("storageRef", "conversationId"))).findFirst().orElseThrow(() -> new AssertionError(indexes.toString()));
        assertTrue(pair.getBoolean("unique", false), pair.toJson());
        assertTrue(indexes.stream().anyMatch(index -> index.get("key", Document.class).keySet().equals(Set.of("conversationId"))),
                indexes.toString());
    }

    @Test
    void theCompletionMarkerIsWrittenOnceAcrossStarts() throws Exception {
        // The MongoDB migration log has no unique key, and every start runs a pass.
        String ref = store.store("marked".getBytes(StandardCharsets.UTF_8), "text/plain", "m.txt", OWNER, "default", null).storageRef();
        seedLegacyGrant(ref, GRANTEE);
        AttachmentGrantBackfill backfill = new AttachmentGrantBackfill(store, migrationLog, null, null);

        assertTrue(backfill.run(id -> true, 500, 10).complete());
        assertTrue(backfill.run(id -> true, 500, 10).complete());

        assertEquals(1, getDatabase().getCollection("migrationlog").countDocuments(Filters.eq("name", "attachment-grants-backfill-complete")));
    }

    @Test
    void aBlobStoredBeforeStorageRefExistedIsGrantedListedAndPrunedByItsObjectId() throws Exception {
        IAttachmentStore.Attachment stored = store.store("old blob".getBytes(StandardCharsets.UTF_8), "text/plain", "old.txt", OWNER, "default",
                null);
        // Make it look like a blob from before storageRef: no metadata.storageRef.
        files().updateOne(Filters.eq("metadata.storageRef", stored.storageRef()), Updates.unset("metadata.storageRef"));
        String hex = files().find(Filters.eq("metadata.conversationId", OWNER)).first().getObjectId("_id").toHexString();

        store.grantAccess(hex, GRANTEE);

        assertEquals(1, grantRowsOn(hex), "keyed by the ObjectId hex, the blob's only reference");
        assertArrayEquals("old blob".getBytes(StandardCharsets.UTF_8), store.load(hex, GRANTEE));
        dropGrantRows();
        assertEquals(List.of(hex), store.listAccessible(GRANTEE).stream().map(IAttachmentStore.Attachment::storageRef).toList(),
                "the legacy array still lists it");
        store.grantAccess(hex, GRANTEE);
        files().updateOne(Filters.eq("_id", new ObjectId(hex)), Updates.set("metadata.grants", List.of()));
        assertEquals(List.of(hex), store.listAccessible(GRANTEE).stream().map(IAttachmentStore.Attachment::storageRef).toList(),
                "and so does the grant row, by _id");

        assertEquals(1, store.deleteByConversation(OWNER));
        assertEquals(0, grantRowsOn(hex));
    }

    @Test
    void aViewOfABlobKnownOnlyByItsObjectIdKeepsThatAsItsRootAndDiesWithIt() throws Exception {
        IAttachmentStore.Attachment stored = store.store("old pdf".getBytes(StandardCharsets.UTF_8), "text/plain", "old.txt", OWNER, "default",
                null);
        files().updateOne(Filters.eq("metadata.storageRef", stored.storageRef()),
                Updates.combine(Updates.unset("metadata.storageRef"), Updates.unset("metadata.ownerKey"), Updates.unset("metadata.ownerScope")));
        String hex = files().find(Filters.eq("metadata.conversationId", OWNER)).first().getObjectId("_id").toHexString();
        store.grantAccess(hex, GRANTEE);

        IAttachmentStore.Attachment view = store.storeDerived("text".getBytes(StandardCharsets.UTF_8), "text/plain", "u1.txt",
                Derivation.of(hex, Derivation.Kind.TEXT_PART, "u1"), GRANTEE);

        assertEquals(hex, store.rootOf(view.storageRef(), GRANTEE));
        assertEquals(1, lineageRows(hex));
        assertTrue(store.delete(hex, OWNER));
        assertEquals(0, lineageRows(hex));
        assertEquals(List.of(new AttachmentRootDeleted(hex, OwnerScope.CONVERSATION, OWNER, null)), rootDeletions());
    }

    @Override
    public void makePreUpgradeRoot(String storageRef) {
        files().updateOne(Filters.eq("metadata.storageRef", storageRef),
                Updates.combine(Updates.unset("metadata.ownerKey"), Updates.unset("metadata.ownerScope")));
    }
}
