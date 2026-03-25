package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStorage;
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

    // Simple test POJO
    record TestConfig(String name) {
    }
}
