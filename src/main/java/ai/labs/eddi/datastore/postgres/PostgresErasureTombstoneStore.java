/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IErasureTombstoneStore}: tombstones in
 * {@code erasure_tombstones}, pending removals in
 * {@code erasure_pending_removals}, the salt in {@code erasure_meta}. Expired
 * tombstones are ignored by {@link #erasedAt} and deleted by the maintenance
 * pass ({@link #deleteExpiredTombstones}).
 */
@ApplicationScoped
@DefaultBean
public class PostgresErasureTombstoneStore implements IErasureTombstoneStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresErasureTombstoneStore.class);

    private static final String[] SCHEMA = {"""
            CREATE TABLE IF NOT EXISTS erasure_tombstones (
                kind VARCHAR(16) NOT NULL,
                tkey VARCHAR(255) NOT NULL,
                erased_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (kind, tkey)
            )
            """, """
            CREATE TABLE IF NOT EXISTS erasure_pending_removals (
                writer VARCHAR(64) NOT NULL,
                row_id VARCHAR(255) NOT NULL,
                user_key VARCHAR(64),
                conversation_id VARCHAR(255),
                created_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (writer, row_id)
            )
            """, """
            CREATE TABLE IF NOT EXISTS erasure_meta (
                name VARCHAR(64) PRIMARY KEY,
                value TEXT NOT NULL
            )
            """, "CREATE INDEX IF NOT EXISTS idx_erasure_tombstones_expiry ON erasure_tombstones (expires_at)",
            "CREATE INDEX IF NOT EXISTS idx_erasure_pending_user ON erasure_pending_removals (user_key) WHERE user_key IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_erasure_pending_conversation ON erasure_pending_removals (conversation_id) "
                    + "WHERE conversation_id IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_erasure_pending_created ON erasure_pending_removals (created_at)"};

    private static final String SALT_NAME = "tombstone-salt";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresErasureTombstoneStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            for (String ddl : SCHEMA) {
                stmt.execute(ddl);
            }
            schemaInitialized = true;
            LOGGER.info("PostgresErasureTombstoneStore schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize the erasure tombstone tables", e);
        }
    }

    @Override
    public void putTombstones(Kind kind, Collection<String> keys, Instant erasedAt, Instant expiresAt) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        ensureSchema();
        String sql = "INSERT INTO erasure_tombstones (kind, tkey, erased_at, expires_at) VALUES (?, ?, ?, ?) ON CONFLICT (kind, tkey) DO UPDATE "
                + "SET erased_at = GREATEST(erasure_tombstones.erased_at, EXCLUDED.erased_at), "
                + "expires_at = GREATEST(erasure_tombstones.expires_at, EXCLUDED.expires_at)";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String key : keys) {
                ps.setString(1, kind.name());
                ps.setString(2, key);
                ps.setObject(3, timestamp(erasedAt));
                ps.setObject(4, timestamp(expiresAt));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write erasure tombstones", e);
        }
    }

    @Override
    public Optional<Instant> erasedAt(Kind kind, String key, Instant now) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn
                        .prepareStatement("SELECT erased_at FROM erasure_tombstones WHERE kind = ? AND tkey = ? AND expires_at > ?")) {
            ps.setString(1, kind.name());
            ps.setString(2, key);
            ps.setObject(3, timestamp(now));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, OffsetDateTime.class).toInstant()) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read an erasure tombstone", e);
        }
    }

    @Override
    public long deleteExpiredTombstones(Instant now) {
        ensureSchema();
        return update("DELETE FROM erasure_tombstones WHERE expires_at <= ?", List.of(timestamp(now)));
    }

    @Override
    public String salt(String candidate) {
        ensureSchema();
        // Insert-if-absent, then read whichever value won.
        update("INSERT INTO erasure_meta (name, value) VALUES (?, ?) ON CONFLICT (name) DO NOTHING", List.of(SALT_NAME, candidate));
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT value FROM erasure_meta WHERE name = ?")) {
            ps.setString(1, SALT_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("The erasure tombstone salt was not stored");
                }
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the erasure tombstone salt", e);
        }
    }

    @Override
    public void addPendingRemoval(PendingRemoval removal) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        params.add(removal.writer());
        params.add(removal.rowId());
        params.add(removal.userKey());
        params.add(removal.conversationId());
        params.add(timestamp(removal.createdAt()));
        update("INSERT INTO erasure_pending_removals (writer, row_id, user_key, conversation_id, created_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (writer, row_id) DO NOTHING", params);
    }

    @Override
    public List<PendingRemoval> pendingRemovals(String writer, String userKey, String conversationId, int limit) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        String sql = "SELECT writer, row_id, user_key, conversation_id, created_at FROM erasure_pending_removals"
                + where(writer, userKey, conversationId, params) + " ORDER BY created_at, writer, row_id LIMIT ?";
        params.add(limit);
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            List<PendingRemoval> found = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(new PendingRemoval(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getObject(5, OffsetDateTime.class).toInstant()));
                }
            }
            return found;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read pending erasure removals", e);
        }
    }

    @Override
    public long countPendingRemovals(String writer, String userKey, String conversationId) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM erasure_pending_removals" + where(writer, userKey, conversationId, params);
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count pending erasure removals", e);
        }
    }

    @Override
    public boolean removePendingRemoval(String writer, String rowId) {
        ensureSchema();
        return update("DELETE FROM erasure_pending_removals WHERE writer = ? AND row_id = ?", List.of(writer, rowId)) > 0;
    }

    static String where(String writer, String userKey, String conversationId, List<Object> params) {
        List<String> scope = new ArrayList<>();
        if (userKey != null) {
            scope.add("user_key = ?");
        }
        if (conversationId != null) {
            scope.add("conversation_id = ?");
        }
        List<String> clauses = new ArrayList<>();
        if (writer != null) {
            clauses.add("writer = ?");
            params.add(writer);
        }
        if (!scope.isEmpty()) {
            clauses.add("(" + String.join(" OR ", scope) + ")");
            if (userKey != null) {
                params.add(userKey);
            }
            if (conversationId != null) {
                params.add(conversationId);
            }
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private long update(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erasure tombstone statement failed", e);
        }
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value == null) {
                ps.setNull(i + 1, Types.NULL);
            } else {
                ps.setObject(i + 1, value);
            }
        }
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
