/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
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
        // The constructor calls initSchema which creates tables
        verify(statement, times(3)).execute(anyString());
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

    // Simple test POJO
    record TestConfig(String name) {
    }
}
