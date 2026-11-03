/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorageFactory;
import ai.labs.eddi.datastore.mongo.MongoTestBase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link GroupConversationStore#updateIfStateAndPause} against a real MongoDB:
 * a state-only CAS cannot tell a pause from the NEXT pause of the same state
 * (resume + re-pause between a read and a write), so the write must also name
 * the pause. A mock cannot show it - it depends on how the database evaluates
 * the conjunctive filter in the one atomic replace.
 */
@DisplayName("GroupConversationStore on MongoDB - pause-bound CAS")
class GroupConversationStoreMongoContainerTest extends MongoTestBase {

    private static final String COLLECTION = "groupconversations";

    private GroupConversationStore store;

    @BeforeEach
    void setUp() {
        dropCollections(COLLECTION);
        store = new GroupConversationStore(new MongoResourceStorageFactory(getDatabase()), documentBuilder);
    }

    private GroupConversation pausedAt(long millis) throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setGroupId("g-1");
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAt(Instant.ofEpochMilli(millis));
        store.create(gc);
        return gc;
    }

    private GroupConversation decision(String id, GroupConversationState to) throws Exception {
        GroupConversation write = store.read(id);
        write.setState(to);
        write.setPausedAt(null);
        return write;
    }

    @Test
    @DisplayName("the write for the current pause lands")
    void currentPause_lands() throws Exception {
        GroupConversation gc = pausedAt(1_000L);
        GroupConversation write = decision(gc.getId(), GroupConversationState.REJECTED);

        store.updateIfStateAndPause(write, GroupConversationState.AWAITING_APPROVAL, "1000");

        GroupConversation after = store.read(gc.getId());
        assertEquals(GroupConversationState.REJECTED, after.getState());
        assertNull(after.getPauseId(), "leaving the pause clears the marker with it");
    }

    @Test
    @DisplayName("ABA: a decision read before resume + re-pause is refused by the newer pause")
    void staleDecisionAfterResumeAndRepause_isRefused() throws Exception {
        GroupConversation gc = pausedAt(1_000L);
        GroupConversation stale = decision(gc.getId(), GroupConversationState.REJECTED);

        // Meanwhile: resume (state-only CAS to IN_PROGRESS) and pause again.
        GroupConversation resumed = store.read(gc.getId());
        resumed.setState(GroupConversationState.IN_PROGRESS);
        resumed.setPausedAt(null);
        store.updateIfState(resumed, GroupConversationState.AWAITING_APPROVAL);
        GroupConversation repaused = store.read(gc.getId());
        repaused.setState(GroupConversationState.AWAITING_APPROVAL);
        repaused.setPausedAt(Instant.ofEpochMilli(2_000L));
        store.updateIfState(repaused, GroupConversationState.IN_PROGRESS);

        assertThrows(IResourceStore.ResourceModifiedException.class,
                () -> store.updateIfStateAndPause(stale, GroupConversationState.AWAITING_APPROVAL, "1000"));

        GroupConversation after = store.read(gc.getId());
        assertEquals(GroupConversationState.AWAITING_APPROVAL, after.getState(), "the newer pause is untouched");
        assertEquals("2000", after.getPauseId());
    }

    @Test
    @DisplayName("the right pause in the wrong state is refused")
    void wrongState_isRefused() throws Exception {
        GroupConversation gc = pausedAt(1_000L);
        GroupConversation write = decision(gc.getId(), GroupConversationState.REJECTED);

        assertThrows(IResourceStore.ResourceModifiedException.class,
                () -> store.updateIfStateAndPause(write, GroupConversationState.AWAITING_HUMAN_INPUT, "1000"));
    }

    @Test
    @DisplayName("a document written before pauseId existed still accepts its pause's decision")
    void legacyDocumentWithoutPauseId_stillAccepted() throws Exception {
        GroupConversation gc = pausedAt(1_000L);
        getDatabase().getCollection(COLLECTION)
                .updateOne(Filters.eq("_id", new ObjectId(gc.getId())), Updates.unset("pauseId"));
        Document raw = getDatabase().getCollection(COLLECTION).find(Filters.eq("_id", new ObjectId(gc.getId()))).first();
        assertFalse(raw.containsKey("pauseId"), "fixture: the stored document must lack the field");
        GroupConversation write = decision(gc.getId(), GroupConversationState.REJECTED);

        store.updateIfStateAndPause(write, GroupConversationState.AWAITING_APPROVAL, "1000");

        assertEquals(GroupConversationState.REJECTED, store.read(gc.getId()).getState());
    }

    @Test
    @DisplayName("a deleted conversation is reported as gone, not as a conflict")
    void deleted_isGone() throws Exception {
        GroupConversation gc = pausedAt(1_000L);
        GroupConversation write = decision(gc.getId(), GroupConversationState.REJECTED);
        store.delete(gc.getId());

        assertThrows(GroupConversationGoneException.class,
                () -> store.updateIfStateAndPause(write, GroupConversationState.AWAITING_APPROVAL, "1000"));
    }
}
