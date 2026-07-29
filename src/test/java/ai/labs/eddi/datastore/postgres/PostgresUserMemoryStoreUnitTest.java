/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PostgresUserMemoryStore} with mocked JDBC connections.
 * <p>
 * Targets uncovered branches: findEntryById, deleteOlderThan, getVisibleEntries
 * with most_accessed ordering and group IDs, and resultSetToEntry edge cases.
 */
class PostgresUserMemoryStoreUnitTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    @SuppressWarnings("unchecked")
    private Instance<DataSource> dataSourceInstance;
    private PostgresUserMemoryStore sut;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        dataSourceInstance = mock(Instance.class);

        when(dataSourceInstance.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        sut = new PostgresUserMemoryStore(dataSourceInstance);
    }

    // ─── findEntryById ──────────────────────────────────────────

    @Test
    void findEntryById_found_returnsEntry() throws Exception {
        // given
        setupResultSetForEntry();
        when(resultSet.next()).thenReturn(true);

        // when
        Optional<UserMemoryEntry> result = sut.findEntryById("entry-1");

        // then
        assertTrue(result.isPresent());
        assertEquals("entry-1", result.get().id());
        assertEquals("user1", result.get().userId());
        assertEquals("fav_color", result.get().key());
        verify(preparedStatement).setString(1, "entry-1");
    }

    @Test
    void findEntryById_notFound_returnsEmpty() throws Exception {
        // given
        when(resultSet.next()).thenReturn(false);

        // when
        Optional<UserMemoryEntry> result = sut.findEntryById("missing-id");

        // then
        assertTrue(result.isEmpty());
    }

    @Test
    void findEntryById_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.findEntryById("entry-1"));
    }

    // ─── deleteOlderThan ────────────────────────────────────────

    @Test
    void deleteOlderThan_deletesExpiredEntries() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(15);

        // when
        long deleted = sut.deleteOlderThan(30);

        // then
        assertEquals(15, deleted);
        verify(preparedStatement).setInt(1, 30);
    }

    @Test
    void deleteOlderThan_noneExpired_returnsZero() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenReturn(0);

        // when
        long deleted = sut.deleteOlderThan(365);

        // then
        assertEquals(0, deleted);
    }

    @Test
    void deleteOlderThan_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.deleteOlderThan(30));
    }

    // ─── getVisibleEntries with most_accessed ────────────────────

    @Test
    void getVisibleEntries_mostAccessed_incrementsAccessCount() throws Exception {
        // given
        var byAccessCount = queryReturning("entry-1");
        var byRecency = queryReturning();
        var update = mock(PreparedStatement.class);
        stubStatementsByOrdering(byAccessCount, byRecency, update);

        // when
        List<UserMemoryEntry> entries = sut.getVisibleEntries(
                "user1", "agent1", null, "most_accessed", 50);

        // then
        assertEquals(1, entries.size());
        // The update PS should have addBatch and executeBatch called
        verify(update).setString(1, "entry-1");
        verify(update).addBatch();
        verify(update).executeBatch();
    }

    /**
     * The reserved recency slice is the whole point of the split — without it
     * {@code most_accessed} is self-reinforcing and a freshly written entry (access
     * count 0) can never enter a full window. The MongoDB store reserves it; the
     * PostgreSQL store used to issue one plain {@code ORDER BY access_count} query,
     * so the same recall order returned a different entry set per backend.
     */
    @Test
    void getVisibleEntries_mostAccessed_alsoQueriesByRecencyForTheReservedSlots() throws Exception {
        var byAccessCount = queryReturning("popular");
        var byRecency = queryReturning("brand-new");
        var update = mock(PreparedStatement.class);
        stubStatementsByOrdering(byAccessCount, byRecency, update);

        List<UserMemoryEntry> entries = sut.getVisibleEntries("user1", "agent1", null, "most_accessed", 50);

        assertEquals(List.of("popular", "brand-new"), entries.stream().map(UserMemoryEntry::id).toList());
        // 50 entries → 40 by access count, 10 reserved for recency.
        verify(byAccessCount).setInt(3, 40);
        verify(byRecency).setInt(3, 10);
    }

    /**
     * An entry that tops both orderings must be returned once and incremented once.
     */
    @Test
    void getVisibleEntries_mostAccessed_deduplicatesAcrossTheTwoPasses() throws Exception {
        var byAccessCount = queryReturning("entry-1");
        var byRecency = queryReturning("entry-1");
        var update = mock(PreparedStatement.class);
        stubStatementsByOrdering(byAccessCount, byRecency, update);

        List<UserMemoryEntry> entries = sut.getVisibleEntries("user1", "agent1", null, "most_accessed", 50);

        assertEquals(1, entries.size());
        verify(update, times(1)).addBatch();
    }

    /**
     * With a window of one, reserving a recency slot leaves ZERO slots for access
     * count — {@code most_accessed} would silently degrade into "most recent", the
     * exact opposite of the requested ordering.
     */
    @Test
    void getVisibleEntries_mostAccessed_windowOfOne_stillRanksByAccessCount() throws Exception {
        var byAccessCount = queryReturning("popular");
        var byRecency = queryReturning("brand-new");
        var update = mock(PreparedStatement.class);
        stubStatementsByOrdering(byAccessCount, byRecency, update);

        List<UserMemoryEntry> entries = sut.getVisibleEntries("user1", "agent1", null, "most_accessed", 1);

        assertEquals(List.of("popular"), entries.stream().map(UserMemoryEntry::id).toList());
        verify(byAccessCount).setInt(3, 1);
        // A zero-slot budget must not issue a query at all.
        verify(byRecency, never()).executeQuery();
    }

    /**
     * maxEntries <= 0 means "no limit" to the MongoDB store. Binding it straight
     * into {@code LIMIT ?} makes PostgreSQL return nothing (LIMIT 0) or raise an
     * error (negative LIMIT); {@code LIMIT NULL} is how PostgreSQL spells
     * unlimited.
     */
    @Test
    void getVisibleEntries_unlimitedWindow_bindsLimitNullNotZero() throws Exception {
        when(resultSet.next()).thenReturn(false);

        sut.getVisibleEntries("user1", "agent1", null, "most_recent", 0);

        verify(preparedStatement).setNull(3, Types.INTEGER);
        verify(preparedStatement, never()).setInt(eq(3), anyInt());
    }

    /**
     * Routes the two recall queries and the increment to distinct mocks so each
     * one's bound limit can be asserted independently.
     */
    private void stubStatementsByOrdering(PreparedStatement byAccessCount, PreparedStatement byRecency, PreparedStatement update)
            throws Exception {
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("UPDATE")) {
                return update;
            }
            return sql.contains("ORDER BY access_count DESC") ? byAccessCount : byRecency;
        });
    }

    /**
     * A PreparedStatement whose result set yields one row per supplied entry id.
     */
    private PreparedStatement queryReturning(String... entryIds) throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        if (entryIds.length == 0) {
            when(rs.next()).thenReturn(false);
        } else {
            when(rs.next()).thenReturn(true, buildTail(entryIds.length));
            when(rs.getString("id")).thenReturn(entryIds[0], Arrays.copyOfRange(entryIds, 1, entryIds.length));
        }
        when(rs.getString("user_id")).thenReturn("user1");
        when(rs.getString("key")).thenReturn("fav_color");
        when(rs.getString("value")).thenReturn("\"blue\"");
        when(rs.getString("category")).thenReturn("preference");
        when(rs.getString("visibility")).thenReturn("self");
        when(rs.getString("source_agent_id")).thenReturn("agent1");
        when(rs.getString("group_ids")).thenReturn("[]");
        when(rs.getString("source_conversation_id")).thenReturn("conv1");
        when(ps.executeQuery()).thenReturn(rs);
        return ps;
    }

    /** {@code next()} answers: one more TRUE per remaining row, then FALSE. */
    private static Boolean[] buildTail(int rowCount) {
        Boolean[] tail = new Boolean[rowCount];
        Arrays.fill(tail, Boolean.TRUE);
        tail[rowCount - 1] = Boolean.FALSE;
        return tail;
    }

    @Test
    void getVisibleEntries_mostRecent_doesNotIncrementAccessCount() throws Exception {
        // given
        when(resultSet.next()).thenReturn(false);

        // when
        List<UserMemoryEntry> entries = sut.getVisibleEntries(
                "user1", "agent1", null, "most_recent", 50);

        // then
        assertTrue(entries.isEmpty());
        // Should NOT create a second PS for access count update
        verify(connection, times(1)).prepareStatement(anyString());
    }

    // ─── getVisibleEntries with groupIds ────────────────────────

    @Test
    void getVisibleEntries_withGroupIds_includesGroupClause() throws Exception {
        // given
        when(resultSet.next()).thenReturn(false);

        // when
        sut.getVisibleEntries("user1", "agent1",
                List.of("group-a", "group-b"), "most_recent", 10);

        // then — params: userId=1, agentId=2, group-a=3, group-b=4, limit=5
        verify(preparedStatement).setString(1, "user1");
        verify(preparedStatement).setString(2, "agent1");
        verify(preparedStatement).setString(3, "group-a");
        verify(preparedStatement).setString(4, "group-b");
        verify(preparedStatement).setInt(5, 10);
    }

    @Test
    void getVisibleEntries_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.getVisibleEntries("user1", "agent1", null, "most_recent", 50));
    }

    // ─── resultSetToEntry edge cases ────────────────────────────

    @Test
    void resultSetToEntry_unknownVisibility_defaultsToSelf() throws Exception {
        // given — visibility is an unknown value
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("e1");
        when(resultSet.getString("user_id")).thenReturn("u1");
        when(resultSet.getString("key")).thenReturn("k1");
        when(resultSet.getString("value")).thenReturn("\"val\"");
        when(resultSet.getString("category")).thenReturn("fact");
        when(resultSet.getString("visibility")).thenReturn("INVALID_VALUE");
        when(resultSet.getString("source_agent_id")).thenReturn("a1");
        when(resultSet.getString("group_ids")).thenReturn("[]");
        when(resultSet.getString("source_conversation_id")).thenReturn("c1");
        when(resultSet.getBoolean("conflicted")).thenReturn(false);
        when(resultSet.getInt("access_count")).thenReturn(0);
        when(resultSet.getTimestamp("created_at")).thenReturn(null);
        when(resultSet.getTimestamp("updated_at")).thenReturn(null);

        // when
        Optional<UserMemoryEntry> result = sut.findEntryById("e1");

        // then
        assertTrue(result.isPresent());
        assertEquals(Visibility.self, result.get().visibility());
    }

    @Test
    void resultSetToEntry_nullValueJson_setsValueNull() throws Exception {
        // given
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("e1");
        when(resultSet.getString("user_id")).thenReturn("u1");
        when(resultSet.getString("key")).thenReturn("k1");
        when(resultSet.getString("value")).thenReturn(null);
        when(resultSet.getString("category")).thenReturn("fact");
        when(resultSet.getString("visibility")).thenReturn("self");
        when(resultSet.getString("source_agent_id")).thenReturn("a1");
        when(resultSet.getString("group_ids")).thenReturn(null);
        when(resultSet.getString("source_conversation_id")).thenReturn("c1");
        when(resultSet.getBoolean("conflicted")).thenReturn(false);
        when(resultSet.getInt("access_count")).thenReturn(0);
        when(resultSet.getTimestamp("created_at")).thenReturn(null);
        when(resultSet.getTimestamp("updated_at")).thenReturn(null);

        // when
        Optional<UserMemoryEntry> result = sut.findEntryById("e1");

        // then
        assertTrue(result.isPresent());
        assertNull(result.get().value());
        assertTrue(result.get().groupIds().isEmpty());
    }

    @Test
    void resultSetToEntry_invalidGroupIdsJson_keepsEmpty() throws Exception {
        // given — group_ids is invalid JSON
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("e1");
        when(resultSet.getString("user_id")).thenReturn("u1");
        when(resultSet.getString("key")).thenReturn("k1");
        when(resultSet.getString("value")).thenReturn("\"v\"");
        when(resultSet.getString("category")).thenReturn("fact");
        when(resultSet.getString("visibility")).thenReturn("global");
        when(resultSet.getString("source_agent_id")).thenReturn("a1");
        when(resultSet.getString("group_ids")).thenReturn("{not-valid-json");
        when(resultSet.getString("source_conversation_id")).thenReturn("c1");
        when(resultSet.getBoolean("conflicted")).thenReturn(false);
        when(resultSet.getInt("access_count")).thenReturn(5);
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(resultSet.getTimestamp("created_at")).thenReturn(ts);
        when(resultSet.getTimestamp("updated_at")).thenReturn(ts);

        // when
        Optional<UserMemoryEntry> result = sut.findEntryById("e1");

        // then
        assertTrue(result.isPresent());
        assertEquals(Visibility.global, result.get().visibility());
        assertTrue(result.get().groupIds().isEmpty());
        assertNotNull(result.get().createdAt());
        assertNotNull(result.get().updatedAt());
    }

    @Test
    void resultSetToEntry_nullVisibility_defaultsToSelf() throws Exception {
        // given
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("e1");
        when(resultSet.getString("user_id")).thenReturn("u1");
        when(resultSet.getString("key")).thenReturn("k1");
        when(resultSet.getString("value")).thenReturn("\"v\"");
        when(resultSet.getString("category")).thenReturn("fact");
        when(resultSet.getString("visibility")).thenReturn(null);
        when(resultSet.getString("source_agent_id")).thenReturn("a1");
        when(resultSet.getString("group_ids")).thenReturn("[]");
        when(resultSet.getString("source_conversation_id")).thenReturn("c1");
        when(resultSet.getBoolean("conflicted")).thenReturn(false);
        when(resultSet.getInt("access_count")).thenReturn(0);
        when(resultSet.getTimestamp("created_at")).thenReturn(null);
        when(resultSet.getTimestamp("updated_at")).thenReturn(null);

        // when
        Optional<UserMemoryEntry> result = sut.findEntryById("e1");

        // then
        assertTrue(result.isPresent());
        assertEquals(Visibility.self, result.get().visibility());
    }

    // ─── readProperties with null value ─────────────────────────

    @Test
    void readProperties_nullValue_skipsEntry() throws Exception {
        // given
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("key")).thenReturn(null);
        when(resultSet.getString("value")).thenReturn(null);

        // when
        Properties result = sut.readProperties("user1");

        // then — should return null because no valid entries
        assertNull(result);
    }

    // ─── deleteProperties SQL exception ─────────────────────────

    @Test
    void deleteProperties_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.deleteProperties("user1"));
    }

    // ─── deleteAllForUser SQL exception ─────────────────────────

    @Test
    void deleteAllForUser_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.deleteAllForUser("user1"));
    }

    // ─── countEntries SQL exception ─────────────────────────────

    @Test
    void countEntries_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.countEntries("user1"));
    }

    @Test
    void countEntries_noRows_returnsZero() throws Exception {
        // given
        when(resultSet.next()).thenReturn(false);

        // when
        long count = sut.countEntries("user1");

        // then
        assertEquals(0, count);
    }

    // ─── ensureSchema failure ───────────────────────────────────

    @Test
    void ensureSchema_sqlException_logsButDoesNotThrow() throws Exception {
        // given
        DataSource failDs = mock(DataSource.class);
        when(failDs.getConnection()).thenThrow(new SQLException("Schema error"));

        @SuppressWarnings("unchecked")
        Instance<DataSource> failInstance = mock(Instance.class);
        when(failInstance.get()).thenReturn(failDs);

        var freshStore = new PostgresUserMemoryStore(failInstance);

        // when/then — ensureSchema logs the error but does not throw
        // (unlike other stores, this one catches the exception)
        // The next call that tries to get a connection will also fail
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> freshStore.readProperties("user1"));
    }

    // ─── filterEntries with blank query ────────────────────────

    @Test
    void filterEntries_blankQuery_returnsAll() throws Exception {
        // given
        when(resultSet.next()).thenReturn(false);

        // when
        List<UserMemoryEntry> result = sut.filterEntries("user1", "   ");

        // then — blank query should fall through to getAllEntries
        assertTrue(result.isEmpty());
    }

    @Test
    void filterEntries_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.filterEntries("user1", "search_term"));
    }

    // ─── getEntriesByCategory SQL exception ─────────────────────

    @Test
    void getEntriesByCategory_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.getEntriesByCategory("user1", "fact"));
    }

    // ─── getByKey SQL exception ─────────────────────────────────

    @Test
    void getByKey_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.getByKey("user1", "key1"));
    }

    // ─── getAllEntries SQL exception ─────────────────────────────

    @Test
    void getAllEntries_sqlException_throwsResourceStoreException() throws Exception {
        // given
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        // when/then
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> sut.getAllEntries("user1"));
    }

    // ─── Helpers ────────────────────────────────────────────────

    private void setupResultSetForEntry() throws Exception {
        when(resultSet.getString("id")).thenReturn("entry-1");
        when(resultSet.getString("user_id")).thenReturn("user1");
        when(resultSet.getString("key")).thenReturn("fav_color");
        when(resultSet.getString("value")).thenReturn("\"blue\"");
        when(resultSet.getString("category")).thenReturn("preference");
        when(resultSet.getString("visibility")).thenReturn("self");
        when(resultSet.getString("source_agent_id")).thenReturn("agent1");
        when(resultSet.getString("group_ids")).thenReturn("[]");
        when(resultSet.getString("source_conversation_id")).thenReturn("conv1");
        when(resultSet.getBoolean("conflicted")).thenReturn(false);
        when(resultSet.getInt("access_count")).thenReturn(0);
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(resultSet.getTimestamp("created_at")).thenReturn(ts);
        when(resultSet.getTimestamp("updated_at")).thenReturn(ts);
    }
}
