/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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

    private static final Logger LOGGER = Logger.getLogger(PostgresResourceStorage.class);

    // collection_name FIRST: every query in this class filters on it, and a
    // btree index can only be used from its leading column. With (id,
    // collection_name) — the original order — a descriptor listing could not use
    // the primary key at all and degraded to a sequential scan over the single
    // table that holds every agent, workflow, behavior, output and LLM config.
    private static final String CREATE_RESOURCES_TABLE = """
            CREATE TABLE IF NOT EXISTS resources (
                id UUID NOT NULL,
                collection_name TEXT NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                data JSONB NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                PRIMARY KEY (collection_name, id)
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

    /**
     * Indexes created unconditionally on every startup.
     * <p>
     * The {@code idx_resources_collection_name} entry is deliberately redundant
     * with the primary key on a freshly created table: the PK column order can only
     * be changed by dropping and recreating the constraint, which is not something
     * to do implicitly at startup against a live deployment, so tables created
     * before the PK reorder keep {@code (id, collection_name)} and need a
     * standalone index to make {@code WHERE collection_name = ?} indexable. The
     * duplicate costs one extra btree on new installs; a sequential scan of every
     * config in the system costs far more.
     */
    private static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_resources_history_id_collection ON resources_history (id, collection_name)",
            "CREATE INDEX IF NOT EXISTS idx_resources_collection_name ON resources (collection_name)",
            "CREATE INDEX IF NOT EXISTS idx_resources_history_collection_name ON resources_history (collection_name)",
            // Supports the jsonpath existence operator (@?) used by
            // findResourceIdsContaining for reverse "which config references X"
            // lookups. jsonb_path_ops is the smaller, faster operator class and
            // covers @>, @? and @@.
            "CREATE INDEX IF NOT EXISTS idx_resources_data_gin ON resources USING gin (data jsonb_path_ops)",
            "CREATE INDEX IF NOT EXISTS idx_resources_history_data_gin ON resources_history USING gin (data jsonb_path_ops)"};

    private static final String UPDATE_IF_CURRENT_VERSION_SQL = """
            UPDATE resources SET version = ?, data = ?::jsonb
            WHERE id = ?::uuid AND collection_name = ? AND version = ?
            """;

    private static final String INSERT_HISTORY_SQL = """
            INSERT INTO resources_history (id, collection_name, version, data, deleted)
            VALUES (?::uuid, ?, ?, ?::jsonb, ?)
            ON CONFLICT (id, collection_name, version) DO NOTHING
            """;

    private final DataSource dataSource;
    private final String collectionName;
    private final IJsonSerialization jsonSerialization;
    private final Class<T> documentType;

    public PostgresResourceStorage(DataSource dataSource, String collectionName, IJsonSerialization jsonSerialization, Class<T> documentType) {
        this(dataSource, collectionName, jsonSerialization, documentType, new String[0]);
    }

    public PostgresResourceStorage(DataSource dataSource, String collectionName, IJsonSerialization jsonSerialization, Class<T> documentType,
            String... indexes) {
        checkNotNull(dataSource, "dataSource");
        checkNotNull(collectionName, "collectionName");
        this.dataSource = dataSource;
        this.collectionName = collectionName;
        this.jsonSerialization = jsonSerialization;
        this.documentType = documentType;

        initSchema(indexes);
    }

    private void initSchema(String... indexes) {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_RESOURCES_TABLE);
            stmt.execute(CREATE_HISTORY_TABLE);
            for (String createIndex : CREATE_INDEXES) {
                createIndexQuietly(stmt, createIndex);
            }
            for (String index : indexes) {
                createFieldIndex(stmt, index);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize PostgreSQL schema", e);
        }
    }

    /**
     * Create the expression index backing a caller-declared field hint.
     * <p>
     * Leading with {@code collection_name} matches how the field is always queried
     * (scoped to one collection in the shared table). Dotted paths are skipped:
     * they address values inside arrays, which a btree expression index cannot
     * represent — those queries are served by the GIN index instead.
     */
    private void createFieldIndex(Statement stmt, String field) {
        String sanitized = sanitizeJsonPath(field);
        if (sanitized.isEmpty() || sanitized.contains(".")) {
            return;
        }
        String indexName = "idx_resources_field_" + sanitized.toLowerCase();
        createIndexQuietly(stmt,
                "CREATE INDEX IF NOT EXISTS " + indexName + " ON resources (collection_name, (data ->> '" + sanitized + "'))");
    }

    private void createIndexQuietly(Statement stmt, String createIndexSql) {
        try {
            stmt.execute(createIndexSql);
        } catch (SQLException e) {
            // An index is an optimisation, never a correctness requirement — a
            // deployment whose DB role may not CREATE INDEX must still boot.
            LOGGER.warnf("Could not create index (queries will fall back to a scan): %s — %s", createIndexSql, e.getMessage());
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
    public void storeIfCurrentVersion(IResource<T> newResource, int expectedCurrentVersion)
            throws IResourceStore.ResourceModifiedException {
        Resource pgResource = checkInternalResource(newResource);
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(UPDATE_IF_CURRENT_VERSION_SQL)) {
            ps.setInt(1, pgResource.getVersion());
            ps.setString(2, pgResource.getJson());
            ps.setString(3, pgResource.getId());
            ps.setString(4, collectionName);
            ps.setInt(5, expectedCurrentVersion);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IResourceStore.ResourceModifiedException(
                        String.format("Resource was modified concurrently (id=%s, expected version=%d)",
                                pgResource.getId(), expectedCurrentVersion));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store resource with version check", e);
        }
    }

    @Override
    public void storeIfFieldEquals(IResource<T> newResource, String fieldName, String expectedValue)
            throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        Resource pgResource = checkInternalResource(newResource);
        // The field path is rendered as a traversal expression rather than bound as
        // a literal key, so a dotted fieldName means the same thing here as it does
        // on MongoDB (which resolves dotted paths natively in its filter).
        String sql = "UPDATE resources SET version = ?, data = ?::jsonb "
                + "WHERE id = ?::uuid AND collection_name = ? AND " + toTextPathExpression(fieldName) + " = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pgResource.getVersion());
            ps.setString(2, pgResource.getJson());
            ps.setString(3, pgResource.getId());
            ps.setString(4, collectionName);
            ps.setString(5, expectedValue);
            if (ps.executeUpdate() == 0) {
                // Distinguish "deleted" (404) from "field mismatch" (409) — parity
                // with the Mongo backend.
                String existsSql = "SELECT 1 FROM resources WHERE id = ?::uuid AND collection_name = ?";
                try (PreparedStatement check = conn.prepareStatement(existsSql)) {
                    check.setString(1, pgResource.getId());
                    check.setString(2, collectionName);
                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next()) {
                            throw new IResourceStore.ResourceNotFoundException(
                                    String.format("Resource no longer exists (id=%s)", pgResource.getId()));
                        }
                    }
                }
                throw new IResourceStore.ResourceModifiedException(
                        String.format("Resource field '%s' was not '%s' (id=%s)", fieldName, expectedValue, pgResource.getId()));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store resource with field check", e);
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
    public List<IResource<T>> readMany(List<IResourceStore.IResourceId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("SELECT id, version, data FROM resources WHERE collection_name = ? AND id IN (");
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i == 0 ? "?::uuid" : ", ?::uuid");
        }
        sql.append(')');

        Map<String, Resource> byId = new HashMap<>();
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, collectionName);
            for (int i = 0; i < ids.size(); i++) {
                ps.setString(i + 2, ids.get(i).getId());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byId.put(rs.getString("id"), new Resource(rs.getString("id"), rs.getInt("version"), rs.getString("data")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read resources", e);
        }

        // Request order is the caller's sort order and must survive the batching.
        List<IResource<T>> resources = new ArrayList<>(ids.size());
        for (IResourceStore.IResourceId id : ids) {
            Resource resource = byId.get(id.getId());
            if (resource != null && id.getVersion().equals(resource.getVersion())) {
                resources.add(resource);
            }
        }
        return resources;
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

    /**
     * Archive the predecessor and apply the version-checked update inside ONE
     * transaction, so a crash can never leave the two rows disagreeing about which
     * version is current.
     */
    @Override
    public void storeHistoryAndUpdate(IHistoryResource<T> history, IResource<T> newResource, int expectedCurrentVersion)
            throws IResourceStore.ResourceModifiedException {
        HistoryResource pgHistory = checkInternalHistoryResource(history);
        Resource pgResource = checkInternalResource(newResource);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                insertHistory(conn, pgHistory);
                if (updateIfCurrentVersion(conn, pgResource, expectedCurrentVersion) == 0) {
                    conn.rollback();
                    throw new IResourceStore.ResourceModifiedException(
                            String.format("Resource was modified concurrently (id=%s, expected version=%d)",
                                    pgResource.getId(), expectedCurrentVersion));
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to archive and update resource", e);
        }
    }

    /**
     * Archive the deleted-flagged version and drop the current row inside ONE
     * transaction — the non-transactional sequence could leave a resource that is
     * archived as deleted while still live.
     */
    @Override
    public void storeHistoryAndRemove(IHistoryResource<T> history, String id) {
        HistoryResource pgHistory = checkInternalHistoryResource(history);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                insertHistory(conn, pgHistory);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM resources WHERE id = ?::uuid AND collection_name = ?")) {
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
            throw new RuntimeException("Failed to archive and remove resource", e);
        }
    }

    private void insertHistory(Connection conn, HistoryResource pgHistory) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_HISTORY_SQL)) {
            ps.setString(1, pgHistory.getId());
            ps.setString(2, collectionName);
            ps.setInt(3, pgHistory.getVersion());
            ps.setString(4, pgHistory.getJson());
            ps.setBoolean(5, pgHistory.isDeleted());
            ps.executeUpdate();
        }
    }

    private int updateIfCurrentVersion(Connection conn, Resource pgResource, int expectedCurrentVersion) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_IF_CURRENT_VERSION_SQL)) {
            ps.setInt(1, pgResource.getVersion());
            ps.setString(2, pgResource.getJson());
            ps.setString(3, pgResource.getId());
            ps.setString(4, collectionName);
            ps.setInt(5, expectedCurrentVersion);
            return ps.executeUpdate();
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
        try (Connection conn = dataSource.getConnection()) {
            insertHistory(conn, pgHistory);
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
        String sql = "SELECT id, version FROM resources WHERE collection_name = ? AND data @? ?::jsonpath";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, collectionName);
            ps.setString(2, toContainmentJsonPath(jsonPath, value));
            return extractResourceIds(ps);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find resources containing value", e);
        }
    }

    @Override
    public List<IResourceStore.IResourceId> findHistoryResourceIdsContaining(String jsonPath, String value) {
        String sql = "SELECT id, version FROM resources_history WHERE collection_name = ? AND data @? ?::jsonpath";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, collectionName);
            ps.setString(2, toContainmentJsonPath(jsonPath, value));
            return extractResourceIds(ps);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find history resources containing value", e);
        }
    }

    /**
     * Translate a dot-separated field path plus an expected value into a SQL/JSON
     * path expression.
     * <p>
     * The {@code ->} operator this used to use looks up a LITERAL top-level key, so
     * {@code data->'workflowSteps.config.uri'} matched a key spelled with dots in
     * it — which never exists. The query therefore returned zero rows forever,
     * silently, while MongoDB traversed the same path correctly. Every reverse
     * lookup built on it (cascade-delete reference guards, "which agents use this
     * workflow") was dead on PostgreSQL.
     * <p>
     * Each segment gets a {@code [*]} accessor: in lax mode (the default) that
     * unwraps arrays and wraps scalars, so one expression handles both
     * {@code workflows} (a top-level array of URIs) and
     * {@code workflowSteps[*].config.uri} (a value nested inside an array of
     * objects).
     * <p>
     * The result is passed as a bind parameter ({@code ?::jsonpath}), never
     * concatenated into the SQL, so neither the path nor the value can alter the
     * statement. {@code value} is additionally emitted as a properly escaped JSON
     * string literal so it cannot break out of the jsonpath either — a literal is
     * used rather than a {@code vars} argument because {@code jsonb_path_exists}
     * with vars is not indexable, whereas the {@code @?} operator can use the GIN
     * index.
     */
    static String toContainmentJsonPath(String jsonPath, String value) {
        StringBuilder path = new StringBuilder("$");
        for (String segment : sanitizeJsonPath(jsonPath).split("\\.")) {
            if (!segment.isEmpty()) {
                path.append(".\"").append(segment).append("\"[*]");
            }
        }
        return path.append(" ? (@ == ").append(toJsonStringLiteral(value)).append(')').toString();
    }

    private static String toJsonStringLiteral(String value) {
        StringBuilder literal = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> literal.append("\\\"");
                case '\\' -> literal.append("\\\\");
                case '\b' -> literal.append("\\b");
                case '\f' -> literal.append("\\f");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                default -> {
                    if (c < 0x20) {
                        literal.append(String.format("\\u%04x", (int) c));
                    } else {
                        literal.append(c);
                    }
                }
            }
        }
        return literal.append('"').toString();
    }

    /**
     * Render a dot-separated field path as a JSONB text extraction expression:
     * {@code a} becomes {@code data ->> 'a'}, {@code a.b.c} becomes
     * {@code data -> 'a' -> 'b' ->> 'c'}. The single-segment form is byte-identical
     * to what this class emitted before, so the expression indexes created for
     * those fields still match.
     */
    private static String toTextPathExpression(String field) {
        String[] segments = sanitizeJsonPath(field).split("\\.");
        StringBuilder expression = new StringBuilder("data");
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty()) {
                continue;
            }
            expression.append(i == segments.length - 1 ? " ->> '" : " -> '").append(segments[i]).append('\'');
        }
        return expression.toString();
    }

    @Override
    public List<IResourceStore.IResourceId> findResources(IResourceFilter.QueryFilters[] allQueryFilters, String sortField, int skip, int limit) {

        StringBuilder sql = new StringBuilder("SELECT id, version FROM resources WHERE collection_name = ?");
        List<Object> params = new ArrayList<>();
        params.add(collectionName);

        for (IResourceFilter.QueryFilters queryFilters : allQueryFilters) {
            List<String> clauses = new ArrayList<>();
            for (IResourceFilter.QueryFilter qf : queryFilters.getQueryFilters()) {
                String fieldExpression = toTextPathExpression(qf.getField());
                if (qf.getFilter() instanceof String filterStr) {
                    // Regex filter → use SQL LIKE on JSONB field cast to text
                    clauses.add(fieldExpression + " ~ ?");
                    params.add(filterStr);
                } else if (qf.getFilter() instanceof Boolean boolVal) {
                    clauses.add("COALESCE((" + fieldExpression + ")::boolean, false) = ?");
                    params.add(boolVal);
                } else {
                    clauses.add(fieldExpression + " = ?");
                    params.add(qf.getFilter().toString());
                }
            }
            String connector = queryFilters.getConnectingType() == IResourceFilter.QueryFilters.ConnectingType.AND ? " AND " : " OR ";
            sql.append(" AND (").append(String.join(connector, clauses)).append(")");
        }

        if (sortField != null) {
            sql.append(" ORDER BY ").append(toTextPathExpression(sortField)).append(" DESC");
        }

        int effectiveLimit = IResourceStorage.resolveLimit(limit);
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

    private static String sanitizeJsonPath(String path) {
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
