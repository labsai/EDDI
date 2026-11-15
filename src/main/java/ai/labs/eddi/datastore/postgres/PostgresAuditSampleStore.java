/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.hitl.oversight.OversightJson;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IAuditSampleStore}: table
 * {@code audit_samples}. Status, version, user and reviewer are columns (what
 * queries, compare-and-set and erasure touch); the rest is JSON text in
 * {@code data}.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresAuditSampleStore implements IAuditSampleStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresAuditSampleStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS audit_samples (
                id VARCHAR(64) PRIMARY KEY,
                agent_id VARCHAR(255),
                category VARCHAR(255),
                status VARCHAR(16) NOT NULL,
                sampled_at BIGINT NOT NULL,
                review_due_at BIGINT NOT NULL,
                user_id VARCHAR(255),
                reviewed_by VARCHAR(255),
                version BIGINT NOT NULL DEFAULT 0,
                data TEXT NOT NULL
            )
            """;

    static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_audit_samples_status_due ON audit_samples (status, review_due_at)",
            "CREATE INDEX IF NOT EXISTS idx_audit_samples_agent_category ON audit_samples (agent_id, category, sampled_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_audit_samples_agent_status ON audit_samples (agent_id, status, sampled_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_audit_samples_user ON audit_samples (user_id) WHERE user_id IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_audit_samples_reviewer ON audit_samples (reviewed_by) WHERE reviewed_by IS NOT NULL"
    };

    private static final String COLUMNS = "id, agent_id, category, status, sampled_at, review_due_at, user_id, reviewed_by, version, data";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresAuditSampleStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            for (String index : CREATE_INDEXES) {
                stmt.execute(index);
            }
            schemaInitialized = true;
            LOGGER.info("PostgresAuditSampleStore schema initialized");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize audit_samples table", e);
        }
    }

    @Override
    public void insert(AuditSample s) {
        ensureSchema();
        update("INSERT INTO audit_samples (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Arrays.asList(s.id(), s.agentId(), s.category(), s.status().name(), s.sampledAt().toEpochMilli(),
                        s.reviewDueAt().toEpochMilli(), s.userId(), s.reviewedBy(), s.version(), OversightJson.write(s.payload())));
    }

    @Override
    public Optional<AuditSample> read(String id) {
        ensureSchema();
        return select("SELECT " + COLUMNS + " FROM audit_samples WHERE id = ?", List.of(id)).stream().findFirst();
    }

    @Override
    public List<AuditSample> query(SampleFilter filter, int skip, int limit) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        String order = filter.status() == AuditSample.Status.PENDING
                ? " ORDER BY review_due_at ASC, id ASC"
                : " ORDER BY sampled_at DESC, id DESC";
        String sql = "SELECT " + COLUMNS + " FROM audit_samples" + where(filter, params) + order + " LIMIT ? OFFSET ?";
        params.add(Math.max(0, limit));
        params.add(Math.max(0, skip));
        return select(sql, params);
    }

    @Override
    public long count(SampleFilter filter) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM audit_samples" + where(filter, params);
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            PostgresApprovalDecisionStore.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count audit samples", e);
        }
    }

    @Override
    public Optional<AuditSample> review(AuditSample reviewed, long expectedVersion) {
        ensureSchema();
        long changed = update("UPDATE audit_samples SET status = ?, version = version + 1, reviewed_by = ?, data = ? "
                + "WHERE id = ? AND version = ? AND status = 'PENDING'",
                Arrays.asList(reviewed.status().name(), reviewed.reviewedBy(), OversightJson.write(reviewed.payload()), reviewed.id(),
                        expectedVersion));
        return changed > 0 ? read(reviewed.id()) : Optional.empty();
    }

    @Override
    public long expireOverdue(Instant now) {
        ensureSchema();
        return update("UPDATE audit_samples SET status = 'EXPIRED', version = version + 1 WHERE status = 'PENDING' AND review_due_at < ?",
                List.of(now.toEpochMilli()));
    }

    @Override
    public long deleteSampledBefore(Instant cutoff) {
        ensureSchema();
        return update("DELETE FROM audit_samples WHERE sampled_at < ?", List.of(cutoff.toEpochMilli()));
    }

    @Override
    public List<AuditSample> readByUser(String userId, int limit) {
        ensureSchema();
        return select("SELECT " + COLUMNS + " FROM audit_samples WHERE user_id = ? LIMIT ?", List.of(userId, Math.max(0, limit)));
    }

    @Override
    public long deleteByUser(String userId) {
        ensureSchema();
        return update("DELETE FROM audit_samples WHERE user_id = ?", List.of(userId));
    }

    @Override
    public long pseudonymizeReviewer(String userId, String pseudonym) {
        ensureSchema();
        return update("UPDATE audit_samples SET reviewed_by = ? WHERE reviewed_by = ?", List.of(pseudonym, userId));
    }

    public static String where(SampleFilter f, List<Object> params) {
        List<String> parts = new ArrayList<>();
        if (f.agentId() != null) {
            parts.add("agent_id = ?");
            params.add(f.agentId());
        }
        if (f.category() != null) {
            parts.add("category = ?");
            params.add(f.category());
        }
        if (f.status() != null) {
            parts.add("status = ?");
            params.add(f.status().name());
        }
        if (f.from() != null) {
            parts.add("sampled_at >= ?");
            params.add(f.from().toEpochMilli());
        }
        if (f.to() != null) {
            parts.add("sampled_at < ?");
            params.add(f.to().toEpochMilli());
        }
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }

    private List<AuditSample> select(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            PostgresApprovalDecisionStore.bind(ps, params);
            List<AuditSample> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AuditSample payload = OversightJson.read(rs.getString("data"), AuditSample.class);
                    rows.add(payload.withColumns(rs.getString("user_id"), rs.getString("reviewed_by"),
                            AuditSample.Status.valueOf(rs.getString("status")), rs.getLong("version")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read audit samples", e);
        }
    }

    private long update(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            PostgresApprovalDecisionStore.bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update audit samples", e);
        }
    }
}
