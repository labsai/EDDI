/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import jakarta.enterprise.inject.Instance;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * PostgreSQL implementation of {@link IUserMemoryStore}. All data lives in a
 * single {@code usermemories} table with JSONB value column. Flat property
 * methods operate on {@code global} entries.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresUserMemoryStore implements IUserMemoryStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresUserMemoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS usermemories (
                id VARCHAR(64) PRIMARY KEY DEFAULT gen_random_uuid()::text,
                user_id VARCHAR(255) NOT NULL,
                key VARCHAR(255) NOT NULL,
                value JSONB,
                category VARCHAR(50) NOT NULL DEFAULT 'fact',
                visibility VARCHAR(20) NOT NULL DEFAULT 'self',
                source_agent_id VARCHAR(255),
                group_ids JSONB DEFAULT '[]'::jsonb,
                source_conversation_id VARCHAR(255),
                conflicted BOOLEAN DEFAULT FALSE,
                access_count INTEGER DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private static final String CREATE_INDEXES = """
            CREATE INDEX IF NOT EXISTS idx_um_user_vis_agent_key
                ON usermemories (user_id, visibility, source_agent_id, key);
            CREATE INDEX IF NOT EXISTS idx_um_user_updated
                ON usermemories (user_id, updated_at DESC);
            CREATE INDEX IF NOT EXISTS idx_um_user_category
                ON usermemories (user_id, category);
            CREATE UNIQUE INDEX IF NOT EXISTS idx_um_upsert_global
                ON usermemories (user_id, key) WHERE visibility = 'global';
            CREATE UNIQUE INDEX IF NOT EXISTS idx_um_upsert_agent
                ON usermemories (user_id, key, source_agent_id) WHERE visibility != 'global';
            CREATE INDEX IF NOT EXISTS idx_um_user_access_count
                ON usermemories (user_id, access_count DESC);
            """;

    /** Recall order that ranks entries by how often they have been recalled. */
    private static final String RECALL_ORDER_MOST_ACCESSED = "most_accessed";

    /**
     * Share of the recall window reserved for the most recently updated entries
     * when {@link #RECALL_ORDER_MOST_ACCESSED} is used (1/5th of the window, at
     * least one slot).
     * <p>
     * Without this reservation {@code most_accessed} is self-reinforcing: only
     * entries that are already inside the window get their {@code access_count}
     * incremented, so a freshly written entry (count 0) can never climb in once the
     * window is full. The reserved slots act as the recency term of the ranking — a
     * new entry always gets at least one chance to be recalled (and thereby to
     * start accumulating access counts). Mirrors
     * {@code MongoUserMemoryStore.RECENCY_RESERVATION_DIVISOR}.
     */
    private static final int RECENCY_RESERVATION_DIVISOR = 5;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresUserMemoryStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            for (String sql : CREATE_INDEXES.split(";")) {
                sql = sql.trim();
                if (!sql.isEmpty()) {
                    stmt.execute(sql);
                }
            }
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize usermemories table", e);
        }
    }

    // === Flat property view (reads/writes global entries in usermemories) ===

    @Override
    public Properties readProperties(String userId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT key, value FROM usermemories WHERE user_id = ? AND visibility = 'global'";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            Properties props = new Properties();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("key");
                    String valueJson = rs.getString("value");
                    if (key != null && valueJson != null) {
                        Object value = MAPPER.readValue(valueJson, Object.class);
                        props.put(key, value);
                    }
                }
            }
            return props.isEmpty() ? null : props;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to read properties for userId=" + userId, e);
        }
    }

    @Override
    public void mergeProperties(String userId, Properties properties) throws IResourceStore.ResourceStoreException {
        if (properties == null || properties.isEmpty())
            return;
        ensureSchema();

        // Upsert each key-value pair as a global entry
        String sql = """
                INSERT INTO usermemories (user_id, key, value, category, visibility)
                VALUES (?, ?, ?::jsonb, 'property', 'global')
                ON CONFLICT (user_id, key) WHERE visibility = 'global'
                DO UPDATE SET value = EXCLUDED.value, updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String key = entry.getKey();
                if ("_id".equals(key) || "userId".equals(key))
                    continue;
                ps.setString(1, userId);
                ps.setString(2, key);
                ps.setString(3, MAPPER.writeValueAsString(entry.getValue()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to merge properties for userId=" + userId, e);
        }
    }

    @Override
    public void deleteProperties(String userId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "DELETE FROM usermemories WHERE user_id = ? AND visibility = 'global'";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete properties for userId=" + userId, e);
        }
    }

    // === Structured entries ===

    @Override
    public String upsert(UserMemoryEntry entry) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String visibility = entry.visibility() != null ? entry.visibility().name() : "self";

        // Check for cross-agent global overwrite
        if (entry.visibility() == Visibility.global) {
            String checkSql = "SELECT source_agent_id FROM usermemories WHERE user_id = ? AND key = ? AND visibility = 'global'";
            try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, entry.userId());
                ps.setString(2, entry.key());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String existingAgent = rs.getString("source_agent_id");
                        if (existingAgent != null && !existingAgent.equals(entry.sourceAgentId())) {
                            LOGGER.infof(
                                    "[MEMORY] Cross-agent global write: key='%s', user='%s', "
                                            + "owning agent='%s' (preserved), writing agent='%s'",
                                    entry.key(), entry.userId(), existingAgent, entry.sourceAgentId());
                        }
                    }
                }
            } catch (SQLException e) {
                throw new IResourceStore.ResourceStoreException("Failed to check global override", e);
            }
        }

        // Build upsert SQL based on visibility
        String upsertSql;
        if (entry.visibility() == Visibility.global) {
            // A global entry is keyed on (user_id, key) ONLY — it is shared across
            // agents, so any agent that merely RECALLS and rewrites it would otherwise
            // take over source_agent_id and silently steal ownership. Leaving
            // source_agent_id out of the DO UPDATE SET list is the SQL equivalent of
            // MongoDB's $setOnInsert: the INSERT still stamps the creating agent, later
            // updates keep it. Self/group entries are keyed per agent, so there
            // source_agent_id is part of the identity and updating it is correct.
            upsertSql = """
                    INSERT INTO usermemories (user_id, key, value, category, visibility, source_agent_id,
                        group_ids, source_conversation_id, conflicted)
                    VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?)
                    ON CONFLICT (user_id, key) WHERE visibility = 'global'
                    DO UPDATE SET value = EXCLUDED.value, category = EXCLUDED.category,
                        group_ids = EXCLUDED.group_ids,
                        source_conversation_id = EXCLUDED.source_conversation_id,
                        conflicted = EXCLUDED.conflicted, updated_at = CURRENT_TIMESTAMP
                    RETURNING id
                    """;
        } else {
            upsertSql = """
                    INSERT INTO usermemories (user_id, key, value, category, visibility, source_agent_id,
                        group_ids, source_conversation_id, conflicted)
                    VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?)
                    ON CONFLICT (user_id, key, source_agent_id) WHERE visibility != 'global'
                    DO UPDATE SET value = EXCLUDED.value, category = EXCLUDED.category,
                        visibility = EXCLUDED.visibility, group_ids = EXCLUDED.group_ids,
                        source_conversation_id = EXCLUDED.source_conversation_id,
                        conflicted = EXCLUDED.conflicted, updated_at = CURRENT_TIMESTAMP
                    RETURNING id
                    """;
        }

        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setString(1, entry.userId());
            ps.setString(2, entry.key());
            ps.setString(3, MAPPER.writeValueAsString(entry.value()));
            ps.setString(4, entry.category());
            ps.setString(5, visibility);
            ps.setString(6, entry.sourceAgentId());
            ps.setString(7, MAPPER.writeValueAsString(entry.groupIds()));
            ps.setString(8, entry.sourceConversationId());
            ps.setBoolean(9, entry.conflicted());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("id");
                }
            }
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to upsert memory entry", e);
        }
        return null;
    }

    @Override
    public void deleteEntry(String entryId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "DELETE FROM usermemories WHERE id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete memory entry", e);
        }
    }

    @Override
    public Optional<UserMemoryEntry> findEntryById(String entryId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT * FROM usermemories WHERE id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(resultSetToEntry(rs));
                }
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to find memory entry by id", e);
        }
        return Optional.empty();
    }

    // === Queries ===

    @Override
    public List<UserMemoryEntry> getVisibleEntries(String userId, String agentId, List<String> groupIds, String recallOrder, int maxEntries)
            throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String visibilityQuery = buildVisibilityQuery(groupIds);

        try (Connection conn = dataSourceInstance.get().getConnection()) {
            if (!RECALL_ORDER_MOST_ACCESSED.equals(recallOrder)) {
                List<UserMemoryEntry> entries = new ArrayList<>();
                collectInto(conn, new LinkedHashSet<>(), entries, visibilityQuery, userId, agentId, groupIds, " ORDER BY updated_at DESC",
                        maxEntries > 0 ? maxEntries : -1);
                return entries;
            }

            return mostAccessedWithRecencyReservation(conn, visibilityQuery, userId, agentId, groupIds, maxEntries);
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to get visible entries", e);
        }
    }

    /**
     * Visibility filter for a recall: self(agentId) OR group(groupIds) OR global.
     * The {@code ??|} is the JDBC escape for PostgreSQL's {@code ?|} JSONB overlap
     * operator — a single {@code ?} would be parsed as a bind parameter.
     */
    private String buildVisibilityQuery(List<String> groupIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM usermemories WHERE user_id = ? AND (
                    (visibility = 'self' AND source_agent_id = ?)
                    OR (visibility = 'global')
                """);

        if (groupIds != null && !groupIds.isEmpty()) {
            sql.append(" OR (visibility = 'group' AND group_ids ??| ARRAY[");
            for (int i = 0; i < groupIds.size(); i++) {
                sql.append(i > 0 ? ",?" : "?");
            }
            sql.append("])");
        }
        return sql.append(")").toString();
    }

    /**
     * {@code most_accessed} recall: the bulk of the window is filled by access
     * count, a reserved slice by recency (see
     * {@link #RECENCY_RESERVATION_DIVISOR}), and the {@code access_count}
     * increments are applied in ONE set-based statement AFTER both result sets are
     * drained. Mirrors
     * {@code MongoUserMemoryStore.mostAccessedWithRecencyReservation}.
     */
    private List<UserMemoryEntry> mostAccessedWithRecencyReservation(Connection conn, String visibilityQuery, String userId, String agentId,
                                                                     List<String> groupIds, int maxEntries)
            throws SQLException {
        int recencySlots = maxEntries > 0 ? Math.max(1, maxEntries / RECENCY_RESERVATION_DIVISOR) : 0;
        int accessSlots = maxEntries > 0 ? maxEntries - recencySlots : -1;

        Set<String> recalled = new LinkedHashSet<>();
        List<UserMemoryEntry> ordered = new ArrayList<>();
        collectInto(conn, recalled, ordered, visibilityQuery, userId, agentId, groupIds, " ORDER BY access_count DESC", accessSlots);
        collectInto(conn, recalled, ordered, visibilityQuery, userId, agentId, groupIds, " ORDER BY updated_at DESC", recencySlots);

        if (!recalled.isEmpty()) {
            String updateSql = "UPDATE usermemories SET access_count = access_count + 1 WHERE id = ANY (?)";
            try (PreparedStatement up = conn.prepareStatement(updateSql)) {
                up.setArray(1, conn.createArrayOf("varchar", recalled.toArray(new String[0])));
                up.executeUpdate();
            }
        }

        return ordered;
    }

    /**
     * Drains one sorted query into {@code ordered}, de-duplicating against
     * {@code seen} (keyed by row id). {@code limit < 0} means "no limit";
     * {@code limit == 0} skips the query entirely.
     */
    private void collectInto(Connection conn, Set<String> seen, List<UserMemoryEntry> ordered, String visibilityQuery, String userId,
                             String agentId, List<String> groupIds, String orderBy, int limit)
            throws SQLException {
        if (limit == 0) {
            return;
        }
        String sql = visibilityQuery + orderBy + (limit > 0 ? " LIMIT ?" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int paramIndex = 1;
            ps.setString(paramIndex++, userId);
            ps.setString(paramIndex++, agentId);
            if (groupIds != null) {
                for (String gid : groupIds) {
                    ps.setString(paramIndex++, gid);
                }
            }
            if (limit > 0) {
                ps.setInt(paramIndex, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    var entry = resultSetToEntry(rs);
                    if (entry.id() == null || seen.add(entry.id())) {
                        ordered.add(entry);
                    }
                }
            }
        }
    }

    @Override
    public List<UserMemoryEntry> filterEntries(String userId, String query) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        if (query == null || query.isBlank()) {
            return getAllEntries(userId);
        }
        String sql = "SELECT * FROM usermemories WHERE user_id = ? AND (key ILIKE ? OR value::text ILIKE ?) ORDER BY updated_at DESC";
        String pattern = "%" + query + "%";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            List<UserMemoryEntry> entries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(resultSetToEntry(rs));
                }
            }
            return entries;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to filter entries", e);
        }
    }

    @Override
    public List<UserMemoryEntry> getEntriesByCategory(String userId, String category) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT * FROM usermemories WHERE user_id = ? AND category = ? ORDER BY updated_at DESC";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, category);
            List<UserMemoryEntry> entries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(resultSetToEntry(rs));
                }
            }
            return entries;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to get entries by category", e);
        }
    }

    @Override
    public Optional<UserMemoryEntry> getByKey(String userId, String key) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT * FROM usermemories WHERE user_id = ? AND key = ? LIMIT 1";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(resultSetToEntry(rs));
                }
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to get entry by key", e);
        }
        return Optional.empty();
    }

    @Override
    public List<UserMemoryEntry> getAllEntries(String userId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT * FROM usermemories WHERE user_id = ? ORDER BY updated_at DESC";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            List<UserMemoryEntry> entries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(resultSetToEntry(rs));
                }
            }
            return entries;
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to get all entries", e);
        }
    }

    // === GDPR ===

    @Override
    public void deleteAllForUser(String userId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM usermemories WHERE user_id = ?")) {
                ps.setString(1, userId);
                int count = ps.executeUpdate();
                LOGGER.infof("[MEMORY] GDPR delete-all for user '%s': %d entries removed", userId, count);
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete all data for userId=" + userId, e);
        }
    }

    @Override
    public long countEntries(String userId) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        String sql = "SELECT COUNT(*) FROM usermemories WHERE user_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to count entries", e);
        }
        return 0;
    }

    @Override
    public long deleteOlderThan(int olderThanDays) throws IResourceStore.ResourceStoreException {
        ensureSchema();
        // Exclude GDPR system keys (e.g. _gdpr_processing_restricted) from retention
        // cleanup
        String sql = "DELETE FROM usermemories WHERE updated_at < CURRENT_TIMESTAMP - INTERVAL '1 day' * ? AND key NOT LIKE '_gdpr_%'";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, olderThanDays);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete old memory entries", e);
        }
    }

    // === ResultSet conversion ===

    @SuppressWarnings("unchecked")
    private UserMemoryEntry resultSetToEntry(ResultSet rs) throws SQLException {
        String visStr = rs.getString("visibility");
        Visibility vis;
        try {
            vis = visStr != null ? Visibility.valueOf(visStr) : Visibility.self;
        } catch (IllegalArgumentException e) {
            vis = Visibility.self;
        }

        Object value = null;
        String valueJson = rs.getString("value");
        if (valueJson != null) {
            try {
                value = MAPPER.readValue(valueJson, Object.class);
            } catch (Exception ignored) {
                value = valueJson;
            }
        }

        List<String> groupIds = List.of();
        String groupIdsJson = rs.getString("group_ids");
        if (groupIdsJson != null) {
            try {
                groupIds = MAPPER.readValue(groupIdsJson, List.class);
            } catch (Exception ignored) {
                // keep empty
            }
        }

        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");

        return new UserMemoryEntry(rs.getString("id"), rs.getString("user_id"), rs.getString("key"), value, rs.getString("category"), vis,
                rs.getString("source_agent_id"), groupIds, rs.getString("source_conversation_id"), rs.getBoolean("conflicted"),
                rs.getInt("access_count"), createdTs != null ? createdTs.toInstant() : null, updatedTs != null ? updatedTs.toInstant() : null);
    }
}
