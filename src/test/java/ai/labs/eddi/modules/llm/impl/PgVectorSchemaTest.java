/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.ingestion.IngestionPipeline;
import ai.labs.eddi.modules.llm.impl.PgVectorSchema.Layout;
import ai.labs.eddi.modules.llm.impl.PgVectorSchema.Settings;
import ai.labs.eddi.modules.llm.impl.PgVectorSchema.TableState;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("pgvector table layout and DDL")
class PgVectorSchemaTest {

    @Nested
    @DisplayName("store parameters")
    class SettingsParsing {

        @Test
        @DisplayName("unset parameters mean auto layout, HNSW with pgvector's defaults, 500-row batches")
        void defaults() {
            Settings settings = PgVectorSchema.settings(Map.of());
            assertNull(settings.layout());
            assertTrue(settings.hnsw());
            assertEquals(16, settings.m());
            assertEquals(64, settings.efConstruction());
            assertEquals(40, settings.efSearch());
            assertEquals(500, settings.insertBatchSize());
        }

        @Test
        @DisplayName("every parameter is read")
        void explicit() {
            Settings settings = PgVectorSchema.settings(Map.of("layout", "V2", "vectorIndex", "none", "hnswM", "24", "hnswEfConstruction", "200",
                    "hnswEfSearch", "120", "insertBatchSize", "50"));
            assertEquals(Layout.V2, settings.layout());
            assertFalse(settings.hnsw());
            assertEquals(24, settings.m());
            assertEquals(200, settings.efConstruction());
            assertEquals(120, settings.efSearch());
            assertEquals(50, settings.insertBatchSize());
            assertEquals(Layout.V1, PgVectorSchema.settings(Map.of("layout", "v1")).layout());
            assertNull(PgVectorSchema.settings(Map.of("layout", "auto")).layout());
        }

        @Test
        @DisplayName("invalid values are refused, naming the parameter but never echoing the value")
        void invalid() {
            assertRefused(Map.of("layout", "v3-secret"), "storeParameters.layout");
            assertRefused(Map.of("vectorIndex", "ivfflat-secret"), "storeParameters.vectorIndex");
            assertRefused(Map.of("hnswM", "many-secret"), "storeParameters.hnswM");
            assertRefused(Map.of("hnswM", "1"), "storeParameters.hnswM");
            assertRefused(Map.of("hnswEfSearch", "0"), "storeParameters.hnswEfSearch");
            assertRefused(Map.of("insertBatchSize", "100000"), "storeParameters.insertBatchSize");
            assertRefused(Map.of("hnswM", "48", "hnswEfConstruction", "64"), "storeParameters.hnswEfConstruction");
        }

