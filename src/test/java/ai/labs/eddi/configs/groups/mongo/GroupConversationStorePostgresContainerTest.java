/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionRecord;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionStatus;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.postgres.PostgresResourceStorageFactory;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link GroupConversationStore#updateIfStateAndPause} against a real
 * PostgreSQL: the same cases as
 * {@link GroupConversationStoreMongoContainerTest}. Here the conjunction is two
 * {@code data ->> field} predicates in one UPDATE.
 */
@DisplayName("GroupConversationStore on PostgreSQL - pause-bound CAS")
class GroupConversationStorePostgresContainerTest extends PostgresTestBase {

    private GroupConversationStore store;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = createDataSource();
        var json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        store = new GroupConversationStore(new PostgresResourceStorageFactory(createDataSourceInstance(), json), null);
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
        try (Connection conn = dataSource.getConnection();
                PreparedStatement strip = conn.prepareStatement("UPDATE resources SET data = data - 'pauseId' WHERE id = ?::uuid")) {
            strip.setString(1, gc.getId());
            assertEquals(1, strip.executeUpdate());
        }
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
    /** A running discussion owing one approved-result action, stored. */
    private GroupConversation runningWithOwedAction() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setGroupId("g-1");
        gc.setState(GroupConversationState.IN_PROGRESS);
        var list = new SharedTaskList();
        list.setApprovedActions(Map.of("t-1", new ApprovedActionRecord(ApprovedActionStatus.PENDING, "Draft", 0, "uri#post", null, "alice",
                false, "key", Instant.ofEpochMilli(1_000L), null, null, null, null, null)));
        gc.setTaskList(list);
        store.create(gc);
        return gc;
    }

    private static void cancel(GroupConversation gc) {
        gc.setState(GroupConversationState.CANCELLED);
        gc.setPausedAt(null);
    }

    @Test
    @DisplayName("a cancel read before the leg's claim cannot overwrite the claim")
    void staleSettlingCancel_isRefusedByTheClaimsRevision() throws Exception {
        GroupConversation gc = runningWithOwedAction();
        GroupConversation cancelsCopy = store.read(gc.getId());
        // the leg claims: RUNNING, with a new revision, under the plain state CAS
        GroupConversation leg = store.read(gc.getId());
        var claimed = leg.getTaskList().getApprovedActions().get("t-1").running(Instant.ofEpochMilli(2_000L));
        leg.getTaskList().updateApprovedAction("t-1", claimed);
        leg.getTaskList().setApprovedActionsRevision("rev-claim");
        store.updateIfState(leg, GroupConversationState.IN_PROGRESS);

        cancel(cancelsCopy);
        assertThrows(IResourceStore.ResourceModifiedException.class,
                () -> store.updateIfStateAndPause(cancelsCopy, GroupConversationState.IN_PROGRESS, null));

        GroupConversation after = store.read(gc.getId());
        assertEquals(GroupConversationState.IN_PROGRESS, after.getState());
        assertEquals(ApprovedActionStatus.RUNNING, after.getTaskList().getApprovedActions().get("t-1").status());
    }

    @Test
    @DisplayName("a cancel that read the current revision lands and settles what is owed")
    void currentSettlingCancel_lands() throws Exception {
        GroupConversation gc = runningWithOwedAction();
        GroupConversation write = store.read(gc.getId());
        cancel(write);

        store.updateIfStateAndPause(write, GroupConversationState.IN_PROGRESS, null);

        GroupConversation after = store.read(gc.getId());
        assertEquals(GroupConversationState.CANCELLED, after.getState());
        assertEquals(ApprovedActionStatus.NOT_SENT, after.getTaskList().getApprovedActions().get("t-1").status());
    }
}
