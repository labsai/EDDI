/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * langchain4j's pgvector store with two additions it has no option for.
 * <ul>
 * <li><b>{@code hnsw.ef_search} per knowledge base.</b> langchain4j opens a
 * connection for every operation through the overridable
 * {@link #getConnection()} and offers no other hook, and it runs every
 * statement in autocommit, so the setting is applied to the session each time a
 * connection is borrowed. Connections are pooled and shared by every knowledge
 * base on the same server, so it is set on every borrow rather than once:
 * otherwise a knowledge base would search with whatever value the previous
 * borrower left behind.</li>
 * <li><b>Bounded insert batches.</b> {@code addAll} sends every row of a call
 * in one JDBC batch — one implicit transaction in autocommit mode. A large
 * document is split into batches of {@code insertBatchSize} rows so no single
 * transaction holds its locks and WAL for the whole document.</li>
 * </ul>
 */
final class EddiPgVectorEmbeddingStore extends PgVectorEmbeddingStore implements SegmentMetadataUpdates {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@code null} leaves the session default. Also {@code null} while the
     * superclass constructor runs (it creates the table through
     * {@link #getConnection()} before this field is assigned), which is fine: DDL
     * does not search.
     */
    private final Integer efSearch;
    private final int insertBatchSize;

    EddiPgVectorEmbeddingStore(DatasourceBuilder builder, Integer efSearch, int insertBatchSize) {
        super(builder);
        this.efSearch = efSearch;
        this.insertBatchSize = insertBatchSize;
    }

    @Override
    protected Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        Integer ef = efSearch;
        if (ef != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(PgVectorSchema.efSearchSql(ef));
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
        }
        return connection;
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        boolean sizesAgree = ids != null && embeddings != null && ids.size() == embeddings.size()
                && (embedded == null || embedded.size() == ids.size());
        if (!sizesAgree || ids.size() <= insertBatchSize) {
            // The superclass validates the arguments; mismatched lists reach it whole.
            super.addAll(ids, embeddings, embedded);
            return;
        }
        for (int from = 0; from < ids.size(); from += insertBatchSize) {
            int to = Math.min(from + insertBatchSize, ids.size());
            super.addAll(ids.subList(from, to), embeddings.subList(from, to), embedded == null ? null : embedded.subList(from, to));
        }
    }

    /**
     * One {@code UPDATE}, merging the new values into each matching row's metadata
     * — the vectors are not touched.
     *
     * <p>
     * Works on both layouts: the merge is computed as {@code jsonb}, and assigning
     * it to a {@code json} column (the earlier layout) goes through PostgreSQL's
     * assignment cast. The match is written as {@code (metadata->>'key')::text},
     * the expression the metadata indexes are built on, so a document's rows are
     * found by index. Keys are spliced into the statement and therefore held to
     * {@link SegmentMetadataUpdates#METADATA_KEY}; values are bound. A null value
     * removes its key, as on the in-memory store: a JSON {@code null} would come
     * back from a search as a metadata value langchain4j does not accept.
     */
    @Override
    public int updateMetadata(Map<String, String> match, Map<String, String> values) {
        SegmentMetadataUpdates.requireValid(match, values);
        List<String> keys = new ArrayList<>(match.keySet());
        Map<String, String> set = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();
        values.forEach((key, value) -> {
            if (value == null) {
                removed.add("'" + key + "'");
            } else {
                set.put(key, value);
            }
        });
        StringBuilder sql = new StringBuilder("UPDATE ").append(table)
                .append(" SET metadata = (COALESCE(metadata::jsonb, '{}'::jsonb)");
        if (!removed.isEmpty()) {
            sql.append(" - ARRAY[").append(String.join(", ", removed)).append("]::text[]");
        }
        sql.append(") || ?::jsonb WHERE ");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append("(metadata->>'").append(keys.get(i)).append("')::text = ?");
        }
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, JSON.writeValueAsString(set));
            for (int i = 0; i < keys.size(); i++) {
                statement.setString(i + 2, match.get(keys.get(i)));
            }
            return statement.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Could not update chunk metadata in " + table + ": " + e.getMessage(), e);
        }
    }

    /** For tests: the configured ef_search, {@code null} when not set. */
    Integer efSearch() {
        return efSearch;
    }

    /** For tests: the rows per INSERT batch. */
    int insertBatchSize() {
        return insertBatchSize;
    }
}