        private void assertRefused(Map<String, String> params, String expectedMention) {
            var e = assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.settings(params));
            assertTrue(e.getMessage().contains(expectedMention), e.getMessage());
            assertFalse(e.getMessage().contains("secret"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("which layout a store gets")
    class LayoutResolution {

        private final TableState absent = TableState.absent();
        private final TableState legacy = new TableState(true, "json");
        private final TableState scaled = new TableState(true, "jsonb");

        @Test
        @DisplayName("auto: a new table is v2, an existing table keeps the layout it has")
        void auto() {
            assertEquals(Layout.V2, PgVectorSchema.resolveLayout(null, absent, "t"));
            assertEquals(Layout.V1, PgVectorSchema.resolveLayout(null, legacy, "t"));
            assertEquals(Layout.V2, PgVectorSchema.resolveLayout(null, scaled, "t"));
            // A table without a metadata column (made by hand) is left to behave as before.
            assertEquals(Layout.V1, PgVectorSchema.resolveLayout(null, new TableState(true, null), "t"));
        }

        @Test
        @DisplayName("an explicit layout creates a new table in it and matches an existing one")
        void explicit() {
            assertEquals(Layout.V1, PgVectorSchema.resolveLayout(Layout.V1, absent, "t"));
            assertEquals(Layout.V2, PgVectorSchema.resolveLayout(Layout.V2, absent, "t"));
            assertEquals(Layout.V1, PgVectorSchema.resolveLayout(Layout.V1, legacy, "t"));
            assertEquals(Layout.V2, PgVectorSchema.resolveLayout(Layout.V2, scaled, "t"));
        }

        @Test
        @DisplayName("an existing table is never converted: asking for the other layout is refused with the way out")
        void neverConverts() {
            var e = assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.resolveLayout(Layout.V2, legacy, "kb_table"));
            assertTrue(e.getMessage().contains("kb_table"), e.getMessage());
            assertTrue(e.getMessage().contains("re-ingest"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.resolveLayout(Layout.V1, scaled, "t"));
        }

        @Test
        @DisplayName("v1 keeps langchain4j's json metadata; v2 stores jsonb")
        void metadataStorage() {
            assertEquals(MetadataStorageMode.COMBINED_JSON, PgVectorSchema.metadataStorageConfig(Layout.V1).storageMode());
            var v2 = PgVectorSchema.metadataStorageConfig(Layout.V2);
            assertEquals(MetadataStorageMode.COMBINED_JSONB, v2.storageMode());
            assertEquals(List.of("metadata JSONB NULL"), v2.columnDefinitions());
            assertTrue(v2.indexes() == null || v2.indexes().isEmpty(), "langchain4j must not create metadata indexes itself");
        }
    }

    @Nested
    @DisplayName("DDL")
    class Ddl {

        @Test
        @DisplayName("HNSW on the embedding with cosine ops and the configured build parameters")
        void hnsw() {
            assertEquals("CREATE INDEX IF NOT EXISTS \"eddi_kbid_abc_hnsw_idx\" ON \"eddi_kbid_abc\" USING hnsw (embedding vector_cosine_ops) "
                    + "WITH (m = 24, ef_construction = 200)", PgVectorSchema.hnswIndexSql("eddi_kbid_abc", 24, 200));
        }

        @Test
        @DisplayName("expression indexes on exactly what langchain4j's filter mapper emits")
        void metadataIndexes() {
            assertEquals(List.of(
                    "CREATE INDEX IF NOT EXISTS \"t_doc_src_idx\" ON \"t\" ((metadata->>'documentId'), (metadata->>'sourceKey'))",
                    "CREATE INDEX IF NOT EXISTS \"t_src_run_idx\" ON \"t\" ((metadata->>'sourceKey'), (metadata->>'runId'))"),
                    PgVectorSchema.metadataIndexSql("t"));
        }

        @Test
        @DisplayName("table names are quoted lower-case, the way PostgreSQL folds the unquoted name langchain4j uses")
        void quotedTable() {
            assertEquals("\"kb\"", PgVectorSchema.quotedTable("KB"));
            assertEquals("\"rag\".\"kb_1$x\"", PgVectorSchema.quotedTable(" Rag.KB_1$x "));
            assertEquals("\"" + "a".repeat(63) + "\"", PgVectorSchema.quotedTable("a".repeat(63)));
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.quotedTable("a".repeat(64)),
                    "PostgreSQL would truncate a 64-character name");
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.quotedTable(null));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "t;drop table x", "t\"x", "t x", "t--", "a.b.c", ".t", "t.", "1t", "t/*", "t'x"})
        @DisplayName("any table name that is not a plain identifier never reaches the DDL")
        void unsafeTableRefused(String table) {
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.metadataIndexSql(table));
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.hnswIndexSql(table, 16, 64));
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.quotedTable(table));
        }

        @Test
        @DisplayName("an unsafe table name is refused before any statement runs")
        void unsafeTableRunsNothing() throws SQLException {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);
            assertThrows(IllegalArgumentException.class,
                    () -> PgVectorSchema.createIndexes(connection, "t; drop table x", PgVectorSchema.settings(Map.of()), 1536));
            verify(statement, never()).executeUpdate(anyString());
        }

        @Test
        @DisplayName("numeric DDL parameters are range-checked where they meet SQL")
        void numbersOutOfRange() {
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.hnswIndexSql("t", 1, 64));
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.hnswIndexSql("t", 101, 1000));
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.hnswIndexSql("t", 16, 3));
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.hnswIndexSql("t", 16, 1001));
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.efSearchSql(0));
            assertThrows(IllegalArgumentException.class, () -> PgVectorSchema.efSearchSql(1001));
        }

        @Test
        @DisplayName("the indexed keys are the ones ingestion writes and filters on")
        void keysMatchIngestion() {
            assertEquals(IngestionPipeline.METADATA_DOCUMENT_ID, PgVectorSchema.METADATA_DOCUMENT_ID);
            assertEquals(IngestionPipeline.METADATA_SOURCE_KEY, PgVectorSchema.METADATA_SOURCE_KEY);
            assertEquals(IngestionPipeline.METADATA_RUN_ID, PgVectorSchema.METADATA_RUN_ID);
        }

        @Test
        @DisplayName("a schema-qualified table gets an unqualified, lower-case index name and keeps its qualified name in ON")
        void qualified() {
            assertEquals("kb_hnsw_idx", PgVectorSchema.indexName("Rag.KB", PgVectorSchema.SUFFIX_HNSW));
            assertTrue(PgVectorSchema.hnswIndexSql("rag.kb", 16, 64).contains("\"kb_hnsw_idx\" ON \"rag\".\"kb\" USING"));
        }

        @Test
        @DisplayName("a long table name is shortened with a digest: at most 63 characters, never shared")
        void longNames() {
            String a = "a".repeat(63);
            String b = "rag." + a;
            String nameA = PgVectorSchema.indexName(a, PgVectorSchema.SUFFIX_DOC_SOURCE);
            String nameB = PgVectorSchema.indexName(b, PgVectorSchema.SUFFIX_DOC_SOURCE);
            assertEquals(63, nameA.length());
            assertTrue(nameA.endsWith(PgVectorSchema.SUFFIX_DOC_SOURCE));
            assertNotEquals(nameA, nameB, "the same table name in two schemas must not share an index name");
            assertNotEquals(nameA, PgVectorSchema.indexName(a.substring(1) + "b", PgVectorSchema.SUFFIX_DOC_SOURCE));
        }

        @Test
        @DisplayName("ef_search is a session SET of an int")
        void efSearch() {
            assertEquals("SET hnsw.ef_search = 120", PgVectorSchema.efSearchSql(120));
        }

        @Test
        @DisplayName("JDBC URL: IPv6 bracketed, database URL-encoded so it cannot add driver properties")
        void jdbcUrl() {
            assertEquals("jdbc:postgresql://db.example.com:5432/eddi", PgVectorConnectionPools.jdbcUrl("db.example.com", 5432, "eddi"));
            assertEquals("jdbc:postgresql://[::1]:5433/eddi", PgVectorConnectionPools.jdbcUrl("::1", 5433, "eddi"));
            assertEquals("jdbc:postgresql://[::1]:5433/eddi", PgVectorConnectionPools.jdbcUrl("[::1]", 5433, "eddi"));
            String url = PgVectorConnectionPools.jdbcUrl("h", 1, "eddi?socketFactory=x&a=b");
            assertFalse(url.contains("?"), url);
            assertFalse(url.contains("&"), url);
        }
    }

    @Nested
    @DisplayName("against a mocked connection")
    class WithConnection {

        @Test
        @DisplayName("the vector extension is created only when it is missing")
        void extension() throws SQLException {
            Connection present = connectionWithQueryResult(true);
            PgVectorSchema.ensureExtension(present);
            verify(present.createStatement(), never()).executeUpdate(anyString());

            Connection missing = connectionWithQueryResult(false);
            PgVectorSchema.ensureExtension(missing);
            verify(missing.createStatement()).executeUpdate("CREATE EXTENSION IF NOT EXISTS vector");
        }

        @Test
        @DisplayName("v2 index creation: metadata indexes always, HNSW only when enabled and within pgvector's dimension limit")
        void createIndexes() throws SQLException {
            Settings hnsw = PgVectorSchema.settings(Map.of());
            Settings none = PgVectorSchema.settings(Map.of("vectorIndex", "none"));

            List<String> executed = executedDdl(hnsw, 1536);
            assertEquals(3, executed.size());
            assertTrue(executed.get(2).contains("USING hnsw"));

            assertEquals(2, executedDdl(none, 1536).size());
            assertEquals(2, executedDdl(hnsw, 3072).size(), "3072 dimensions exceed HNSW's limit: no index, no failure");
        }

        @Test
        @DisplayName("an index that exists is not created again: no DDL, so no SHARE lock behind open writers")
        void existingIndexesSkipped() throws SQLException {
            Settings hnsw = PgVectorSchema.settings(Map.of());
            List<String> binds = new ArrayList<>();
            Connection connection = connectionWithIndexes(binds, "\"t_hnsw_idx\"", "\"t_doc_src_idx\"");
            Statement statement = connection.createStatement();
            List<String> executed = new ArrayList<>();
            when(statement.executeUpdate(anyString())).thenAnswer(inv -> {
                executed.add(inv.getArgument(0));
                return 0;
            });
            PgVectorSchema.createIndexes(connection, "t", hnsw, 1536);
            assertEquals(List.of("\"t_doc_src_idx\"", "\"t_src_run_idx\"", "\"t_hnsw_idx\""), binds,
                    "every index is looked up, by bound name");
            assertEquals(1, executed.size(), executed.toString());
            assertTrue(executed.get(0).contains("\"t_src_run_idx\""), "only the missing index is created");
            assertTrue(executed.get(0).contains("IF NOT EXISTS"), "IF NOT EXISTS stays for concurrent builds");
        }

        @Test
        @DisplayName("an index is looked up in its table's schema: the named one, else current_schema()")
        void indexLookupSchema() throws SQLException {
            List<String> sql = new ArrayList<>();
            List<String> binds = new ArrayList<>();
            Connection qualified = capturingConnection(sql, binds, false);
            assertFalse(PgVectorSchema.indexExists(qualified, "Rag.KB", PgVectorSchema.SUFFIX_HNSW));
            assertEquals("SELECT to_regclass(?) IS NOT NULL", sql.get(0));
            assertEquals("\"rag\".\"kb_hnsw_idx\"", binds.get(0));

            Connection plain = capturingConnection(sql, binds, true);
            assertTrue(PgVectorSchema.indexExists(plain, "KB", PgVectorSchema.SUFFIX_HNSW));
            assertEquals("SELECT to_regclass(quote_ident(current_schema()) || '.' || ?) IS NOT NULL", sql.get(1));
            assertEquals("\"kb_hnsw_idx\"", binds.get(1));
        }

        @Test
        @DisplayName("inspect: an unqualified table is looked up in current_schema(), where langchain4j creates it")
        void inspectSchema() throws SQLException {
            List<String> sql = new ArrayList<>();
            List<String> binds = new ArrayList<>();
            assertFalse(PgVectorSchema.inspect(capturingConnection(sql, binds, false), "docs").exists());
            assertTrue(sql.get(0).contains("FROM (SELECT to_regclass(quote_ident(current_schema()) || '.' || ?) AS oid) r"), sql.get(0));
            assertEquals("docs", binds.get(0));

            assertFalse(PgVectorSchema.inspect(capturingConnection(sql, binds, false), "rag.docs").exists());
            assertTrue(sql.get(1).contains("FROM (SELECT to_regclass(?) AS oid) r"), sql.get(1));
            assertEquals("rag.docs", binds.get(1));
        }

        @Test
        @DisplayName("a failing HNSW build (old pgvector) is logged and the store works without it")
        void hnswFailureTolerated() throws SQLException {
            Connection connection = connectionWithIndexes(new ArrayList<>());
            Statement statement = connection.createStatement();
            when(statement.executeUpdate(anyString())).thenAnswer(inv -> {
                if (inv.<String>getArgument(0).contains("hnsw")) {
                    throw new SQLException("access method \"hnsw\" does not exist");
                }
                return 0;
            });
            PgVectorSchema.createIndexes(connection, "t", PgVectorSchema.settings(Map.of()), 1536);
            verify(statement, times(3)).executeUpdate(anyString());
        }

        /**
         * A connection whose index lookups find the named indexes; each looked-up name
         * is added to {@code binds}.
         */
        private Connection connectionWithIndexes(List<String> binds, String... existing) throws SQLException {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);
            when(connection.prepareStatement(anyString())).thenAnswer(prepare -> {
                PreparedStatement ps = mock(PreparedStatement.class);
                String[] bound = new String[1];
                doAnswer(inv -> {
                    bound[0] = inv.getArgument(1);
                    binds.add(bound[0]);
                    return null;
                }).when(ps).setString(eq(1), anyString());
                when(ps.executeQuery()).thenAnswer(query -> {
                    ResultSet rs = mock(ResultSet.class);
                    boolean found = List.of(existing).contains(bound[0]);
                    when(rs.next()).thenReturn(true);
                    when(rs.getBoolean(1)).thenReturn(found);
                    return rs;
                });
                return ps;
            });
            return connection;
        }

        /**
         * A connection recording each prepared SQL and its bound name; every query
         * answers {@code result}.
         */
        private Connection capturingConnection(List<String> sql, List<String> binds, boolean result) throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenAnswer(prepare -> {
                sql.add(prepare.getArgument(0));
                PreparedStatement ps = mock(PreparedStatement.class);
                doAnswer(inv -> {
                    binds.add(inv.getArgument(1));
                    return null;
                }).when(ps).setString(eq(1), anyString());
                ResultSet rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(true);
                when(rs.getBoolean(1)).thenReturn(result);
                when(ps.executeQuery()).thenReturn(rs);
                return ps;
            });
            return connection;
        }

        private List<String> executedDdl(Settings settings, int dimension) throws SQLException {
            Connection connection = connectionWithIndexes(new ArrayList<>());
            Statement statement = connection.createStatement();
            List<String> executed = new ArrayList<>();
            when(statement.executeUpdate(anyString())).thenAnswer(inv -> {
                executed.add(inv.getArgument(0));
                return 0;
            });
            PgVectorSchema.createIndexes(connection, "t", settings, dimension);
            return executed;
        }

        private Connection connectionWithQueryResult(boolean hasRow) throws SQLException {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            ResultSet rs = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(rs);
            when(rs.next()).thenReturn(hasRow);
            return connection;
        }
    }

    @Nested
    @DisplayName("EDDI's store subclass")
    class StoreSubclass {

        @Test
        @DisplayName("a large addAll is split into insertBatchSize-row batches; ef_search is set on every borrowed connection")
        void batchesAndEfSearch() throws SQLException {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            PreparedStatement insert = mock(PreparedStatement.class);
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(connection.prepareStatement(anyString())).thenReturn(insert);
            when(connection.unwrap(PGConnection.class)).thenReturn(mock(PGConnection.class));
            when(connection.isWrapperFor(PGConnection.class)).thenReturn(true);

            var builder = PgVectorEmbeddingStore.datasourceBuilder().datasource(dataSource).table("t").dimension(3).useIndex(false)
                    .createTable(true).dropTableFirst(false).skipCreateVectorExtension(true)
                    .metadataStorageConfig(PgVectorSchema.metadataStorageConfig(Layout.V2));
            var store = new EddiPgVectorEmbeddingStore(builder, 77, 2);
            // The table was created through getConnection before ef_search was known: no
            // SET then.
            verify(statement, never()).execute("SET hnsw.ef_search = 77");

            int rows = 5;
            List<String> ids = new ArrayList<>();
            List<Embedding> embeddings = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                ids.add(UUID.randomUUID().toString());
                embeddings.add(Embedding.from(new float[]{1f, 0f, (float) i}));
                segments.add(TextSegment.from("chunk " + i, new Metadata(new HashMap<>(Map.of("documentId", "d", "sourceKey", "s")))));
            }
            store.addAll(ids, embeddings, segments);

            verify(insert, times(3)).executeBatch();
            verify(insert, times(rows)).addBatch();
            verify(statement, times(3)).execute("SET hnsw.ef_search = 77");
            assertEquals(77, store.efSearch());
            assertEquals(2, store.insertBatchSize());
        }
    }
}
