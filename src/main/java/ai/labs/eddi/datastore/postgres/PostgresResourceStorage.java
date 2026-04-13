package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;

/**
 * PostgreSQL implementation of {@link IResourceStorage}.
 * <p>
 * Stores resource content as JSONB in a shared {@code resources} table,
 * partitioned by {@code collection_name}. History (previous versions and
 * soft-deletes) is stored in {@code resources_history}.
 * <p>
 * Schema is auto-created via {@code CREATE TABLE IF NOT EXISTS}.
 *
 * @param <T>
 *            the resource document type
 */
public class PostgresResourceStorage<T> implements IResourceStorage<T> {

    private static final String CREATE_RESOURCES_TABLE = """
            CREATE TABLE IF NOT EXISTS resources (
                id UUID NOT NULL,
                collection_name TEXT NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                data JSONB NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                PRIMARY KEY (id, collection_name)
            )
            """;

    private static final String CREATE_HISTORY_TABLE = """
            CREATE TABLE IF NOT EXISTS resources_history (
                id UUID NOT NULL,
                collection_name TEXT NOT NULL,
                version INTEGER NOT NULL,
                data JSONB NOT NULL,
                deleted BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                PRIMARY KEY (id, collection_name, version)
            )
            """;

    private static final String CREATE_HISTORY_INDEX = "CREATE INDEX IF NOT EXISTS idx_resources_history_id_collection "
            + "ON resources_history (id, collection_name)";

    private final DataSource dataSource;
    private final String collectionName;
    private final IJsonSerialization jsonSerialization;
    private final Class<T> documentType;

