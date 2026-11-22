/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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
final class EddiPgVectorEmbeddingStore extends PgVectorEmbeddingStore {

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

    /** For tests: the configured ef_search, {@code null} when not set. */
    Integer efSearch() {
        return efSearch;
    }

    /** For tests: the rows per INSERT batch. */
    int insertBatchSize() {
        return insertBatchSize;
    }
}
