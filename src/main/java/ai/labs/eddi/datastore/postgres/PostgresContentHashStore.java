/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.ingestion.IContentHashStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * PostgreSQL-backed {@link IContentHashStore} that tracks content hashes in the
 * {@code rag_ingestion_hashes} table.
 * <p>
 * Uses {@code SELECT ... FOR UPDATE} with a manual transaction for atomic
 * deduplication in {@link #shouldIngest}.
 *
 * @since 6.0.3
 */
@ApplicationScoped
@Default
public class PostgresContentHashStore implements IContentHashStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresContentHashStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS rag_ingestion_hashes (
                source_id    TEXT NOT NULL,
                document_id  TEXT NOT NULL,
                hash         TEXT NOT NULL,
                stale        BOOLEAN NOT NULL DEFAULT FALSE,
                ingested_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                stale_at     TIMESTAMPTZ,
                CONSTRAINT uq_source_document UNIQUE (source_id, document_id)
            )
            """;

    private static final String CREATE_INDEXES = """
            CREATE INDEX IF NOT EXISTS idx_hash_source_id ON rag_ingestion_hashes (source_id);
            CREATE INDEX IF NOT EXISTS idx_hash_stale ON rag_ingestion_hashes (stale)
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresContentHashStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    public synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            for (String idx : CREATE_INDEXES.split(";")) {
                String trimmed = idx.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
            schemaInitialized = true;
            LOGGER.info("PostgresContentHashStore schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize rag_ingestion_hashes table", e);
        }
    }

    @Override
    public boolean shouldIngest(String sourceId, String documentId, String content) {
        ensureSchema();

        if (documentId == null || documentId.isEmpty()) {
            LOGGER.warn("Skipping document with null/empty ID");
            return false;
        }
        if (content == null || content.isEmpty()) {
            LOGGER.warnf("Skipping document with null/empty content: %s", documentId);
            return false;
        }

        String hash = computeHash(content);
        Instant now = Instant.now();

        try (Connection conn = dataSourceInstance.get().getConnection()) {
            conn.setAutoCommit(false);
            try {
                boolean changed;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT hash FROM rag_ingestion_hashes " +
                                "WHERE source_id = ? AND document_id = ? FOR UPDATE")) {
                    ps.setString(1, sourceId);
                    ps.setString(2, documentId);
                    ResultSet rs = ps.executeQuery();

                    if (rs.next()) {
                        String existingHash = rs.getString("hash");
                        if (hash.equals(existingHash)) {
                            try (PreparedStatement upd = conn.prepareStatement(
                                    "UPDATE rag_ingestion_hashes SET ingested_at = ?, stale = false " +
                                            "WHERE source_id = ? AND document_id = ?")) {
                                upd.setTimestamp(1, Timestamp.from(now));
                                upd.setString(2, sourceId);
                                upd.setString(3, documentId);
                                upd.executeUpdate();
                            }
                            changed = false;
                        } else {
                            try (PreparedStatement upd = conn.prepareStatement(
                                    "UPDATE rag_ingestion_hashes SET hash = ?, stale = false, " +
                                            "ingested_at = ?, updated_at = ? " +
                                            "WHERE source_id = ? AND document_id = ?")) {
                                upd.setString(1, hash);
                                upd.setTimestamp(2, Timestamp.from(now));
                                upd.setTimestamp(3, Timestamp.from(now));
                                upd.setString(4, sourceId);
                                upd.setString(5, documentId);
                                upd.executeUpdate();
                            }
                            changed = true;
                        }
                    } else {
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO rag_ingestion_hashes " +
                                        "(source_id, document_id, hash, stale, ingested_at, updated_at) " +
                                        "VALUES (?, ?, ?, false, ?, ?)")) {
                            ins.setString(1, sourceId);
                            ins.setString(2, documentId);
                            ins.setString(3, hash);
                            ins.setTimestamp(4, Timestamp.from(now));
                            ins.setTimestamp(5, Timestamp.from(now));
                            ins.executeUpdate();
                        }
                        changed = true;
                    }
                }
                conn.commit();
                return changed;
            } catch (SQLException e) {
                conn.rollback();
                throw new RuntimeException("Failed to check content hash for " + documentId, e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check content hash for " + documentId, e);
        }
    }

    @Override
    public int markStaleDocuments(String sourceId, List<String> documentIds) {
        ensureSchema();

        List<String> normalizedIds = documentIds.stream()
                .map(this::normalizeId)
                .filter(id -> !id.isEmpty())
                .toList();

        if (normalizedIds.isEmpty()) {
            LOGGER.debugf("All document IDs were empty after normalization for source '%s' — skipping stale marking", sourceId);
            return 0;
        }

        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE rag_ingestion_hashes SET stale = true, stale_at = NOW() " +
                                "WHERE source_id = ? AND stale = false " +
                                "AND document_id NOT IN (" +
                                normalizedIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(",")) + ")")) {
            ps.setString(1, sourceId);
            for (int i = 0; i < normalizedIds.size(); i++) {
                ps.setString(i + 2, normalizedIds.get(i));
            }
            int markedStale = ps.executeUpdate();
            if (markedStale > 0) {
                LOGGER.infof("Marked %d documents as stale for source '%s'", markedStale, sourceId);
            }
            return markedStale;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark stale documents for source " + sourceId, e);
        }
    }

    @Override
    public void clearSource(String sourceId) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM rag_ingestion_hashes WHERE source_id = ?")) {
            ps.setString(1, sourceId);
            ps.executeUpdate();
            LOGGER.infof("Cleared hash tracking for source '%s'", sourceId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear hash tracking for source " + sourceId, e);
        }
    }

    @Override
    public String computeHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String normalizeId(String id) {
        if (id == null) {
            return "";
        }
        String normalized = id;
        int hashIdx = normalized.indexOf('#');
        if (hashIdx >= 0) {
            normalized = normalized.substring(0, hashIdx);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