    public PostgresResourceStorage(DataSource dataSource, String collectionName, IJsonSerialization jsonSerialization, Class<T> documentType) {
        checkNotNull(dataSource, "dataSource");
        checkNotNull(collectionName, "collectionName");
        this.dataSource = dataSource;
        this.collectionName = collectionName;
        this.jsonSerialization = jsonSerialization;
        this.documentType = documentType;

        initSchema();
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_RESOURCES_TABLE);
            stmt.execute(CREATE_HISTORY_TABLE);
            stmt.execute(CREATE_HISTORY_INDEX);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize PostgreSQL schema", e);
        }
    }

    @Override
    public IResource<T> newResource(T content) throws IOException {
        String json = jsonSerialization.serialize(content);
        String id = UUID.randomUUID().toString();
        return new Resource(id, 1, json);
    }

    @Override
    public IResource<T> newResource(String id, Integer version, T content) throws IOException {
        String json = jsonSerialization.serialize(content);
        return new Resource(id, version, json);
    }

    @Override
    public void store(IResource<T> resource) {
        Resource pgResource = checkInternalResource(resource);
        String sql = """
                INSERT INTO resources (id, collection_name, version, data)
                VALUES (?::uuid, ?, ?, ?::jsonb)
                ON CONFLICT (id, collection_name) DO UPDATE
                SET version = EXCLUDED.version, data = EXCLUDED.data
                """;
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pgResource.getId());
            ps.setString(2, collectionName);
            ps.setInt(3, pgResource.getVersion());
            ps.setString(4, pgResource.getJson());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store resource", e);
        }
    }

    @Override
    public void createNew(IResource<T> resource) {
        Resource pgResource = checkInternalResource(resource);
        String sql = "INSERT INTO resources (id, collection_name, version, data) " + "VALUES (?::uuid, ?, ?, ?::jsonb)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pgResource.getId());
            ps.setString(2, collectionName);
            ps.setInt(3, pgResource.getVersion());
            ps.setString(4, pgResource.getJson());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create new resource", e);
        }
    }

    @Override
    public IResource<T> read(String id, Integer version) {
        String sql = "SELECT id, version, data FROM resources " + "WHERE id = ?::uuid AND collection_name = ? AND version = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, collectionName);
            ps.setInt(3, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Resource(rs.getString("id"), rs.getInt("version"), rs.getString("data"));
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read resource", e);
        }
    }

    @Override
    public void remove(String id) {
        String sql = "DELETE FROM resources WHERE id = ?::uuid AND collection_name = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, collectionName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove resource", e);
        }
    }

    @Override
    public void removeAllPermanently(String id) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM resources WHERE id = ?::uuid AND collection_name = ?")) {
                    ps.setString(1, id);
                    ps.setString(2, collectionName);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM resources_history WHERE id = ?::uuid AND collection_name = ?")) {
                    ps.setString(1, id);
                    ps.setString(2, collectionName);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to permanently remove resource", e);
        }
    }

    @Override
    public IHistoryResource<T> readHistory(String id, Integer version) {
        String sql = "SELECT id, version, data, deleted FROM resources_history " + "WHERE id = ?::uuid AND collection_name = ? AND version = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, collectionName);
            ps.setInt(3, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new HistoryResource(rs.getString("id"), rs.getInt("version"), rs.getString("data"), rs.getBoolean("deleted"));
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read history", e);
        }
    }

    @Override
    public IHistoryResource<T> readHistoryLatest(String id) {
        String sql = "SELECT id, version, data, deleted FROM resources_history " + "WHERE id = ?::uuid AND collection_name = ? "
                + "ORDER BY version DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, collectionName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new HistoryResource(rs.getString("id"), rs.getInt("version"), rs.getString("data"), rs.getBoolean("deleted"));
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read latest history", e);
        }
    }

    @Override
    public IHistoryResource<T> newHistoryResourceFor(IResource<T> resource, boolean deleted) {
        Resource pgResource = checkInternalResource(resource);
        return new HistoryResource(pgResource.getId(), pgResource.getVersion(), pgResource.getJson(), deleted);
    }

    @Override
    public void store(IHistoryResource<T> history) {
        HistoryResource pgHistory = checkInternalHistoryResource(history);
        String sql = """
                INSERT INTO resources_history (id, collection_name, version, data, deleted)
                VALUES (?::uuid, ?, ?, ?::jsonb, ?)
                ON CONFLICT (id, collection_name, version) DO UPDATE
                SET data = EXCLUDED.data, deleted = EXCLUDED.deleted
                """;
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pgHistory.getId());
            ps.setString(2, collectionName);
            ps.setInt(3, pgHistory.getVersion());
            ps.setString(4, pgHistory.getJson());
            ps.setBoolean(5, pgHistory.isDeleted());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store history", e);
        }
    }

    @Override
    public Integer getCurrentVersion(String id) {
        String sql = "SELECT version FROM resources " + "WHERE id = ?::uuid AND collection_name = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, collectionName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("version");
                }
                return -1;
            }
        } catch (SQLException e) {
            // Invalid UUID format (e.g., MongoDB ObjectId) → treat as not found
            if (e.getMessage() != null && e.getMessage().contains("invalid input syntax for type uuid")) {
                return -1;
            }
            throw new RuntimeException("Failed to get current version", e);
        }
    }

    @Override
    public List<IResourceStore.IResourceId> findResourceIdsContaining(String jsonPath, String value) {
        // Use JSONB containment: data->'jsonPath' @> '["value"]'::jsonb
        String sql = "SELECT id, version FROM resources " + "WHERE collection_name = ? AND data->'" + sanitizeJsonPath(jsonPath) + "' @> ?::jsonb";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, collectionName);
            ps.setString(2, "[\"" + value + "\"]");
            return extractResourceIds(ps);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find resources containing value", e);
        }
    }

    @Override
    public List<IResourceStore.IResourceId> findHistoryResourceIdsContaining(String jsonPath, String value) {
        String sql = "SELECT id, version FROM resources_history " + "WHERE collection_name = ? AND data->'" + sanitizeJsonPath(jsonPath)
                + "' @> ?::jsonb";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, collectionName);
            ps.setString(2, "[\"" + value + "\"]");
            return extractResourceIds(ps);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find history resources containing value", e);
        }
    }

    @Override
    public List<IResourceStore.IResourceId> findResources(IResourceFilter.QueryFilters[] allQueryFilters, String sortField, int skip, int limit) {

        StringBuilder sql = new StringBuilder("SELECT id, version FROM resources WHERE collection_name = ?");
        List<Object> params = new ArrayList<>();
        params.add(collectionName);

        for (IResourceFilter.QueryFilters queryFilters : allQueryFilters) {
            List<String> clauses = new ArrayList<>();
            for (IResourceFilter.QueryFilter qf : queryFilters.getQueryFilters()) {
                if (qf.getFilter() instanceof String filterStr) {
                    // Regex filter → use SQL LIKE on JSONB field cast to text
                    clauses.add("data->>'" + sanitizeJsonPath(qf.getField()) + "' ~ ?");
                    params.add(filterStr);
                } else if (qf.getFilter() instanceof Boolean boolVal) {
                    clauses.add("COALESCE((data->>'" + sanitizeJsonPath(qf.getField()) + "')::boolean, false) = ?");
                    params.add(boolVal);
                } else {
                    clauses.add("data->>'" + sanitizeJsonPath(qf.getField()) + "' = ?");
                    params.add(qf.getFilter().toString());
                }
            }
            String connector = queryFilters.getConnectingType() == IResourceFilter.QueryFilters.ConnectingType.AND ? " AND " : " OR ";
            sql.append(" AND (").append(String.join(connector, clauses)).append(")");
        }

        if (sortField != null) {
            sql.append(" ORDER BY data->>'" + sanitizeJsonPath(sortField) + "' DESC");
        }

        int effectiveLimit = limit < 1 ? 20 : limit;
        sql.append(" LIMIT ").append(effectiveLimit);
        if (skip > 0) {
            sql.append(" OFFSET ").append(skip);
        }

        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof Boolean b) {
                    ps.setBoolean(i + 1, b);
                } else {
                    ps.setString(i + 1, param.toString());
                }
            }
            return extractResourceIds(ps);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find resources", e);
        }
    }

    private List<IResourceStore.IResourceId> extractResourceIds(PreparedStatement ps) throws SQLException {
        List<IResourceStore.IResourceId> results = new LinkedList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                int version = rs.getInt("version");
                results.add(new IResourceStore.IResourceId() {
                    @Override
                    public String getId() {
                        return id;
                    }
                    @Override
                    public Integer getVersion() {
                        return version;
                    }
                });
            }
        }
        return results;
    }

    private String sanitizeJsonPath(String path) {
        // Prevent SQL injection in JSON path
        return path.replaceAll("[^a-zA-Z0-9_.]", "");
    }

    @SuppressWarnings("unchecked")
    private Resource checkInternalResource(IResource<?> resource) {
        if (!(resource instanceof PostgresResourceStorage<?>.Resource)) {
            throw new IllegalArgumentException("Resource must be a PostgresResourceStorage.Resource instance");
        }
        return (Resource) resource;
    }

    @SuppressWarnings("unchecked")
    private HistoryResource checkInternalHistoryResource(IHistoryResource<?> resource) {
        if (!(resource instanceof PostgresResourceStorage<?>.HistoryResource)) {
            throw new IllegalArgumentException("HistoryResource must be a PostgresResourceStorage.HistoryResource instance");
        }
        return (HistoryResource) resource;
    }

    // -- Inner classes implementing IResource<T> and IHistoryResource<T> --

    private class Resource implements IResource<T> {
        private final String id;
        private final int version;
        private final String json;

        Resource(String id, int version, String json) {
            this.id = id;
            this.version = version;
            this.json = json;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Integer getVersion() {
            return version;
        }

        @Override
        public T getData() throws IOException {
            return jsonSerialization.deserialize(json, documentType);
        }

        String getJson() {
            return json;
        }
    }

    private class HistoryResource implements IHistoryResource<T> {
        private final String id;
        private final int version;
        private final String json;
        private final boolean deleted;

        HistoryResource(String id, int version, String json, boolean deleted) {
            this.id = id;
            this.version = version;
            this.json = json;
            this.deleted = deleted;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Integer getVersion() {
            return version;
        }

        @Override
        public T getData() throws IOException {
            return jsonSerialization.deserialize(json, documentType);
        }

        @Override
        public boolean isDeleted() {
            return deleted;
        }

        String getJson() {
            return json;
        }
    }
}
