/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import ai.labs.eddi.modules.ingestion.connector.SourceDocument.Content;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceConnectorContractTest {

    private static SourceConnector connector(String type) {
        return new SourceConnector() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public DiscoveryResult discover(DiscoveryContext context) {
                return DiscoveryResult.of(StopReason.COMPLETED, Coverage.FULL_LISTING);
            }

            @Override
            public SourceDocument fetch(WorkItem item, SourceContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Nested
    @DisplayName("SourceConnector defaults")
    class Defaults {

        @Test
        @DisplayName("a source has one implicit partition and every partition is accessible")
        void singleImplicitPartition() throws SourceException {
            SourceConnector connector = connector("x");
            assertEquals(List.of(Partition.IMPLICIT), connector.listPartitions(null));
            connector.verifyAccess(null, Partition.IMPLICIT);
        }

        @Test
        @DisplayName("a partition id may not be blank")
        void blankPartitionId() {
            assertThrows(IllegalArgumentException.class, () -> new Partition(" ", null));
        }
    }

    @Nested
    @DisplayName("SourceConnectors")
    class Registry {

        @Test
        @DisplayName("finds a connector by its type")
        void byType() {
            SourceConnector web = connector("web");
            SourceConnector upload = connector("upload");
            SourceConnectors connectors = SourceConnectors.of(web, upload);

            assertSame(web, connectors.forType("web"));
            assertSame(upload, connectors.forType("upload"));
            assertEquals(List.of("upload", "web"), connectors.types());
        }

        @Test
        @DisplayName("collects CDI beans")
        @SuppressWarnings("unchecked")
        void fromCdi() {
            Instance<SourceConnector> instance = mock(Instance.class);
            List<SourceConnector> beans = List.of(connector("a"), connector("b"));
            when(instance.iterator()).thenAnswer(invocation -> beans.iterator());
            doForEach(instance, beans);

            assertEquals(List.of("a", "b"), new SourceConnectors(instance).types());
        }

        @SuppressWarnings("unchecked")
        private void doForEach(Instance<SourceConnector> instance, List<SourceConnector> beans) {
            doAnswer(invocation -> {
                beans.forEach(invocation.getArgument(0));
                return null;
            }).when(instance).forEach(any());
        }

        @Test
        @DisplayName("refuses two connectors for one type")
        void duplicateType() {
            var e = assertThrows(IllegalStateException.class,
                    () -> SourceConnectors.of(connector("web"), connector("web")));
            assertTrue(e.getMessage().contains("'web'"));
        }

        @Test
        @DisplayName("an unknown type names the ones there are")
        void unknownType() {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> SourceConnectors.of(connector("web")).forType("googleDrive"));
            assertTrue(e.getMessage().contains("googleDrive"));
            assertTrue(e.getMessage().contains("[web]"));
            assertThrows(IllegalArgumentException.class, () -> SourceConnectors.of().forType(null));
        }
    }

    @Nested
    @DisplayName("WorkItem")
    class WorkItems {

        @Test
        @DisplayName("factories set the action and the partition")
        void factories() {
            var partition = new Partition("drive-1", "Handbook");
            var upsert = WorkItem.upsert("doc", "rev-3", partition, Map.of("name", "a.pdf"));
            assertEquals(WorkItem.Action.UPSERT, upsert.action());
            assertEquals("drive-1", upsert.partitionId());
            assertEquals("rev-3", upsert.versionToken());
            assertEquals("a.pdf", upsert.hints().get("name"));
            assertNull(upsert.prefetched());

            assertEquals(WorkItem.Action.UNCHANGED, WorkItem.unchanged("doc", partition).action());
            assertEquals(WorkItem.Action.DELETE, WorkItem.delete("doc", partition).action());
        }

        @Test
        @DisplayName("a prefetched item carries its document and that document's version")
        void prefetched() {
            var document = new SourceDocument("doc", new Content.Text("hello"), "v1", null, false, Map.of());
            var item = WorkItem.prefetched(document, Partition.IMPLICIT);
            assertSame(document, item.prefetched());
            assertEquals("v1", item.versionToken());
            assertEquals(WorkItem.Action.UPSERT, item.action());
        }

        @Test
        @DisplayName("only an UPSERT for the same id may carry a document")
        void prefetchedMustMatch() {
            var document = new SourceDocument("doc", new Content.Text("hello"), null, null, false, Map.of());
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkItem("doc", null, WorkItem.Action.DELETE, "default", Map.of(), document));
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkItem("other", null, WorkItem.Action.UPSERT, "default", Map.of(), document));
        }

        @Test
        @DisplayName("hints are copied and never null")
        void hintsCopied() {
            Map<String, String> hints = new HashMap<>(Map.of("k", "v"));
            var item = WorkItem.upsert("doc", null, Partition.IMPLICIT, hints);
            hints.put("k", "changed");
            assertEquals("v", item.hints().get("k"));
            assertTrue(WorkItem.upsert("doc", null, Partition.IMPLICIT, null).hints().isEmpty());
        }
    }

    @Nested
    @DisplayName("SourceDocument")
    class Documents {

        @Test
        @DisplayName("reads title, citation and display name from the standard keys and drops null values")
        void standardKeys() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(SourceMetadataKeys.TITLE, "Handbook");
            metadata.put(SourceMetadataKeys.CITATION_URL, "file:handbook.pdf");
            metadata.put(SourceMetadataKeys.PATH, "handbook.pdf");
            metadata.put(SourceMetadataKeys.AUTHOR, null);
            var document = new SourceDocument("id-1", new Content.Text("x"), null, null, false, metadata);

            assertEquals("Handbook", document.title());
            assertEquals("file:handbook.pdf", document.citationUrl());
            assertEquals("handbook.pdf", document.displayName());
            assertFalse(document.metadata().containsKey(SourceMetadataKeys.AUTHOR));
            assertThrows(UnsupportedOperationException.class, () -> document.metadata().put("k", "v"));
        }

        @Test
        @DisplayName("without a path the display name is the id")
        void displayNameFallsBack() {
            var document = new SourceDocument("id-1", new Content.Text("x"), null, null, false, null);
            assertEquals("id-1", document.displayName());
            assertNull(document.title());
        }

        @Test
        @DisplayName("file content compares by its bytes")
        void fileBytesEquality() {
            byte[] bytes = {1, 2, 3};
            var a = new Content.FileBytes(bytes, "application/pdf");
            var b = new Content.FileBytes(Arrays.copyOf(bytes, 3), "application/pdf");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertArrayEquals(bytes, a.bytes());
            assertTrue(a.toString().contains("3 bytes"));
        }

        @Test
        @DisplayName("the citation key is the one chunks already carry")
        void citationKeyMatchesStoredChunks() {
            assertEquals("url", SourceMetadataKeys.CITATION_URL);
            assertEquals("documentId", SourceMetadataKeys.DOCUMENT_ID);
            assertEquals("sourceKey", SourceMetadataKeys.SOURCE_KEY);
            assertEquals("title", SourceMetadataKeys.TITLE);
            assertTrue(SourceMetadataKeys.ALL.contains(SourceMetadataKeys.ACL_PRINCIPALS));
        }
    }

    @Nested
    @DisplayName("SourceError")
    class Errors {

        @Test
        @DisplayName("only NOT_FOUND and PERMANENT are answers about the document")
        void definitive() {
            assertTrue(new SourceError.NotFound("gone").isDefinitive());
            assertTrue(new SourceError.Permanent("bad").isDefinitive());
            assertFalse(new SourceError.Transient("503").isDefinitive());
            assertFalse(new SourceError.Throttled("429").isDefinitive());
            assertFalse(new SourceError.Auth("401").isDefinitive());
            // A request refused as made is a fault in the request, not an answer
            // about the document: counting it would let a broken connector delete.
            assertFalse(new SourceError.Rejected("400 invalid field mask").isDefinitive());
            assertFalse(new SourceError.CursorInvalid("stale").isDefinitive());
        }

        @Test
        @DisplayName("each variant reports its kind, and retry hints survive")
        void kindsAndRetry() {
            assertEquals(SourceError.Kind.TRANSIENT, new SourceError.Transient("x").kind());
            assertEquals(SourceError.Kind.THROTTLED, new SourceError.Throttled("x").kind());
            assertEquals(SourceError.Kind.AUTH, new SourceError.Auth("x").kind());
            assertEquals(SourceError.Kind.NOT_FOUND, new SourceError.NotFound("x").kind());
            assertEquals(SourceError.Kind.PERMANENT, new SourceError.Permanent("x").kind());
            assertEquals(SourceError.Kind.REJECTED, new SourceError.Rejected("x").kind());
            assertEquals(SourceError.Kind.CURSOR_INVALID, new SourceError.CursorInvalid("x").kind());

            assertEquals(Duration.ofSeconds(30),
                    new SourceError.Throttled("x", Duration.ofSeconds(30)).retryAfter().orElseThrow());
            assertEquals(Duration.ofSeconds(5),
                    new SourceError.Transient("x", Duration.ofSeconds(5)).retryAfter().orElseThrow());
            assertTrue(new SourceError.Auth("x").retryAfter().isEmpty());
        }

        @Test
        @DisplayName("an exception carries its classification and reason")
        void exception() {
            var error = new SourceError.Auth("grant revoked");
            var e = new SourceException(error);
            assertSame(error, e.error());
            assertEquals("grant revoked", e.getMessage());
        }
    }

    @Test
    @DisplayName("stop reasons have the names run history stores")
    void stopReasonParity() {
        // Run history stores WebCrawler.StopReason by name, and the pipeline maps a
        // connector's stop reason onto it by name: the two must not drift.
        assertEquals(Arrays.stream(WebCrawler.StopReason.values()).map(Enum::name).toList(),
                Arrays.stream(StopReason.values()).map(Enum::name).toList());
    }
}
