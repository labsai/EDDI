/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.notifications;

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
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IWorkspaceNotificationStore}: one row per
 * notification in {@code workspace_notifications}. Schema is created on first
 * access, following {@code PostgresConnectionSettingsStore}.
 */
@ApplicationScoped
@DefaultBean
public class PostgresWorkspaceNotificationStore implements IWorkspaceNotificationStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresWorkspaceNotificationStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS workspace_notifications (
                id TEXT PRIMARY KEY,
                recipient TEXT NOT NULL,
                type TEXT NOT NULL,
                resource_id TEXT,
                resource_uri TEXT,
                resource_name TEXT,
                actor TEXT,
                actor_label TEXT,
                level TEXT,
                message TEXT,
                created_at TIMESTAMPTZ NOT NULL,
                read_at TIMESTAMPTZ
            )
            """;

    private static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_workspace_notifications_recipient ON workspace_notifications (recipient, created_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_workspace_notifications_actor ON workspace_notifications (actor, created_at DESC)"};

    private static final String COLUMNS = "id, recipient, type, resource_id, resource_uri, resource_name, actor, actor_label, level, message, "
            + "created_at, read_at";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresWorkspaceNotificationStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        synchronized (this) {
            if (schemaInitialized) {
                return;
            }
            try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE);
                for (String index : CREATE_INDEXES) {
                    statement.execute(index);
                }
                schemaInitialized = true;
                LOGGER.info("PostgresWorkspaceNotificationStore initialized (table=workspace_notifications)");
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to initialize the workspace_notifications table", e);
            }
        }
    }

    @Override
    public void add(WorkspaceNotification n) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection
                        .prepareStatement("INSERT INTO workspace_notifications (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, n.id());
            statement.setString(2, n.recipient());
            statement.setString(3, n.type().name());
            statement.setString(4, n.resourceId());
            statement.setString(5, n.resourceUri());
            statement.setString(6, n.resourceName());
            statement.setString(7, n.actor());
            statement.setString(8, n.actorLabel());
            statement.setString(9, n.level());
            statement.setString(10, n.message());
            statement.setTimestamp(11, Timestamp.from(n.createdAt()));
            if (n.readAt() == null) {
                statement.setNull(12, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setTimestamp(12, Timestamp.from(n.readAt()));
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store a workspace notification", e);
        }
    }

    @Override
    public List<WorkspaceNotification> list(String recipient, boolean unreadOnly, int limit) {
        return query("SELECT " + COLUMNS + " FROM workspace_notifications WHERE recipient = ?" + (unreadOnly ? " AND read_at IS NULL" : "")
                + " ORDER BY created_at DESC LIMIT ?", recipient, Math.max(1, limit));
    }

    @Override
    public long countUnread(String recipient) {
        return count("SELECT COUNT(*) FROM workspace_notifications WHERE recipient = ? AND read_at IS NULL", recipient);
    }

    @Override
    public long markRead(String recipient, Collection<String> ids, Instant readAt) {
        ensureSchema();
        String sql = "UPDATE workspace_notifications SET read_at = ? WHERE recipient = ? AND read_at IS NULL"
                + (ids == null ? "" : " AND id = ANY(?)");
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(readAt));
            statement.setString(2, recipient);
            if (ids != null) {
                statement.setArray(3, connection.createArrayOf("text", ids.toArray(new String[0])));
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark workspace notifications read", e);
        }
    }

    @Override
    public boolean hasUnread(String recipient, String actor, String resourceId, WorkspaceNotification.Type type) {
        return count("SELECT COUNT(*) FROM workspace_notifications WHERE recipient = ? AND actor = ? AND resource_id = ? AND type = ? "
                + "AND read_at IS NULL", recipient, actor, resourceId, type.name()) > 0;
    }

    @Override
    public long countByActorSince(String actor, WorkspaceNotification.Type type, Instant since) {
        return count("SELECT COUNT(*) FROM workspace_notifications WHERE actor = ? AND type = ? AND created_at >= ?", actor, type.name(),
                Timestamp.from(since));
    }

    @Override
    public void prune(String recipient, int keep, Instant olderThan) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement old = connection.prepareStatement("DELETE FROM workspace_notifications WHERE recipient = ? AND created_at < ?");
                PreparedStatement overflow = connection.prepareStatement("DELETE FROM workspace_notifications WHERE id IN (SELECT id FROM "
                        + "workspace_notifications WHERE recipient = ? ORDER BY created_at DESC OFFSET ?)")) {
            old.setString(1, recipient);
            old.setTimestamp(2, Timestamp.from(olderThan));
            old.executeUpdate();
            overflow.setString(1, recipient);
            overflow.setInt(2, Math.max(0, keep));
            overflow.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to prune workspace notifications", e);
        }
    }

    @Override
    public long deleteInvolving(String principal) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM workspace_notifications WHERE recipient = ? OR actor = ?")) {
            statement.setString(1, principal);
            statement.setString(2, principal);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete workspace notifications", e);
        }
    }

    @Override
    public List<WorkspaceNotification> listInvolving(String principal, int limit) {
        return query("SELECT " + COLUMNS + " FROM workspace_notifications WHERE recipient = ? OR actor = ? ORDER BY created_at DESC LIMIT ?",
                principal, principal, Math.max(1, limit));
    }

    private List<WorkspaceNotification> query(String sql, Object... params) {
        ensureSchema();
        List<WorkspaceNotification> result = new ArrayList<>();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(toNotification(rows));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read workspace notifications", e);
        }
        return result;
    }

    private long count(String sql, Object... params) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count workspace notifications", e);
        }
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object param = params[i];
            if (param instanceof Integer number) {
                statement.setInt(i + 1, number);
            } else if (param instanceof Timestamp timestamp) {
                statement.setTimestamp(i + 1, timestamp);
            } else {
                statement.setString(i + 1, param == null ? null : param.toString());
            }
        }
    }

    private static WorkspaceNotification toNotification(ResultSet row) throws SQLException {
        Timestamp created = row.getTimestamp("created_at");
        Timestamp read = row.getTimestamp("read_at");
        WorkspaceNotification.Type type;
        try {
            type = WorkspaceNotification.Type.valueOf(row.getString("type"));
        } catch (RuntimeException e) {
            type = WorkspaceNotification.Type.SHARED_WITH_YOU;
        }
        return new WorkspaceNotification(row.getString("id"), row.getString("recipient"), type, row.getString("resource_id"),
                row.getString("resource_uri"), row.getString("resource_name"), row.getString("actor"), row.getString("actor_label"),
                row.getString("level"), row.getString("message"), created == null ? null : created.toInstant(),
                read == null ? null : read.toInstant());
    }
}
