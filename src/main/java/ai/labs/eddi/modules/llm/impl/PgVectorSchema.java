/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The table layout of a pgvector knowledge base, and the DDL EDDI runs for it.
 * <p>
 * langchain4j's {@code PgVectorEmbeddingStore} creates the table but offers
 * only an IVFFlat index, and stores metadata in a {@code json} column nothing
 * can index. At ten million chunks that makes every retrieval an exact scan and
 * every document replacement ({@code removeAll} by document id and owning
 * source) a sequential scan. Two layouts therefore exist:
 * <ul>
 * <li>{@link Layout#V1} — the table every EDDI release up to 6.7 created:
 * {@code metadata json}, no index besides the primary key. Still read and
 * written exactly as before.</li>
 * <li>{@link Layout#V2} — {@code metadata jsonb}, an HNSW index on the
 * embedding (cosine, the operator langchain4j's query orders by), and b-tree
 * expression indexes on {@code (documentId, sourceKey)} and
 * {@code (sourceKey, runId)} — the keys every replacement filter starts
 * with.</li>
 * </ul>
 * <p>
 * The metadata indexes are expressions on {@code metadata->>'key'} because that
 * is exactly what langchain4j's filter mapper emits for a combined JSON(B)
 * column: {@code (metadata->>'documentId')::text = ?}. The {@code ::text} cast
 * of a {@code text} expression is removed by the PostgreSQL parser, so the
 * predicate matches the index expression. Column-per-key storage would index as
 * well, but it drops every metadata key that has no column — the URL, title,
 * source name and loader metadata EDDI also stores.
 * <p>
 * Which layout a knowledge base gets is decided by the table, not by the
 * knowledge base's age: {@code storeParameters.layout} defaults to
 * {@code auto}, which keeps an existing table in the layout it has (its
 * {@code metadata} column type tells which) and creates a missing one in
 * {@code v2}. Nothing ever converts or re-indexes an existing {@code v1} table:
 * building an HNSW index over millions of rows would block writes for as long
 * as it takes, from inside a request. Moving a knowledge base to the new layout
 * is a re-embed into a new table.
 */
final class PgVectorSchema {

    private static final Logger LOGGER = Logger.getLogger(PgVectorSchema.class);

    /** The table layouts — see the class comment. */
    enum Layout {
        V1, V2
    }

    static final String PARAM_LAYOUT = "layout";
    static final String PARAM_VECTOR_INDEX = "vectorIndex";
    static final String PARAM_HNSW_M = "hnswM";
    static final String PARAM_HNSW_EF_CONSTRUCTION = "hnswEfConstruction";
    static final String PARAM_HNSW_EF_SEARCH = "hnswEfSearch";
    static final String PARAM_INSERT_BATCH_SIZE = "insertBatchSize";

    static final String LAYOUT_AUTO = "auto";
    static final String VECTOR_INDEX_HNSW = "hnsw";
    static final String VECTOR_INDEX_NONE = "none";

    /** pgvector's own defaults, so an unset parameter means what pgvector means. */
    static final int DEFAULT_HNSW_M = 16;
    static final int DEFAULT_HNSW_EF_CONSTRUCTION = 64;
    static final int DEFAULT_HNSW_EF_SEARCH = 40;

    /**
     * Rows per INSERT batch. pgjdbc sends a batch in autocommit mode as one
     * implicit transaction, and langchain4j hands it every segment of a call at
     * once — a large document is then one long transaction holding its locks and
     * WAL for the whole write. Five hundred rows of a 1536-dimension model are
     * about 3 MB of vectors.
     */
    static final int DEFAULT_INSERT_BATCH_SIZE = 500;

    /**
     * pgvector cannot build an HNSW index over a {@code vector} column of more
     * dimensions than this.
     */
    static final int MAX_HNSW_DIMENSIONS = 2000;

    /** PostgreSQL truncates longer identifiers, which could make two names one. */
    static final int MAX_IDENTIFIER_LENGTH = 63;

    /**
     * The chunk metadata keys the replacement filters use — the same strings as
     * {@code IngestionPipeline.METADATA_*} (a test holds them equal), repeated so
     * this package does not depend on the ingestion module.
     */
    static final String METADATA_DOCUMENT_ID = "documentId";
    static final String METADATA_SOURCE_KEY = "sourceKey";
    static final String METADATA_RUN_ID = "runId";

    static final String SUFFIX_HNSW = "_hnsw_idx";
    static final String SUFFIX_DOC_SOURCE = "_doc_src_idx";
    static final String SUFFIX_SOURCE_RUN = "_src_run_idx";

    private PgVectorSchema() {
    }

    /**
     * The pgvector settings of one knowledge base, parsed from its resolved
     * {@code storeParameters}.
     *
     * @param layout
     *            the requested layout, or {@code null} for {@code auto}
     * @param hnsw
     *            whether a {@code v2} table gets an HNSW index
     */
    record Settings(Layout layout, boolean hnsw, int m, int efConstruction, int efSearch, int insertBatchSize) {
    }

    /** What a table looks like before EDDI touches it. */
    record TableState(boolean exists, String metadataType) {
        static TableState absent() {
            return new TableState(false, null);
        }
    }

    /**
     * Parses and range-checks the pgvector parameters. Messages name the parameter
     * but never echo its value: vault references are resolved by now, and the
     * message reaches ingestion status and RAG search responses.
     */
    static Settings settings(Map<String, String> params) {
        Layout layout = parseLayout(params.get(PARAM_LAYOUT));
        boolean hnsw = parseVectorIndex(params.get(PARAM_VECTOR_INDEX));
        int m = intParam(params, PARAM_HNSW_M, DEFAULT_HNSW_M, 2, 100);
        int efConstruction = intParam(params, PARAM_HNSW_EF_CONSTRUCTION, DEFAULT_HNSW_EF_CONSTRUCTION, 4, 1000);
        if (efConstruction < 2 * m) {
            throw new IllegalArgumentException("storeParameters." + PARAM_HNSW_EF_CONSTRUCTION + " must be at least twice storeParameters."
                    + PARAM_HNSW_M + " (pgvector refuses to build the index otherwise)");
        }
        int efSearch = intParam(params, PARAM_HNSW_EF_SEARCH, DEFAULT_HNSW_EF_SEARCH, 1, 1000);
        int insertBatchSize = intParam(params, PARAM_INSERT_BATCH_SIZE, DEFAULT_INSERT_BATCH_SIZE, 1, 10_000);
        return new Settings(layout, hnsw, m, efConstruction, efSearch, insertBatchSize);
    }

    private static Layout parseLayout(String raw) {
        if (raw == null || raw.isBlank() || LAYOUT_AUTO.equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "v1" -> Layout.V1;
            case "v2" -> Layout.V2;
            default -> throw new IllegalArgumentException("Invalid storeParameters." + PARAM_LAYOUT + " for pgvector. Valid values: auto, v1, v2");
        };
    }

    private static boolean parseVectorIndex(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case VECTOR_INDEX_HNSW -> true;
            case VECTOR_INDEX_NONE -> false;
            default -> throw new IllegalArgumentException(
                    "Invalid storeParameters." + PARAM_VECTOR_INDEX + " for pgvector. Valid values: " + VECTOR_INDEX_HNSW + ", " + VECTOR_INDEX_NONE);
        };
    }

    private static int intParam(Map<String, String> params, String key, int defaultValue, int min, int max) {
        String raw = params.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value for storeParameters." + key);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("storeParameters." + key + " must be between " + min + " and " + max);
        }
        return value;
    }

    /**
     * The layout to build a store in.
     *
     * @param requested
     *            {@code null} for {@code auto}
     */
    static Layout resolveLayout(Layout requested, TableState state, String table) {
        if (!state.exists()) {
            return requested == null ? Layout.V2 : requested;
        }
        Layout actual = "jsonb".equalsIgnoreCase(state.metadataType()) ? Layout.V2 : Layout.V1;
        if (requested != null && requested != actual) {
            throw new IllegalArgumentException(String.format(
                    "pgvector table %s already exists in the %s layout, but storeParameters.%s asks for %s. An existing table is never "
                            + "converted: to move this knowledge base to the %s layout, point it at a new table (storeParameters.table) "
                            + "and re-ingest its sources; otherwise remove storeParameters.%s",
                    sanitize(table), actual.name().toLowerCase(Locale.ROOT), PARAM_LAYOUT, requested.name().toLowerCase(Locale.ROOT),
                    requested.name().toLowerCase(Locale.ROOT), PARAM_LAYOUT));
        }
        return actual;
    }

    /**
     * How langchain4j stores metadata for a layout: {@code json} (its default, and
     * what every earlier table has) or {@code jsonb}. No langchain4j-created
     * metadata indexes: its index names are built from the table name, which is
     * invalid SQL for a schema-qualified table — EDDI creates them itself.
     */
    static MetadataStorageConfig metadataStorageConfig(Layout layout) {
        if (layout == Layout.V1) {
            return DefaultMetadataStorageConfig.defaultConfig();
        }
        return DefaultMetadataStorageConfig.builder()
                .storageMode(MetadataStorageMode.COMBINED_JSONB)
                .columnDefinitions(List.of("metadata JSONB NULL"))
                .build();
    }

    /** The table part of a possibly schema-qualified name. */
    static String unqualified(String table) {
        int dot = table.lastIndexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
    }

    /**
     * An index name for {@code table}: unqualified (an index lives in its table's
     * schema), lower-case (PostgreSQL folds unquoted names), and never longer than
     * 63 characters — a long table name is shortened and given a digest of the
     * full, qualified name, so two tables never share an index name.
     */
    static String indexName(String table, String suffix) {
        String trimmed = table.trim();
        String base = unqualified(trimmed).toLowerCase(Locale.ROOT);
        if (base.length() + suffix.length() <= MAX_IDENTIFIER_LENGTH) {
            return base + suffix;
        }
        String digest = shortDigest(trimmed.toLowerCase(Locale.ROOT));
        int keep = MAX_IDENTIFIER_LENGTH - suffix.length() - digest.length() - 1;
        return base.substring(0, keep) + "_" + digest + suffix;
    }

    /**
     * One part of a table name: a letter or underscore, then letters, digits,
     * underscores or {@code $} — at most 63 characters. The same rule
     * {@code KnowledgeBaseStorage.isPlainPgIdentifier} applies when the name is
     * resolved; it is checked again here because this is where the name meets SQL.
     */
    private static final Pattern IDENTIFIER_PART = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}");

    /**
     * {@code table} — a plain or schema-qualified name — as a quoted SQL
     * identifier. Each part is checked against {@link #IDENTIFIER_PART}, so it
     * holds no quote, whitespace, comment or separator, and is folded to lower case
     * before it is quoted: {@code "kb"} is the table the unquoted {@code KB} in
     * langchain4j's own SQL names, so the indexes land on the table langchain4j
     * created.
     *
     * @throws IllegalArgumentException
     *             for any other name; the message does not echo it
     */
    static String quotedTable(String table) {
        if (table == null) {
            throw new IllegalArgumentException("pgvector table name is missing");
        }
        String[] parts = table.trim().split("\\.", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("pgvector table name must be a plain table name, optionally qualified with a schema");
        }
        StringBuilder quoted = new StringBuilder();
        for (String part : parts) {
            if (!quoted.isEmpty()) {
                quoted.append('.');
            }
            quoted.append(quotedIdentifier(part));
        }
        return quoted.toString();
    }

    /** One identifier part, checked and quoted — see {@link #quotedTable}. */
    private static String quotedIdentifier(String part) {
        if (!IDENTIFIER_PART.matcher(part).matches()) {
            throw new IllegalArgumentException("pgvector table name must be a plain table name, optionally qualified with a schema");
        }
        return '"' + part.toLowerCase(Locale.ROOT) + '"';
    }

    private static int requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    /** The HNSW index — cosine, because langchain4j orders by {@code <=>}. */
    static String hnswIndexSql(String table, int m, int efConstruction) {
        return String.format("CREATE INDEX IF NOT EXISTS %s ON %s USING hnsw (embedding vector_cosine_ops) WITH (m = %d, ef_construction = %d)",
                quotedIdentifier(indexName(table, SUFFIX_HNSW)), quotedTable(table), requireRange(m, 2, 100, PARAM_HNSW_M),
                requireRange(efConstruction, 4, 1000, PARAM_HNSW_EF_CONSTRUCTION));
    }

    /**
     * The b-tree indexes the replacement filters use: {@code documentId} and
     * {@code sourceKey} (a document's chunks), and {@code sourceKey} and
     * {@code runId} (a source's chunks, and those a run did not write). A filter on
     * {@code sourceKey} alone uses the second one's leading column.
     */
    static List<String> metadataIndexSql(String table) {
        String quoted = quotedTable(table);
        return List.of(
                String.format("CREATE INDEX IF NOT EXISTS %s ON %s ((metadata->>'%s'), (metadata->>'%s'))",
                        quotedIdentifier(indexName(table, SUFFIX_DOC_SOURCE)), quoted, METADATA_DOCUMENT_ID, METADATA_SOURCE_KEY),
                String.format("CREATE INDEX IF NOT EXISTS %s ON %s ((metadata->>'%s'), (metadata->>'%s'))",
                        quotedIdentifier(indexName(table, SUFFIX_SOURCE_RUN)), quoted, METADATA_SOURCE_KEY, METADATA_RUN_ID));
    }

    /**
     * Sets the HNSW candidate list for the session. Session-level, not
     * {@code SET LOCAL}: langchain4j runs every statement in autocommit, where a
     * {@code SET LOCAL} ends with the statement that set it. The value is an int,
     * so nothing a user wrote reaches the SQL.
     */
    static String efSearchSql(int efSearch) {
        return "SET hnsw.ef_search = " + requireRange(efSearch, 1, 1000, PARAM_HNSW_EF_SEARCH);
    }

    /**
     * Creates the {@code vector} extension when it is missing. Checked first, so a
     * database user without CREATE on the database works against a database where
     * the extension is already installed — langchain4j ran
     * {@code CREATE EXTENSION IF NOT EXISTS} on every connection it opened, which
     * needs that privilege even when it does nothing.
     */
    static void ensureExtension(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'vector'")) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE EXTENSION IF NOT EXISTS vector");
        }
    }

    /**
     * Whether {@code table} exists, and the type of its {@code metadata} column.
     * {@code to_regclass} folds the name to lower case, as PostgreSQL does with the
     * unquoted name in langchain4j's SQL. An unqualified name is looked up in
     * {@code current_schema()} only — not through the whole {@code search_path} —
     * because that is where langchain4j's {@code CREATE TABLE IF NOT EXISTS}
     * creates it: with {@code search_path = myschema, public} and only
     * {@code public.docs} present, the store uses a new {@code myschema.docs}, so
     * that, not {@code public.docs}, is the table whose layout matters.
     */
    static TableState inspect(Connection connection, String table) throws SQLException {
        String sql = "SELECT r.oid IS NOT NULL, (SELECT format_type(a.atttypid, NULL) FROM pg_attribute a "
                + "WHERE a.attrelid = r.oid AND a.attname = 'metadata' AND NOT a.attisdropped) FROM (SELECT "
                + regclass(isQualified(table)) + " AS oid) r";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || !rs.getBoolean(1)) {
                    return TableState.absent();
                }
                return new TableState(true, rs.getString(2));
            }
        }
    }

    /**
     * Creates the {@code v2} indexes that are missing. Each index is looked up
     * first and its DDL skipped when it exists: {@code CREATE INDEX IF NOT EXISTS}
     * takes a {@code SHARE} lock on the table before it finds the index, so on
     * every store build it would wait behind open write transactions and hold up
     * the writers queued after it. {@code IF NOT EXISTS} stays for two builds
     * racing to create the same index. A failure to build the HNSW index (pgvector
     * older than 0.5.0) is logged and the store works without it — exact scans, as
     * before.
     */
    static void createIndexes(Connection connection, String table, Settings settings, int dimension) throws SQLException {
        List<String> suffixes = List.of(SUFFIX_DOC_SOURCE, SUFFIX_SOURCE_RUN);
        List<String> ddl = metadataIndexSql(table);
        try (Statement statement = connection.createStatement()) {
            for (int i = 0; i < ddl.size(); i++) {
                if (!indexExists(connection, table, suffixes.get(i))) {
                    statement.executeUpdate(ddl.get(i));
                }
            }
        }
        if (!settings.hnsw()) {
            return;
        }
        if (dimension > MAX_HNSW_DIMENSIONS) {
            LOGGER.warnf("pgvector table %s has %d dimensions; pgvector cannot build an HNSW index over more than %d, so retrieval "
                    + "scans every row. Use an embedding model or dimensions setting of at most %d for an indexed knowledge base.",
                    sanitize(table), dimension, MAX_HNSW_DIMENSIONS, MAX_HNSW_DIMENSIONS);
            return;
        }
        String hnswSql = hnswIndexSql(table, settings.m(), settings.efConstruction());
        try (Statement statement = connection.createStatement()) {
            if (!indexExists(connection, table, SUFFIX_HNSW)) {
                statement.executeUpdate(hnswSql);
            }
        } catch (SQLException e) {
            LOGGER.warnf("Could not create the HNSW index on pgvector table %s (%s); retrieval scans every row. HNSW needs pgvector 0.5.0 "
                    + "or later.", sanitize(table), sanitize(e.getMessage()));
        }
    }

    /**
     * Whether the index {@link #indexName(String, String) indexName(table, suffix)}
     * exists in {@code table}'s schema — the schema named in {@code table}, else
     * {@code current_schema()}, as for {@link #inspect}. The name is bound, never
     * spliced into the SQL.
     */
    static boolean indexExists(Connection connection, String table, String suffix) throws SQLException {
        String trimmed = table.trim();
        boolean qualified = isQualified(trimmed);
        String index = quotedIdentifier(indexName(trimmed, suffix));
        String name = qualified ? quotedIdentifier(trimmed.substring(0, trimmed.lastIndexOf('.'))) + "." + index : index;
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + regclass(qualified) + " IS NOT NULL")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static boolean isQualified(String table) {
        return table.indexOf('.') >= 0;
    }

    /**
     * {@code to_regclass} over one bound name: as given when schema-qualified,
     * otherwise in {@code current_schema()} — where an unqualified
     * {@code CREATE TABLE} or {@code CREATE INDEX} puts it — rather than the first
     * match anywhere on the {@code search_path}.
     */
    private static String regclass(boolean qualified) {
        return qualified ? "to_regclass(?)" : "to_regclass(quote_ident(current_schema()) || '.' || ?)";
    }

    private static String shortDigest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
