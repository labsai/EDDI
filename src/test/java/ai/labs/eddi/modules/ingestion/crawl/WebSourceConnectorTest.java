/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.modules.ingestion.connector.Coverage;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryContext;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryResult;
import ai.labs.eddi.modules.ingestion.connector.KnownVersion;
import ai.labs.eddi.modules.ingestion.connector.Partition;
import ai.labs.eddi.modules.ingestion.connector.SourceContext;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument.Content;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.connector.SourceMetadataKeys;
import ai.labs.eddi.modules.ingestion.connector.StopReason;
import ai.labs.eddi.modules.ingestion.connector.WorkItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSourceConnectorTest {

    private static final String SITE = "https://example.com";

    private static IngestionSource source() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl(SITE + "/");
        web.setRespectRobots(false);
        web.setRequestDelayMs(0);
        var source = new IngestionSource();
        source.setId("src-1");
        source.setName("docs");
        source.setWeb(web);
        return source;
    }

    private static String page(String title, String body) {
        return "<html><head><title>" + title + "</title></head><body><main><p>" + body + "</p>"
                + "</main></body></html>";
    }

    /** Records what the connector hands over. */
    private static class Recording implements DiscoveryContext {
        final List<WorkItem> items = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final List<SourceError> errors = new ArrayList<>();
        final Map<String, KnownVersion> known = new HashMap<>();
        boolean cancelled;

        @Override
        public SourceContext source() {
            return new SourceContext(WebSourceConnectorTest.source(), "kb:src-1", "run-1", false);
        }

        @Override
        public Partition partition() {
            return Partition.IMPLICIT;
        }

        @Override
        public Optional<String> cursor() {
            return Optional.empty();
        }

        @Override
        public Instant deadline() {
            return Instant.MAX;
        }

        @Override
        public Optional<KnownVersion> knownVersion(String documentId) {
            return Optional.ofNullable(known.get(documentId));
        }

        @Override
        public void accept(WorkItem item) {
            items.add(item);
        }

        @Override
        public void reportFailure(String documentId, String location, SourceError error) {
            failures.add(documentId);
            errors.add(error);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    @Test
    @DisplayName("serves the web type")
    void type() {
        assertEquals(IngestionSource.TYPE_WEB, new WebSourceConnector(new WebCrawler(new FakeSite())).type());
    }

    @Test
    @DisplayName("every crawled page arrives prefetched, as an HTML page with its validators")
    void pagesArrivePrefetched() {
        var site = new FakeSite().pageWithValidators(SITE + "/", page("Docs", "Hello"), "\"v1\"", "Mon, 01 Jan 2024");
        var context = new Recording();

        DiscoveryResult result = new WebSourceConnector(new WebCrawler(site)).discover(context);

        assertEquals(1, context.items.size());
        WorkItem item = context.items.get(0);
        assertEquals(WorkItem.Action.UPSERT, item.action());
        assertEquals(Partition.IMPLICIT.id(), item.partitionId());
        assertNull(item.versionToken(), "an ETag is not a content version; the text hash decides");
        var document = item.prefetched();
        assertInstanceOf(Content.HtmlPage.class, document.content());
        assertEquals("Docs", document.title());
        assertEquals(SITE + "/", document.citationUrl());
        assertEquals("\"v1\"", document.validators().etag());
        assertEquals("Mon, 01 Jan 2024", document.validators().lastModified());
        assertEquals("web", document.metadata().get(SourceMetadataKeys.PROVIDER));

        assertEquals(StopReason.COMPLETED, result.stopReason());
        assertEquals(Coverage.FULL_TRAVERSAL, result.coverage());
        assertNull(result.nextCursor());
    }

    @Test
    @DisplayName("a known version becomes a conditional request, and a 304 an UNCHANGED item")
    void conditionalRequests() {
        // A leaf: the seed is never revalidated, because its links are needed.
        var site = new FakeSite()
                .page(SITE + "/", "<html><body><main><a href=\"" + SITE + "/a\">a</a></main></body></html>")
                .conditional(SITE + "/a", page("A", "Hello"), "\"v1\"");
        var context = new Recording() {
            @Override
            public SourceContext source() {
                var source = WebSourceConnectorTest.source();
                source.getWeb().setMaxDepth(1);
                return new SourceContext(source, "kb:src-1", "run-1", false);
            }
        };
        context.known.put(SITE + "/a", new KnownVersion("hash", "\"v1\"", null));

        new WebSourceConnector(new WebCrawler(site)).discover(context);

        WorkItem leaf = context.items.stream().filter(item -> item.documentId().equals(SITE + "/a"))
                .findFirst().orElseThrow();
        assertEquals(WorkItem.Action.UNCHANGED, leaf.action());
        assertNull(leaf.prefetched());
    }

    @Test
    @DisplayName("a crawl that reached nothing is partial coverage")
    void nothingReachedIsPartial() {
        var site = new FakeSite().status(SITE + "/", 503);
        var context = new Recording();

        DiscoveryResult result = new WebSourceConnector(new WebCrawler(site)).discover(context);

        assertEquals(Coverage.PARTIAL, result.coverage());
        assertEquals(1, context.errors.size());
        assertFalse(context.errors.get(0).isDefinitive());
    }

    @Test
    @DisplayName("a cancelled context stops the crawl")
    void cancellation() {
        var site = new FakeSite().page(SITE + "/", page("Docs", "Hello"));
        var context = new Recording();
        context.cancelled = true;

        DiscoveryResult result = new WebSourceConnector(new WebCrawler(site)).discover(context);

        assertEquals(StopReason.CANCELLED, result.stopReason());
        assertEquals(Coverage.PARTIAL, result.coverage());
        assertTrue(context.items.isEmpty());
    }

    @Test
    @DisplayName("fetch is never meant to be called")
    void fetchRefuses() {
        var connector = new WebSourceConnector(new WebCrawler(new FakeSite()));
        var item = WorkItem.upsert(SITE + "/", null, Partition.IMPLICIT, Map.of());
        var e = assertThrows(SourceException.class, () -> connector.fetch(item, new Recording().source()));
        // Rejected, not Permanent: a misdirected call says nothing about the page, so
        // it must never count as an answer that removes the page's chunks.
        assertInstanceOf(SourceError.Rejected.class, e.error());
        assertFalse(e.error().isDefinitive());
    }

    @Nested
    @DisplayName("error classification keeps the crawler's verdict")
    class Classification {

        @ParameterizedTest
        @ValueSource(ints = {404, 410})
        @DisplayName("gone is NOT_FOUND")
        void gone(int status) {
            var error = WebSourceConnector.classify(new CrawlSink.CrawlError("id", "u", "HTTP " + status, status, false));
            assertInstanceOf(SourceError.NotFound.class, error);
            assertTrue(error.isDefinitive());
        }

        @Test
        @DisplayName("another definitive status is PERMANENT")
        void otherDefinitive() {
            var error = WebSourceConnector.classify(new CrawlSink.CrawlError("id", "u", "HTTP 400", 400, false));
            assertInstanceOf(SourceError.Permanent.class, error);
            assertTrue(error.isDefinitive());
        }

        @Test
        @DisplayName("429 is THROTTLED, 401 and 403 are AUTH, the rest TRANSIENT — none definitive")
        void unknownContent() {
            assertInstanceOf(SourceError.Throttled.class,
                    WebSourceConnector.classify(new CrawlSink.CrawlError("id", "u", "HTTP 429", 429, true)));
            assertInstanceOf(SourceError.Auth.class,
                    WebSourceConnector.classify(new CrawlSink.CrawlError("id", "u", "HTTP 401", 401, true)));
            assertInstanceOf(SourceError.Auth.class,
                    WebSourceConnector.classify(new CrawlSink.CrawlError("id", "u", "HTTP 403", 403, true)));
            for (int status : new int[]{0, 408, 500, 503, 200}) {
                var error = WebSourceConnector.classify(new CrawlSink.CrawlError("id", "u", "x", status, true));
                assertInstanceOf(SourceError.Transient.class, error);
                assertFalse(error.isDefinitive());
            }
        }

        @Test
        @DisplayName("isDefinitive is exactly the crawler's 'content known'")
        void parity() {
            for (int status = 0; status < 600; status++) {
                for (boolean contentUnknown : new boolean[]{true, false}) {
                    var error = WebSourceConnector.classify(
                            new CrawlSink.CrawlError("id", "u", "x", status, contentUnknown));
                    assertEquals(!contentUnknown, error.isDefinitive(), "status " + status);
                }
            }
        }
    }

    @Test
    @DisplayName("the crawl request carries the web block's settings and defaults")
    void crawlRequest() {
        var source = source();
        source.getWeb().setMaxPages(7);
        CrawlRequest request = WebSourceConnector.toCrawlRequest(source);
        assertEquals(SITE + "/", request.seedUrl());
        assertEquals(7, request.limits().maxPages());
        assertEquals(3, request.scope().maxDepth());
    }
}
