/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
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
}
