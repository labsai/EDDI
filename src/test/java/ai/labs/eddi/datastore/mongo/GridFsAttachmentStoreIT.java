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
import ai.labs.eddi.engine.attachments.AttachmentStoreContract;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
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
class GridFsAttachmentStoreIT extends MongoTestBase implements AttachmentStoreContract {

    private GridFsAttachmentStore store;

    @BeforeEach
    void setUp() {
        dropCollections("attachments.files", "attachments.chunks", GridFsAttachmentStore.LOCKS_COLLECTION,
                GridFsAttachmentStore.GRANTS_COLLECTION, GridFsAttachmentStore.BACKFILL_COLLECTION, "migrationlog", "groupconversations");
        store = new GridFsAttachmentStore(getDatabase());
        store.maxSizeBytes = 1_048_576;
        setQuotas(0, 0, 0, 0);
        store.ensureIndexes();
        // Production reads MigrationLog through its POJO codec; the bare test client
        // has none.
        migrationLog = new MigrationLogStore(getDatabase().withCodecRegistry(CodecRegistries.fromRegistries(getDatabase().getCodecRegistry(),
                CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build()))));
        groupConversations = new GroupConversationStore(new MongoResourceStorageFactory(getDatabase()), documentBuilder);
    }

    private MigrationLogStore migrationLog;
    private GroupConversationStore groupConversations;

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
}
