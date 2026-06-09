/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.modules.ingestion.MongoContentHashStore;
import org.bson.Document;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MongoContentHashStore} using Testcontainers.
 *
 * @since 6.0.3
 */
@DisplayName("MongoContentHashStore IT")
class MongoContentHashStoreTest extends MongoTestBase {

    private static MongoContentHashStore tracker;

    @BeforeAll
    static void init() {
        tracker = new MongoContentHashStore(getDatabase());
    }

    @BeforeEach
    void clean() {
        getDatabase().getCollection("rag_ingestion_hashes").deleteMany(new Document());
    }

    @Nested
    @DisplayName("shouldIngest")
    class ShouldIngest {

        @Test
        @DisplayName("new document returns true and creates entry")
        void newDocument() {
            assertTrue(tracker.shouldIngest("src-1", "doc-1", "content-1"));
        }

        @Test
        @DisplayName("identical content returns false (dedup)")
        void unchangedContent() {
            assertTrue(tracker.shouldIngest("src-1", "doc-1", "hello"));
            assertFalse(tracker.shouldIngest("src-1", "doc-1", "hello"));
        }

        @Test
        @DisplayName("changed content returns true and updates hash")
        void changedContent() {
            assertTrue(tracker.shouldIngest("src-1", "doc-1", "v1"));
            assertTrue(tracker.shouldIngest("src-1", "doc-1", "v2"));
        }

        @Test
        @DisplayName("same content after change returns false again")
        void stableAfterChange() {
            assertTrue(tracker.shouldIngest("src-1", "doc-1", "v1"));
            assertTrue(tracker.shouldIngest("src-1", "doc-1", "v2"));
            assertFalse(tracker.shouldIngest("src-1", "doc-1", "v2"));
        }

        @Test
        @DisplayName("different sources are independent")
        void independentSources() {
            assertTrue(tracker.shouldIngest("src-a", "doc-1", "same"));
            assertTrue(tracker.shouldIngest("src-b", "doc-1", "same"));
        }

        @Test
        @DisplayName("null documentId returns false")
        void nullDocumentId() {
            assertFalse(tracker.shouldIngest("src-1", null, "content"));
        }

        @Test
        @DisplayName("empty documentId returns false")
        void emptyDocumentId() {
            assertFalse(tracker.shouldIngest("src-1", "", "content"));
        }

        @Test
        @DisplayName("null content returns false")
        void nullContent() {
            assertFalse(tracker.shouldIngest("src-1", "doc-1", null));
        }

        @Test
        @DisplayName("empty content returns false")
        void emptyContent() {
            assertFalse(tracker.shouldIngest("src-1", "doc-1", ""));
        }
    }

    @Nested
    @DisplayName("markStaleDocuments")
    class MarkStale {

        @Test
        @DisplayName("marks non-fetched documents as stale")
        void marksMissing() {
            tracker.shouldIngest("src-1", "keep-me", "fresh");
            tracker.shouldIngest("src-1", "stale-me", "old");

            int count = tracker.markStaleDocuments("src-1", List.of("keep-me"));

            assertEquals(1, count);
            // Kept doc still returns false (unchanged hash)
            assertFalse(tracker.shouldIngest("src-1", "keep-me", "fresh"));
            // Stale doc with same content returns false (unchanged hash, stale flag
            // cleared)
            assertFalse(tracker.shouldIngest("src-1", "stale-me", "old"));
            // Stale doc with changed content → true (new hash)
            assertTrue(tracker.shouldIngest("src-1", "stale-me", "new-content"));
        }

        @Test
        @DisplayName("empty documentIds list is handled gracefully")
        void emptyList() {
            tracker.shouldIngest("src-1", "doc-1", "content");
            int count = tracker.markStaleDocuments("src-1", List.of());
            assertEquals(0, count);
        }

        @Test
        @DisplayName("only affects the specified source")
        void scopedToSource() {
            tracker.shouldIngest("src-a", "doc-1", "v");
            tracker.shouldIngest("src-b", "doc-1", "v");

            assertEquals(0, tracker.markStaleDocuments("src-a", List.of("doc-1")));
        }
    }

    @Nested
    @DisplayName("clearSource")
    class ClearSource {

        @Test
        @DisplayName("removes all entries for the source")
        void clear() {
            tracker.shouldIngest("src-1", "doc-a", "content");
            tracker.shouldIngest("src-1", "doc-b", "content");

            tracker.clearSource("src-1");

            assertTrue(tracker.shouldIngest("src-1", "doc-a", "content"));
        }

        @Test
        @DisplayName("does not affect other sources")
        void otherSources() {
            tracker.shouldIngest("src-a", "doc-1", "content");
            tracker.shouldIngest("src-b", "doc-1", "content");

            tracker.clearSource("src-a");

            assertFalse(tracker.shouldIngest("src-b", "doc-1", "content"));
        }
    }

    @Nested
    @DisplayName("computeHash")
    class ComputeHash {

        @Test
        @DisplayName("returns deterministic SHA-256 hex string")
        void deterministic() {
            String h1 = tracker.computeHash("hello world");
            String h2 = tracker.computeHash("hello world");
            assertEquals(h1, h2);
            assertEquals(64, h1.length());
        }

        @Test
        @DisplayName("different inputs produce different hashes")
        void different() {
            assertNotEquals(
                    tracker.computeHash("abc"),
                    tracker.computeHash("xyz"));
        }

        @Test
        @DisplayName("empty string produces valid hash")
        void emptyString() {
            String hash = tracker.computeHash("");
            assertNotNull(hash);
            assertEquals(64, hash.length());
        }

        @Test
        @DisplayName("null input throws NullPointerException")
        void nullInput() {
            assertThrows(NullPointerException.class,
                    () -> tracker.computeHash(null));
        }
    }
}
