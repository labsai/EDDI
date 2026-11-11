/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.postgres;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthReportLinkStore;
import ai.labs.eddi.engine.health.model.HealthReportLink;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IHealthReportLinkStore}: table
 * {@code health_report_links}, a unique index on the token hash. Created on
 * first use.
 */
@ApplicationScoped
@DefaultBean
public class PostgresHealthReportLinkStore implements IHealthReportLinkStore {

    static final String TABLE = "health_report_links";

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS health_report_links (
                id VARCHAR(64) PRIMARY KEY,
                agent_id VARCHAR(255) NOT NULL,
                token_hash VARCHAR(64) NOT NULL,
                sections TEXT[],
                period VARCHAR(8),
                created_at_ms BIGINT,
                created_by VARCHAR(512),
                expires_at_ms BIGINT NOT NULL
            )
            """;
    static final String[] CREATE_INDEXES = {
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_health_report_links_token ON health_report_links (token_hash)",
            "CREATE INDEX IF NOT EXISTS idx_health_report_links_agent ON health_report_links (agent_id, created_at_ms)",
            "CREATE INDEX IF NOT EXISTS idx_health_report_links_expires ON health_report_links (expires_at_ms)"};

    static final String COLUMNS = "id, agent_id, token_hash, sections, period, created_at_ms, created_by, expires_at_ms";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresHealthReportLinkStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            for (String index : CREATE_INDEXES) {
                statement.execute(index);
            }
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not create the " + TABLE + " schema", e);
        }
    }

    private Connection connection() throws SQLException {
        ensureSchema();
        return dataSourceInstance.get().getConnection();
    }

    @Override
    public void create(HealthReportLink link) {
        try (Connection c = connection();
                PreparedStatement s = c.prepareStatement("INSERT INTO health_report_links (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, link.id());
            s.setString(2, link.agentId());
            s.setString(3, link.tokenHash());
            s.setArray(4, c.createArrayOf("text", link.sections().toArray(new String[0])));
            s.setString(5, link.period());
            s.setLong(6, link.createdAt() == null ? 0L : link.createdAt().toEpochMilli());
            s.setString(7, link.createdBy());
            s.setLong(8, link.expiresAt().toEpochMilli());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not store a health report link", e);
        }
    }

    @Override
    public Optional<HealthReportLink> findByTokenHash(String tokenHash) {
        try (Connection c = connection();
                PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM health_report_links WHERE token_hash = ?")) {
            s.setString(1, tokenHash);
            return read(s).stream().findFirst();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read a health report link", e);
        }
    }

    @Override
    public List<HealthReportLink> listByAgent(String agentId) {
        try (Connection c = connection();
                PreparedStatement s = c.prepareStatement(
                        "SELECT " + COLUMNS + " FROM health_report_links WHERE agent_id = ? ORDER BY created_at_ms DESC, id")) {
            s.setString(1, agentId);
            return read(s);
        } catch (SQLException e) {
            throw new HealthStoreException("Could not list health report links", e);
        }
    }

    private static List<HealthReportLink> read(PreparedStatement s) throws SQLException {
        List<HealthReportLink> found = new ArrayList<>();
        try (ResultSet r = s.executeQuery()) {
            while (r.next()) {
                Array sections = r.getArray(4);
                found.add(new HealthReportLink(r.getString(1), r.getString(2), r.getString(3),
                        sections == null ? List.of() : Arrays.asList((String[]) sections.getArray()), r.getString(5),
                        Instant.ofEpochMilli(r.getLong(6)), r.getString(7), Instant.ofEpochMilli(r.getLong(8))));
            }
        }
        return found;
    }

    @Override
    public boolean delete(String agentId, String id) {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("DELETE FROM health_report_links WHERE id = ? AND agent_id = ?")) {
            s.setString(1, id);
            s.setString(2, agentId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not revoke a health report link", e);
        }
    }

    @Override
    public long deleteExpiredBefore(Instant before) {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("DELETE FROM health_report_links WHERE expires_at_ms < ?")) {
            s.setLong(1, before.toEpochMilli());
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not delete expired health report links", e);
        }
    }

    @Override
    public long clearCreator(String userId) {
        if (userId == null) {
            return 0;
        }
        try (Connection c = connection();
                PreparedStatement s = c.prepareStatement("UPDATE health_report_links SET created_by = NULL WHERE created_by = ?")) {
            s.setString(1, userId);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not clear the creator of health report links", e);
        }
    }
}
