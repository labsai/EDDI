/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresContentHashStore} using Testcontainers.
 *
 * @since 6.0.3
 */
@DisplayName("PostgresContentHashStore IT")
class PostgresContentHashStoreTest extends PostgresTestBase {

    private static PostgresContentHashStore store;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        ds = createDataSource();
        store = new PostgresContentHashStore(createDataSourceInstance());
        store.ensureSchema();
    }

    @BeforeEach
    void clean() throws Exception {
        truncateTables(ds, "rag_ingestion_hashes");
    }

    @Nested
    @DisplayName("shouldIngest")
    class ShouldIngest {

        @Test
        @DisplayName("new document returns true and creates entry")
        void newDocument() {
            assertTrue(store.shouldIngest("src-1", "doc-1", "content-1"));
        }

        @Test
        @DisplayName("identical content returns false (dedup)")
        void unchangedContent() {
            assertTrue(store.shouldIngest("src-1", "doc-1", "hello"));
            assertFalse(store.shouldIngest("src-1", "doc-1", "hello"));
        }

        @Test
        @DisplayName("changed content returns true and updates hash")
        void changedContent() {
            assertTrue(store.shouldIngest("src-1", "doc-1", "v1"));
            assertTrue(store.shouldIngest("src-1", "doc-1", "v2"));
        }

        @Test
        @DisplayName("same content after change returns false again")
        void stableAfterChange() {
            assertTrue(store.shouldIngest("src-1", "doc-1", "v1"));
            assertTrue(store.shouldIngest("src-1", "doc-1", "v2"));
            assertFalse(store.shouldIngest("src-1", "doc-1", "v2"));
        }

        @Test
        @DisplayName("different sources are independent")
        void independentSources() {
            assertTrue(store.shouldIngest("src-a", "doc-1", "same"));
            assertTrue(store.shouldIngest("src-b", "doc-1", "same"));
        }

        @Test
        @DisplayName("null documentId returns false")
        void nullDocumentId() {
            assertFalse(store.shouldIngest("src-1", null, "content"));
        }

        @Test
        @DisplayName("empty documentId returns false")
        void emptyDocumentId() {
            assertFalse(store.shouldIngest("src-1", "", "content"));
        }

        @Test
        @DisplayName("null content returns false")
        void nullContent() {
            assertFalse(store.shouldIngest("src-1", "doc-1", null));
        }

        @Test
        @DisplayName("empty content returns false")
        void emptyContent() {
            assertFalse(store.shouldIngest("src-1", "doc-1", ""));
        }
    }

    @Nested
    @DisplayName("markStaleDocuments")
    class MarkStale {

        @Test
        @DisplayName("marks non-fetched documents as stale")
        void marksMissing() {
            store.shouldIngest("src-1", "keep-me", "fresh");
            store.shouldIngest("src-1", "stale-me", "old");

            int count = store.markStaleDocuments("src-1", List.of("keep-me"));

            assertEquals(1, count);
            assertFalse(store.shouldIngest("src-1", "keep-me", "fresh"));
            assertFalse(store.shouldIngest("src-1", "stale-me", "old"));
            assertTrue(store.shouldIngest("src-1", "stale-me", "new-content"));
        }

        @Test
        @DisplayName("empty documentIds list is handled gracefully")
        void emptyList() {
            store.shouldIngest("src-1", "doc-1", "content");
            int count = store.markStaleDocuments("src-1", List.of());
            assertEquals(0, count);
        }

        @Test
        @DisplayName("only affects the specified source")
        void scopedToSource() {
            store.shouldIngest("src-a", "doc-1", "v");
            store.shouldIngest("src-b", "doc-1", "v");

            assertEquals(0, store.markStaleDocuments("src-a", List.of("doc-1")));
        }
    }

    @Nested
    @DisplayName("clearSource")
    class ClearSource {

        @Test
        @DisplayName("removes all entries for the source")
        void clear() {
            store.shouldIngest("src-1", "doc-a", "content");
            store.shouldIngest("src-1", "doc-b", "content");

            store.clearSource("src-1");

            assertTrue(store.shouldIngest("src-1", "doc-a", "content"));
        }

        @Test
        @DisplayName("does not affect other sources")
        void otherSources() {
            store.shouldIngest("src-a", "doc-1", "content");
            store.shouldIngest("src-b", "doc-1", "content");

            store.clearSource("src-a");

            assertFalse(store.shouldIngest("src-b", "doc-1", "content"));
        }
    }

    @Nested
    @DisplayName("computeHash")
    class ComputeHash {

        @Test
        @DisplayName("returns deterministic SHA-256 hex string")
        void deterministic() {
            String h1 = store.computeHash("hello world");
            String h2 = store.computeHash("hello world");
            assertEquals(h1, h2);
            assertEquals(64, h1.length());
        }

        @Test
        @DisplayName("different inputs produce different hashes")
        void different() {
            assertNotEquals(
                    store.computeHash("abc"),
                    store.computeHash("xyz"));
        }

        @Test
        @DisplayName("empty string produces valid hash")
        void emptyString() {
            String hash = store.computeHash("");
            assertNotNull(hash);
            assertEquals(64, hash.length());
        }

        @Test
        @DisplayName("null input throws NullPointerException")
        void nullInput() {
            assertThrows(NullPointerException.class,
                    () -> store.computeHash(null));
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("concurrent shouldIngest with same sourceId+documentId is safe")
        void concurrentShouldIngest() throws Exception {
            int threadCount = 10;
            var results = java.util.concurrent.ConcurrentHashMap.<Boolean>newKeySet();
            var threads = new java.util.ArrayList<Thread>();

            for (int i = 0; i < threadCount; i++) {
                Thread t = new Thread(() -> {
                    boolean result = store.shouldIngest("src-1", "same-doc", "same-content");
                    results.add(result);
                });
                threads.add(t);
                t.start();
            }

            for (Thread t : threads) {
                t.join(5000);
            }

            // At least one thread should have returned true (first insertion wins)
            assertTrue(results.contains(true), "At least one concurrent caller should detect new content");
        }
    }
}
