/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PostgresResourceStorage} with mocked JDBC connections.
 */
class PostgresResourceStorageTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    private IJsonSerialization jsonSerialization;
    private PostgresResourceStorage<TestConfig> storage;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        jsonSerialization = mock(IJsonSerialization.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        storage = new PostgresResourceStorage<>(dataSource, "test_collection", jsonSerialization, TestConfig.class);
    }

    @Test
    void shouldInitSchemaOnConstruction() throws Exception {
        // The constructor calls initSchema which creates the two tables plus the
        // shared indexes.
        var executed = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).execute(executed.capture());

        List<String> statements = executed.getAllValues();
        assertTrue(statements.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS resources ")));
        assertTrue(statements.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS resources_history ")));
    }

    @Test
    void shouldPutCollectionNameFirstInThePrimaryKey() throws Exception {
        var executed = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).execute(executed.capture());

        String createResources = executed.getAllValues().stream()
                .filter(s -> s.contains("CREATE TABLE IF NOT EXISTS resources "))
                .findFirst().orElseThrow();

        // Every query filters on collection_name; a btree is only usable from its
        // leading column, so (id, collection_name) meant none of them could use the
        // primary key.
        assertTrue(createResources.contains("PRIMARY KEY (collection_name, id)"), createResources);
    }

    @Test
    void shouldCreateTheIndexesEveryQueryNeeds() throws Exception {
        var executed = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).execute(executed.capture());
        String all = String.join("\n", executed.getAllValues());

        // Migration path for tables created before the primary-key reorder — their
        // PK stays (id, collection_name), so collection_name needs its own index.
        assertTrue(all.contains("idx_resources_collection_name"), all);
        assertTrue(all.contains("idx_resources_history_collection_name"), all);
        // Backs the @? jsonpath lookups used for reverse "who references X" queries.
        assertTrue(all.contains("gin (data jsonb_path_ops)"), all);
        // Idempotent: existing deployments re-run this on every startup.
        assertTrue(all.contains("CREATE INDEX IF NOT EXISTS"), all);
    }

    @Test
    void shouldMaterialiseCallerSuppliedIndexHintsAsExpressionIndexes() throws Exception {
        Statement indexStatement = mock(Statement.class);
        Connection indexConnection = mock(Connection.class);
        DataSource indexDataSource = mock(DataSource.class);
        when(indexDataSource.getConnection()).thenReturn(indexConnection);
        when(indexConnection.createStatement()).thenReturn(indexStatement);

        new PostgresResourceStorage<>(indexDataSource, "descriptors", jsonSerialization, TestConfig.class, "name", "workflowSteps.config.uri");

        var executed = ArgumentCaptor.forClass(String.class);
        verify(indexStatement, atLeastOnce()).execute(executed.capture());
        String all = String.join("\n", executed.getAllValues());

        // The factory used to accept the hints and drop them on the floor.
        assertTrue(all.contains("idx_resources_field_name"), all);
        assertTrue(all.contains("(collection_name, (data ->> 'name'))"), all);
        // Dotted paths address values inside arrays — a btree expression index
        // cannot represent those, the GIN index serves them instead.
        assertFalse(all.contains("idx_resources_field_workflowsteps"), all);
    }

    @Test
    void shouldNotFailStartupWhenIndexCreationIsRejected() throws Exception {
        Statement indexStatement = mock(Statement.class);
        Connection indexConnection = mock(Connection.class);
        DataSource indexDataSource = mock(DataSource.class);
        when(indexDataSource.getConnection()).thenReturn(indexConnection);
        when(indexConnection.createStatement()).thenReturn(indexStatement);
        // Tables must still be created; only the index statements fail.
        doThrow(new SQLException("permission denied")).when(indexStatement).execute(contains("CREATE INDEX"));

        assertDoesNotThrow(() -> new PostgresResourceStorage<>(indexDataSource, "descriptors", jsonSerialization, TestConfig.class, "name"));
    }

    // ─── dotted JSON paths ─────────────────────────────────────

    @Test
    void containmentJsonPath_traversesDottedPathsIntoArrays() {
        // `data->'workflowSteps.config.uri'` looked up a LITERAL top-level key
        // spelled with dots, which never exists — the query returned nothing,
        // forever, while MongoDB traversed the same path correctly.
        assertEquals("$.\"workflowSteps\"[*].\"config\"[*].\"uri\"[*] ? (@ == \"eddi://x?version=1\")",
                PostgresResourceStorage.toContainmentJsonPath("workflowSteps.config.uri", "eddi://x?version=1"));
    }

    @Test
    void containmentJsonPath_handlesSingleSegmentArrayFields() {
        assertEquals("$.\"workflows\"[*] ? (@ == \"eddi://wf?version=2\")",
                PostgresResourceStorage.toContainmentJsonPath("workflows", "eddi://wf?version=2"));
    }

    @Test
    void containmentJsonPath_escapesTheValueSoItCannotBreakOutOfTheLiteral() {
        String path = PostgresResourceStorage.toContainmentJsonPath("field", "a\"b\\c\nd");

        assertEquals("$.\"field\"[*] ? (@ == \"a\\\"b\\\\c\\nd\")", path);
    }

    @Test
    void findResourceIdsContaining_bindsTheJsonPathAsAParameter() throws Exception {
        when(resultSet.next()).thenReturn(false);

        storage.findResourceIdsContaining("workflowSteps.config.uri", "eddi://out?version=1");

        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        // The path (and the value inside it) must never be concatenated into SQL.
        assertTrue(sql.getValue().contains("data @? ?::jsonpath"), sql.getValue());
        assertFalse(sql.getValue().contains("workflowSteps"), sql.getValue());
        verify(preparedStatement).setString(2, PostgresResourceStorage.toContainmentJsonPath("workflowSteps.config.uri", "eddi://out?version=1"));
    }

    @Test
    void findResources_traversesDottedFilterAndSortPaths() throws Exception {
        when(resultSet.next()).thenReturn(false);

        var filter = new IResourceFilter.QueryFilter("meta.owner", "someone");
        var queryFilters = new IResourceFilter.QueryFilters(List.of(filter));

        storage.findResources(new IResourceFilter.QueryFilters[]{queryFilters}, "meta.updatedAt", 0, 10);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("data -> 'meta' ->> 'owner'"), sql.getValue());
        assertTrue(sql.getValue().contains("ORDER BY data -> 'meta' ->> 'updatedAt' DESC"), sql.getValue());
    }

    // ─── batch read ────────────────────────────────────────────

    @Test
    void readMany_readsThePageInOneStatementAndKeepsRequestOrder() throws Exception {
        when(resultSet.next()).thenReturn(true, true, false);
        // Deliberately returned in the opposite order to the request.
        when(resultSet.getString("id")).thenReturn("id-a", "id-b");
        when(resultSet.getInt("version")).thenReturn(1, 1);
        when(resultSet.getString("data")).thenReturn("{}", "{}");

        var results = storage.readMany(List.of(resourceId("id-b", 1), resourceId("id-a", 1)));

        assertEquals(List.of("id-b", "id-a"), results.stream().map(IResourceStorage.IResource::getId).toList());
        verify(connection, times(1)).prepareStatement(anyString());
    }

    @Test
    void readMany_emptyInputIssuesNoQuery() throws Exception {
        assertTrue(storage.readMany(List.of()).isEmpty());
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void readMany_skipsIdsThatNoLongerExist() throws Exception {
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("id")).thenReturn("id-a");
        when(resultSet.getInt("version")).thenReturn(1);
        when(resultSet.getString("data")).thenReturn("{}");

        var results = storage.readMany(List.of(resourceId("id-a", 1), resourceId("id-gone", 1)));

        assertEquals(1, results.size());
        assertEquals("id-a", results.getFirst().getId());
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    // ─── atomic history + current writes ───────────────────────

    @Test
    void storeHistoryAndUpdate_commitsBothWritesInOneTransaction() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"name\":\"value1\"}");
        var resource = storage.newResource("11111111-1111-1111-1111-111111111111", 2, config);
        var history = storage.newHistoryResourceFor(resource, false);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        storage.storeHistoryAndUpdate(history, resource, 1);

        InOrder inOrder = inOrder(connection);
        inOrder.verify(connection).setAutoCommit(false);
        inOrder.verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void storeHistoryAndUpdate_rollsBackAndReportsAConcurrentEdit() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"name\":\"value1\"}");
        var resource = storage.newResource("11111111-1111-1111-1111-111111111111", 2, config);
        var history = storage.newHistoryResourceFor(resource, false);
        // history insert succeeds, the version-checked update matches nothing
        when(preparedStatement.executeUpdate()).thenReturn(1, 0);

        assertThrows(IResourceStore.ResourceModifiedException.class, () -> storage.storeHistoryAndUpdate(history, resource, 1));

        // The archived row must not survive an update that never happened.
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void storeHistoryAndRemove_commitsBothWritesInOneTransaction() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"name\":\"value1\"}");
        var resource = storage.newResource("11111111-1111-1111-1111-111111111111", 1, config);
        var history = storage.newHistoryResourceFor(resource, true);

        storage.storeHistoryAndRemove(history, "11111111-1111-1111-1111-111111111111");

        InOrder inOrder = inOrder(connection);
        inOrder.verify(connection).setAutoCommit(false);
        inOrder.verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void storeHistoryAndRemove_rollsBackWhenTheRemovalFails() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"name\":\"value1\"}");
        var resource = storage.newResource("11111111-1111-1111-1111-111111111111", 1, config);
        var history = storage.newHistoryResourceFor(resource, true);
        when(preparedStatement.executeUpdate()).thenReturn(1).thenThrow(new SQLException("connection lost"));

        assertThrows(RuntimeException.class, () -> storage.storeHistoryAndRemove(history, "11111111-1111-1111-1111-111111111111"));

        // Otherwise the resource is archived as deleted while its live row remains.
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void shouldCreateNewResourceWithUUID() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"name\":\"value1\"}");

        IResourceStorage.IResource<TestConfig> resource = storage.newResource(config);

        assertNotNull(resource);
        assertNotNull(resource.getId());
        assertEquals(1, resource.getVersion());
    }

    @Test
    void shouldCreateResourceWithSpecificIdAndVersion() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"name\":\"value1\"}");

        IResourceStorage.IResource<TestConfig> resource = storage.newResource("custom-id", 3, config);

        assertEquals("custom-id", resource.getId());
        assertEquals(3, resource.getVersion());
    }

    @Test
    void shouldStoreResource() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"name\":\"value1\"}");

        IResourceStorage.IResource<TestConfig> resource = storage.newResource(config);

        // Reset interaction count from constructor
        reset(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        storage.store(resource);

        verify(preparedStatement).setString(1, resource.getId());
        verify(preparedStatement).setString(2, "test_collection");
        verify(preparedStatement).setInt(3, 1);
        verify(preparedStatement).setString(4, "{\"name\":\"value1\"}");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void shouldReadResource() throws Exception {
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("id1");
        when(resultSet.getInt("version")).thenReturn(1);
        when(resultSet.getString("data")).thenReturn("{\"name\":\"val\"}");

        IResourceStorage.IResource<TestConfig> resource = storage.read("id1", 1);

        assertNotNull(resource);
        assertEquals("id1", resource.getId());
        assertEquals(1, resource.getVersion());

        verify(preparedStatement).setString(1, "id1");
        verify(preparedStatement).setString(2, "test_collection");
        verify(preparedStatement).setInt(3, 1);
    }

    @Test
    void shouldReturnNullWhenResourceNotFound() throws Exception {
        when(resultSet.next()).thenReturn(false);

        IResourceStorage.IResource<TestConfig> resource = storage.read("nonexistent", 1);

        assertNull(resource);
    }

    @Test
    void shouldRemoveResource() throws Exception {
        reset(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        storage.remove("id1");

        verify(preparedStatement).setString(1, "id1");
        verify(preparedStatement).setString(2, "test_collection");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void shouldRemoveAllPermanently() throws Exception {
        reset(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        storage.removeAllPermanently("id1");

        // Two DELETE statements (current + history)
        verify(preparedStatement, times(2)).setString(1, "id1");
        verify(preparedStatement, times(2)).setString(2, "test_collection");
        verify(preparedStatement, times(2)).executeUpdate();
        verify(connection).commit();
    }

    @Test
    void shouldReadHistory() throws Exception {
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("id1");
        when(resultSet.getInt("version")).thenReturn(1);
        when(resultSet.getString("data")).thenReturn("{\"name\":\"old\"}");
        when(resultSet.getBoolean("deleted")).thenReturn(false);

        IResourceStorage.IHistoryResource<TestConfig> history = storage.readHistory("id1", 1);

        assertNotNull(history);
        assertEquals("id1", history.getId());
        assertEquals(1, history.getVersion());
        assertFalse(history.isDeleted());
    }

    @Test
    void shouldReturnNullWhenHistoryNotFound() throws Exception {
        when(resultSet.next()).thenReturn(false);

        IResourceStorage.IHistoryResource<TestConfig> history = storage.readHistory("id1", 99);

        assertNull(history);
    }

    @Test
    void shouldStoreHistoryWithDeletedFlag() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"name\":\"value1\"}");

        IResourceStorage.IResource<TestConfig> resource = storage.newResource("id1", 1, config);
        IResourceStorage.IHistoryResource<TestConfig> history = storage.newHistoryResourceFor(resource, true);

        assertTrue(history.isDeleted());
        assertEquals("id1", history.getId());
        assertEquals(1, history.getVersion());
    }

    @Test
    void shouldGetCurrentVersion() throws Exception {
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt("version")).thenReturn(5);

        Integer version = storage.getCurrentVersion("id1");

        assertEquals(5, version);
    }

    @Test
    void shouldReturnMinusOneForNonExistentVersion() throws Exception {
        when(resultSet.next()).thenReturn(false);

        Integer version = storage.getCurrentVersion("nonexistent");

        assertEquals(-1, version);
    }

    @Test
    void shouldDeserializeResourceData() throws Exception {
        TestConfig expected = new TestConfig("deserialized");
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("id1");
        when(resultSet.getInt("version")).thenReturn(1);
        when(resultSet.getString("data")).thenReturn("{\"name\":\"deserialized\"}");
        when(jsonSerialization.deserialize("{\"name\":\"deserialized\"}", TestConfig.class)).thenReturn(expected);

        IResourceStorage.IResource<TestConfig> resource = storage.read("id1", 1);
        TestConfig data = resource.getData();

        assertEquals(expected, data);
    }

    // ─── createNew ────────────────────────────────────────────────

    @Test
    void shouldCreateNewResource() throws Exception {
        TestConfig config = new TestConfig("val");
        when(jsonSerialization.serialize(config)).thenReturn("{\"name\":\"val\"}");

        IResourceStorage.IResource<TestConfig> resource = storage.newResource("new-id", 1, config);

        reset(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        storage.createNew(resource);

        verify(preparedStatement).setString(1, "new-id");
        verify(preparedStatement).setString(2, "test_collection");
        verify(preparedStatement).setInt(3, 1);
        verify(preparedStatement).setString(4, "{\"name\":\"val\"}");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void createNew_sqlException_throwsRuntimeException() throws Exception {
        TestConfig config = new TestConfig("val");
        when(jsonSerialization.serialize(config)).thenReturn("{}");
        IResourceStorage.IResource<TestConfig> resource = storage.newResource("id1", 1, config);

        reset(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("Duplicate"));

        assertThrows(RuntimeException.class, () -> storage.createNew(resource));
    }

    // ─── storeHistory ──────────────────────────────────────────

    @Test
    void shouldStoreHistory() throws Exception {
        TestConfig config = new TestConfig("val");
        when(jsonSerialization.serialize(config)).thenReturn("{\"name\":\"val\"}");
        IResourceStorage.IResource<TestConfig> resource = storage.newResource("id1", 2, config);
        IResourceStorage.IHistoryResource<TestConfig> history = storage.newHistoryResourceFor(resource, false);

        reset(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        storage.store(history);

        verify(preparedStatement).setString(1, "id1");
        verify(preparedStatement).setString(2, "test_collection");
        verify(preparedStatement).setInt(3, 2);
        verify(preparedStatement).setString(4, "{\"name\":\"val\"}");
        verify(preparedStatement).setBoolean(5, false);
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void storeHistory_sqlException_throwsRuntimeException() throws Exception {
        TestConfig config = new TestConfig("v");
        when(jsonSerialization.serialize(config)).thenReturn("{}");
        IResourceStorage.IResource<TestConfig> resource = storage.newResource("id1", 1, config);
        IResourceStorage.IHistoryResource<TestConfig> history = storage.newHistoryResourceFor(resource, true);

        reset(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        assertThrows(RuntimeException.class, () -> storage.store(history));
    }

    // ─── readHistoryLatest ─────────────────────────────────────

    @Test
    void shouldReadHistoryLatest() throws Exception {
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("id1");
        when(resultSet.getInt("version")).thenReturn(3);
        when(resultSet.getString("data")).thenReturn("{\"name\":\"latest\"}");
        when(resultSet.getBoolean("deleted")).thenReturn(true);

        IResourceStorage.IHistoryResource<TestConfig> latest = storage.readHistoryLatest("id1");

        assertNotNull(latest);
        assertEquals("id1", latest.getId());
        assertEquals(3, latest.getVersion());
        assertTrue(latest.isDeleted());
    }

    @Test
    void readHistoryLatest_notFound_returnsNull() throws Exception {
        when(resultSet.next()).thenReturn(false);

        IResourceStorage.IHistoryResource<TestConfig> latest = storage.readHistoryLatest("missing");

        assertNull(latest);
    }

    @Test
    void readHistoryLatest_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        assertThrows(RuntimeException.class, () -> storage.readHistoryLatest("id1"));
    }

    // ─── getCurrentVersion UUID error handling ─────────────────

    @Test
    void getCurrentVersion_invalidUuid_returnsMinusOne() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(
                new SQLException("invalid input syntax for type uuid"));

        Integer version = storage.getCurrentVersion("not-a-uuid");

        assertEquals(-1, version);
    }

    @Test
    void getCurrentVersion_otherSqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("Other error"));

        assertThrows(RuntimeException.class, () -> storage.getCurrentVersion("id1"));
    }

    @Test
    void getCurrentVersion_nullMessage_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException((String) null));

        assertThrows(RuntimeException.class, () -> storage.getCurrentVersion("id1"));
    }

    // ─── findResourceIdsContaining ─────────────────────────────

    @Test
    void findResourceIdsContaining_returnsResults() throws Exception {
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("id")).thenReturn("id1");
        when(resultSet.getInt("version")).thenReturn(1);

        var results = storage.findResourceIdsContaining("actions", "my_action");

        assertEquals(1, results.size());
        assertEquals("id1", results.getFirst().getId());
        assertEquals(1, results.getFirst().getVersion());
    }

    @Test
    void findResourceIdsContaining_emptyResult() throws Exception {
        when(resultSet.next()).thenReturn(false);

        var results = storage.findResourceIdsContaining("actions", "missing_action");

        assertTrue(results.isEmpty());
    }

    @Test
    void findResourceIdsContaining_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        assertThrows(RuntimeException.class,
                () -> storage.findResourceIdsContaining("actions", "val"));
    }

    // ─── findHistoryResourceIdsContaining ──────────────────────

    @Test
    void findHistoryResourceIdsContaining_returnsResults() throws Exception {
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("id")).thenReturn("h1", "h2");
        when(resultSet.getInt("version")).thenReturn(1, 2);

        var results = storage.findHistoryResourceIdsContaining("field", "value");

        assertEquals(2, results.size());
    }

    @Test
    void findHistoryResourceIdsContaining_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        assertThrows(RuntimeException.class,
                () -> storage.findHistoryResourceIdsContaining("field", "val"));
    }

    // ─── findResources with filter types ───────────────────────

    @Test
    void findResources_withStringFilter() throws Exception {
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("id")).thenReturn("id1");
        when(resultSet.getInt("version")).thenReturn(1);

        var filter = new ai.labs.eddi.datastore.IResourceFilter.QueryFilter("name", "test.*");
        var queryFilters = new ai.labs.eddi.datastore.IResourceFilter.QueryFilters(
                java.util.List.of(filter));

        var results = storage.findResources(
                new ai.labs.eddi.datastore.IResourceFilter.QueryFilters[]{queryFilters},
                "name", 0, 10);

        assertEquals(1, results.size());
    }

    @Test
    void findResources_zeroLimit_meansUnlimitedUpToCeiling() throws Exception {
        when(resultSet.next()).thenReturn(false);

        var filter = new ai.labs.eddi.datastore.IResourceFilter.QueryFilter("name", "test.*");
        var queryFilters = new ai.labs.eddi.datastore.IResourceFilter.QueryFilters(
                java.util.List.of(filter));

        storage.findResources(
                new ai.labs.eddi.datastore.IResourceFilter.QueryFilters[]{queryFilters},
                "name", 0, 0);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        // limit 0 is the "no caller limit" sentinel — it must not become LIMIT 20
        assertTrue(sql.getValue().contains("LIMIT " + IResourceStorage.MAX_RESULT_LIMIT),
                "expected the safety ceiling in: " + sql.getValue());
    }

    @Test
    void findResources_oversizedLimit_clampedToCeiling() throws Exception {
        when(resultSet.next()).thenReturn(false);

        var filter = new ai.labs.eddi.datastore.IResourceFilter.QueryFilter("name", "test.*");
        var queryFilters = new ai.labs.eddi.datastore.IResourceFilter.QueryFilters(
                java.util.List.of(filter));

        storage.findResources(
                new ai.labs.eddi.datastore.IResourceFilter.QueryFilters[]{queryFilters},
                "name", 0, IResourceStorage.MAX_RESULT_LIMIT * 2);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("LIMIT " + IResourceStorage.MAX_RESULT_LIMIT),
                "expected the safety ceiling in: " + sql.getValue());
    }

    @Test
    void findResources_withBooleanFilter() throws Exception {
        when(resultSet.next()).thenReturn(false);

        var filter = new ai.labs.eddi.datastore.IResourceFilter.QueryFilter("enabled", true);
        var queryFilters = new ai.labs.eddi.datastore.IResourceFilter.QueryFilters(
                java.util.List.of(filter));

        var results = storage.findResources(
                new ai.labs.eddi.datastore.IResourceFilter.QueryFilters[]{queryFilters},
                null, 0, 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void findResources_withOtherFilter() throws Exception {
        when(resultSet.next()).thenReturn(false);

        // Integer filter — goes through the else branch (toString)
        var filter = new ai.labs.eddi.datastore.IResourceFilter.QueryFilter("count", 42);
        var queryFilters = new ai.labs.eddi.datastore.IResourceFilter.QueryFilters(
                java.util.List.of(filter));

        var results = storage.findResources(
                new ai.labs.eddi.datastore.IResourceFilter.QueryFilters[]{queryFilters},
                null, 0, 0); // limit < 1 should default to 20

        assertTrue(results.isEmpty());
    }

    @Test
    void findResources_withOrConnector() throws Exception {
        when(resultSet.next()).thenReturn(false);

        var filter1 = new ai.labs.eddi.datastore.IResourceFilter.QueryFilter("name", "a");
        var filter2 = new ai.labs.eddi.datastore.IResourceFilter.QueryFilter("name", "b");
        var queryFilters = new ai.labs.eddi.datastore.IResourceFilter.QueryFilters(
                ai.labs.eddi.datastore.IResourceFilter.QueryFilters.ConnectingType.OR,
                java.util.List.of(filter1, filter2));

        var results = storage.findResources(
                new ai.labs.eddi.datastore.IResourceFilter.QueryFilters[]{queryFilters},
                "name", 5, 10);

        assertTrue(results.isEmpty());
    }

    @Test
    void findResources_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        var queryFilters = new ai.labs.eddi.datastore.IResourceFilter.QueryFilters(java.util.List.of());

        assertThrows(RuntimeException.class,
                () -> storage.findResources(
                        new ai.labs.eddi.datastore.IResourceFilter.QueryFilters[]{queryFilters},
                        null, 0, 10));
    }

    // ─── removeAllPermanently — rollback on error ──────────────

    @Test
    void removeAllPermanently_rollbackOnError() throws Exception {
        reset(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        // First delete succeeds, second throws
        when(preparedStatement.executeUpdate())
                .thenReturn(1)
                .thenThrow(new SQLException("History delete failed"));

        assertThrows(RuntimeException.class, () -> storage.removeAllPermanently("id1"));
        verify(connection).rollback();
    }

    // ─── checkInternalResource — wrong type ────────────────────

    @Test
    void store_wrongResourceType_throwsIllegalArgumentException() {
        @SuppressWarnings("unchecked")
        IResourceStorage.IResource<TestConfig> fakeResource = mock(IResourceStorage.IResource.class);

        assertThrows(IllegalArgumentException.class, () -> storage.store(fakeResource));
    }

    @Test
    void storeHistory_wrongResourceType_throwsIllegalArgumentException() {
        @SuppressWarnings("unchecked")
        IResourceStorage.IHistoryResource<TestConfig> fakeHistory = mock(IResourceStorage.IHistoryResource.class);

        assertThrows(IllegalArgumentException.class, () -> storage.store(fakeHistory));
    }

    @Test
    void newHistoryResourceFor_wrongResourceType_throwsIllegalArgumentException() {
        @SuppressWarnings("unchecked")
        IResourceStorage.IResource<TestConfig> fakeResource = mock(IResourceStorage.IResource.class);

        assertThrows(IllegalArgumentException.class, () -> storage.newHistoryResourceFor(fakeResource, false));
    }

    // ─── read/remove SQL exceptions ────────────────────────────

    @Test
    void read_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("Read error"));

        assertThrows(RuntimeException.class, () -> storage.read("id1", 1));
    }

    @Test
    void remove_sqlException_throwsRuntimeException() throws Exception {
        reset(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("Delete error"));

        assertThrows(RuntimeException.class, () -> storage.remove("id1"));
    }

    @Test
    void store_sqlException_throwsRuntimeException() throws Exception {
        TestConfig config = new TestConfig("v");
        when(jsonSerialization.serialize(config)).thenReturn("{}");
        IResourceStorage.IResource<TestConfig> resource = storage.newResource("id1", 1, config);

        reset(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("Store error"));

        assertThrows(RuntimeException.class, () -> storage.store(resource));
    }

    @Test
    void readHistory_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("History error"));

        assertThrows(RuntimeException.class, () -> storage.readHistory("id1", 1));
    }

    // ─── Resource.getData deserializes correctly ───────────────

    @Test
    void historyResource_getData_deserializesJson() throws Exception {
        TestConfig expected = new TestConfig("historical");
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("id1");
        when(resultSet.getInt("version")).thenReturn(2);
        when(resultSet.getString("data")).thenReturn("{\"name\":\"historical\"}");
        when(resultSet.getBoolean("deleted")).thenReturn(false);
        when(jsonSerialization.deserialize("{\"name\":\"historical\"}", TestConfig.class)).thenReturn(expected);

        IResourceStorage.IHistoryResource<TestConfig> history = storage.readHistory("id1", 2);
        TestConfig data = history.getData();

        assertEquals(expected, data);
    }

    // ─── storeIfFieldEquals (conditional CAS: deleted vs mismatch) ───

    private static final String VALID_UUID = "11111111-1111-1111-1111-111111111111";

    @Test
    void storeIfFieldEquals_success_whenUpdateAffectsARow() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"state\":\"AWAITING_APPROVAL\"}");
        IResourceStorage.IResource<TestConfig> resource = storage.newResource(VALID_UUID, 2, config);

        // The conditional UPDATE matches (field equals the expected value).
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertDoesNotThrow(() -> storage.storeIfFieldEquals(resource, "state", "AWAITING_APPROVAL"));
        verify(preparedStatement).executeUpdate();
        // No existence probe when the UPDATE succeeded.
        verify(preparedStatement, never()).executeQuery();
    }

    @Test
    void storeIfFieldEquals_deleted_throwsResourceNotFoundException() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"state\":\"AWAITING_APPROVAL\"}");
        IResourceStorage.IResource<TestConfig> resource = storage.newResource(VALID_UUID, 2, config);

        // UPDATE affects 0 rows → existence probe finds NO row → the resource was
        // deleted.
        when(preparedStatement.executeUpdate()).thenReturn(0);
        when(resultSet.next()).thenReturn(false);

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> storage.storeIfFieldEquals(resource, "state", "AWAITING_APPROVAL"));
    }

    @Test
    void storeIfFieldEquals_fieldMismatch_throwsResourceModifiedException() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{\"state\":\"AWAITING_APPROVAL\"}");
        IResourceStorage.IResource<TestConfig> resource = storage.newResource(VALID_UUID, 2, config);

        // UPDATE affects 0 rows → existence probe finds a row → the row exists but the
        // field value no longer matches (concurrent state change / lost CAS).
        when(preparedStatement.executeUpdate()).thenReturn(0);
        when(resultSet.next()).thenReturn(true);

        assertThrows(IResourceStore.ResourceModifiedException.class,
                () -> storage.storeIfFieldEquals(resource, "state", "AWAITING_APPROVAL"));
    }

    @Test
    void storeIfFieldEquals_traversesADottedFieldPath() throws Exception {
        TestConfig config = new TestConfig("value1");
        when(jsonSerialization.serialize(config)).thenReturn("{}");
        IResourceStorage.IResource<TestConfig> resource = storage.newResource(VALID_UUID, 2, config);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        storage.storeIfFieldEquals(resource, "meta.state", "AWAITING_APPROVAL");

        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        // MongoDB resolves a dotted field name in its filter; binding it as a
        // literal key here would compare against a key spelled with a dot, which
        // never exists — the CAS would report a spurious conflict.
        assertTrue(sql.getValue().contains("data -> 'meta' ->> 'state' = ?"), sql.getValue());
    }

    // Simple test POJO
    record TestConfig(String name) {
    }
}
