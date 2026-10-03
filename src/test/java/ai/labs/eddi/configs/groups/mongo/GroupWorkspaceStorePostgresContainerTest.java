/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.model.GroupWorkspace;
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
import java.sql.ResultSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GroupWorkspaceStore} against a real PostgreSQL: the same revision-less
 * document cases as {@link GroupWorkspaceStoreMongoContainerTest}. On this
 * backend {@code data ->> 'revision'} is SQL NULL for a missing key, and
 * {@code NULL = '0'} is not true — so the strict CAS alone would never match.
 */
@DisplayName("GroupWorkspaceStore on PostgreSQL — revision-less documents")
class GroupWorkspaceStorePostgresContainerTest extends PostgresTestBase {

    private GroupWorkspaceStore store;
    private DataSource dataSource;
    private String groupId;

    @BeforeEach
    void setUp() {
        dataSource = createDataSource();
        var json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        store = new GroupWorkspaceStore(new PostgresResourceStorageFactory(createDataSourceInstance(), json), null);
        // The resources table is shared with other container tests in this JVM —
        // a fresh group per test instead of a truncate.
        groupId = "legacy-" + UUID.randomUUID();
    }

    private void legacyWorkspace() throws Exception {
        GroupWorkspace created = store.readOrCreate(groupId);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement strip = conn.prepareStatement(
                        "UPDATE resources SET data = data - 'revision' WHERE id = ?::uuid");
                PreparedStatement check = conn.prepareStatement(
                        "SELECT (data -> 'revision') IS NULL FROM resources WHERE id = ?::uuid")) {
            strip.setString(1, created.getId());
            assertEquals(1, strip.executeUpdate());
            check.setString(1, created.getId());
            try (ResultSet rs = check.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1), "fixture: the stored document must lack the field");
            }
        }
    }

    @Test
    @DisplayName("the first write to a revision-less document lands and stamps revision 1")
    void legacyDocument_firstWriteLands() throws Exception {
        legacyWorkspace();
        GroupWorkspace read = store.find(groupId);
        assertEquals("0", read.getRevision(), "an absent field reads back as the model default");

        read.setRunningDiscussionId("gc-1");
        assertTrue(store.casRevision(read), "a strict revision = '0' predicate never matches a missing key");

        GroupWorkspace after = store.find(groupId);
        assertEquals("1", after.getRevision());
        assertEquals("gc-1", after.getRunningDiscussionId());
        assertTrue(store.casRevision(after), "later writes use the normal revision CAS");
        assertEquals("2", store.find(groupId).getRevision());
    }

    @Test
    @DisplayName("two writers racing on a revision-less document: only the first lands")
    void legacyDocument_racingFirstWrites_onlyOneLands() throws Exception {
        legacyWorkspace();
        GroupWorkspace first = store.find(groupId);
        GroupWorkspace second = store.find(groupId);

        first.setRunningDiscussionId("gc-first");
        second.setRunningDiscussionId("gc-second");
        assertTrue(store.casRunningDiscussion(first));
        assertFalse(store.casRunningDiscussion(second),
                "the first write stamped the field, so the second matches neither branch");
        assertEquals("0", second.getRevision(), "the loser's stamp is restored for its re-read");

        GroupWorkspace after = store.find(groupId);
        assertEquals("gc-first", after.getRunningDiscussionId());
        assertEquals("1", after.getRevision());
    }

    @Test
    @DisplayName("a stamped document at revision 0 still rejects a stale writer")
    void stampedDocument_staleWriterLoses() throws Exception {
        store.readOrCreate(groupId);
        GroupWorkspace first = store.find(groupId);
        GroupWorkspace stale = store.find(groupId);

        assertTrue(store.casRevision(first));
        assertFalse(store.casRevision(stale),
                "the \"or missing\" branch must not let a stale writer past a stamped revision");
    }

    @Test
    @DisplayName("concurrent creators of a real group get ONE workspace, keyed by the group id, and no write is lost")
    void concurrentCreate_convergesOnOneDocument_andKeepsEveryWrite() throws Exception {
        for (int round = 0; round < 10; round++) {
            String realGroupId = UUID.randomUUID().toString();

            var outcome = WorkspaceCreateRace.run(store, realGroupId, 8);

            assertEquals(Set.of(realGroupId), outcome.idsReturned(), "every caller got the canonical document");
            assertEquals(8, outcome.writesCounted(), "a write that reported success must be in the workspace reads return");
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement count = conn.prepareStatement(
                            "SELECT count(*) FROM resources WHERE collection_name = 'groupworkspaces' AND data ->> 'groupId' = ?")) {
                count.setString(1, realGroupId);
                try (ResultSet rs = count.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
    }
}
