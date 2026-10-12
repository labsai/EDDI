/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.datastore.mongo.MongoResourceStorageFactory;
import ai.labs.eddi.datastore.mongo.MongoTestBase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GroupWorkspaceStore} against a real MongoDB: a stored workspace with
 * no {@code revision} field must still accept its first write — and only one of
 * two writers racing on it. A mock cannot show either, because both depend on
 * how the database evaluates the conditional filter against a missing field.
 */
@DisplayName("GroupWorkspaceStore on MongoDB — revision-less documents")
class GroupWorkspaceStoreMongoContainerTest extends MongoTestBase {

    private static final String COLLECTION = "groupworkspaces";
    private static final String GROUP_ID = "legacy-group";

    private GroupWorkspaceStore store;

    @BeforeEach
    void setUp() {
        dropCollections(COLLECTION);
        store = new GroupWorkspaceStore(new MongoResourceStorageFactory(getDatabase()), documentBuilder);
    }

    /** A workspace whose stored document carries no revision field at all. */
    private String legacyWorkspace() throws Exception {
        GroupWorkspace created = store.readOrCreate(GROUP_ID);
        getDatabase().getCollection(COLLECTION)
                .updateOne(Filters.eq("_id", new ObjectId(created.getId())), Updates.unset("revision"));
        Document raw = getDatabase().getCollection(COLLECTION).find(Filters.eq("_id", new ObjectId(created.getId()))).first();
        assertFalse(raw.containsKey("revision"), "fixture: the stored document must lack the field");
        return created.getId();
    }

    @Test
    @DisplayName("the first write to a revision-less document lands and stamps revision 1")
    void legacyDocument_firstWriteLands() throws Exception {
        legacyWorkspace();
        GroupWorkspace read = store.find(GROUP_ID);
        assertEquals("0", read.getRevision(), "an absent field reads back as the model default");

        read.setRunningDiscussionId("gc-1");
        assertTrue(store.casRevision(read), "a strict revision=\"0\" filter never matches a missing field");

        GroupWorkspace after = store.find(GROUP_ID);
        assertEquals("1", after.getRevision());
        assertEquals("gc-1", after.getRunningDiscussionId());
        assertTrue(store.casRevision(after), "later writes use the normal revision CAS");
        assertEquals("2", store.find(GROUP_ID).getRevision());
    }

    @Test
    @DisplayName("two writers racing on a revision-less document: only the first lands")
    void legacyDocument_racingFirstWrites_onlyOneLands() throws Exception {
        legacyWorkspace();
        GroupWorkspace first = store.find(GROUP_ID);
        GroupWorkspace second = store.find(GROUP_ID);

        first.setRunningDiscussionId("gc-first");
        second.setRunningDiscussionId("gc-second");
        assertTrue(store.casRunningDiscussion(first));
        assertFalse(store.casRunningDiscussion(second),
                "the first write stamped the field, so the second matches neither branch");
        assertEquals("0", second.getRevision(), "the loser's stamp is restored for its re-read");

        GroupWorkspace after = store.find(GROUP_ID);
        assertEquals("gc-first", after.getRunningDiscussionId());
        assertEquals("1", after.getRevision());
    }

    @Test
    @DisplayName("concurrent creators of a real group get ONE workspace, keyed by the group id, and no write is lost")
    void concurrentCreate_convergesOnOneDocument_andKeepsEveryWrite() throws Exception {
        for (int round = 0; round < 10; round++) {
            String realGroupId = new ObjectId().toHexString();

            var outcome = WorkspaceCreateRace.run(store, realGroupId, 8);

            assertEquals(Set.of(realGroupId), outcome.idsReturned(), "every caller got the canonical document");
            assertEquals(8, outcome.writesCounted(), "a write that reported success must be in the workspace reads return");
            assertEquals(1, getDatabase().getCollection(COLLECTION).countDocuments(Filters.eq("groupId", realGroupId)));
        }
    }

    @Test
    @DisplayName("a workspace created before canonical ids is still found, and no second one is created")
    void legacyGeneratedIdWorkspace_isStillFound() throws Exception {
        String realGroupId = new ObjectId().toHexString();
        var legacy = new Document("groupId", realGroupId).append("revision", "0").append("_version", 1);
        getDatabase().getCollection(COLLECTION).insertOne(legacy);
        String legacyId = legacy.getObjectId("_id").toHexString();

        assertEquals(legacyId, store.readOrCreate(realGroupId).getId());
        assertEquals(1, getDatabase().getCollection(COLLECTION).countDocuments(Filters.eq("groupId", realGroupId)));
    }

    @Test
    @DisplayName("a stamped document at revision 0 still rejects a stale writer")
    void stampedDocument_staleWriterLoses() throws Exception {
        store.readOrCreate(GROUP_ID);
        GroupWorkspace first = store.find(GROUP_ID);
        GroupWorkspace stale = store.find(GROUP_ID);

        assertTrue(store.casRevision(first));
        assertFalse(store.casRevision(stale),
                "the \"or missing\" branch must not let a stale writer past a stamped revision");
    }
}
