/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.Hop;
import ai.labs.eddi.engine.httpclient.outbound.ScriptedEngine;
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import ai.labs.eddi.modules.ingestion.crawl.PageFetcher.FetchCommand;
import ai.labs.eddi.modules.ingestion.crawl.PageFetcher.FetchedPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SafeHttpPageFetcher} — the production fetcher.
 *
 * <p>
 * The body cap is the interesting part: the draft read the whole response into
 * a String <em>before</em> looking at its Content-Type, so a documentation page
 * linking a 1 GB installer downloaded the installer in full and then discarded
 * it.
 */
class SafeHttpPageFetcherTest {

    /**
     * A public IP literal: every fetch is SSRF-checked, and a hostname would make
     * the test depend on DNS.
     */
    private static final String URL = "https://93.184.215.14/a";

    private ScriptedEngine engine;
    private SafeHttpPageFetcher fetcher;

    @BeforeEach
    void setUp() {
        engine = new ScriptedEngine();
        // A real facade over a scripted engine: the cap, the read budget and the
        // truncation flag are the facade's, so they are exercised, not mocked.
        fetcher = new SafeHttpPageFetcher(TestOutbound.facade(engine));
    }

    private static FetchCommand command(long maxBytes) {
        return new FetchCommand(URL, "EDDI-Crawler/1.0", Duration.ofSeconds(5), null, null, maxBytes);
    }

    private void respond(int statusCode, Map<String, List<String>> headers, byte[] body) {
        engine.respond(statusCode, headers, body);
    }

    private void respond(int statusCode, Map<String, List<String>> headers, InputStream body) {
        engine.respond(statusCode, headers, body);
    }

    private Hop capturedRequest() {
        return engine.lastHop();
    }

    private static String header(Hop hop, String name) {
        List<String> values = hop.headers().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    @Test
    @DisplayName("reads a body that fits under the cap in full")
    void readsWholeBodyUnderCap() throws Exception {
        byte[] body = "<html><body>hello</body></html>".getBytes(StandardCharsets.UTF_8);
        respond(200, Map.of("Content-Type", List.of("text/html")), body);

        FetchedPage page = fetcher.fetch(command(1024));

        assertArrayEquals(body, page.body());
        assertFalse(page.truncated());
        assertTrue(page.isOk());
    }

    @Test
    @DisplayName("stops reading at the cap and says the body was truncated")
    void stopsAtCap() throws Exception {
        byte[] body = new byte[100_000];
        respond(200, Map.of("Content-Type", List.of("text/html")), body);

        FetchedPage page = fetcher.fetch(command(1024));

        assertEquals(1024, page.body().length, "must not read past the cap");
        assertTrue(page.truncated(), "the caller has to know the content is incomplete");
    }

    @Test
    @DisplayName("a cap of zero means no cap, not an empty read")
    void zeroCapMeansUnbounded() throws Exception {
        byte[] body = new byte[5000];
        respond(200, Map.of("Content-Type", List.of("text/html")), body);

        FetchedPage page = fetcher.fetch(command(0));

        assertEquals(5000, page.body().length);
        assertFalse(page.truncated());
    }

    @Test
    @DisplayName("reads the charset from the Content-Type header")
    void readsDeclaredCharset() throws Exception {
        respond(200, Map.of("Content-Type", List.of("text/html; charset=ISO-8859-1")), new byte[]{1});

        assertEquals("iso-8859-1", fetcher.fetch(command(1024)).declaredCharset());
    }

    @Test
    @DisplayName("leaves the charset unset when the header does not declare one, so the parser can sniff")
    void noCharsetWhenUndeclared() throws Exception {
        respond(200, Map.of("Content-Type", List.of("text/html")), new byte[]{1});

        assertNull(fetcher.fetch(command(1024)).declaredCharset(),
                "null lets jsoup read the document's own <meta charset>");
    }

    @Test
    @DisplayName("tolerates a quoted charset")
    void tolerantCharsetParsing() throws Exception {
        respond(200, Map.of("Content-Type", List.of("text/html; charset=\"UTF-8\"")), new byte[]{1});

        assertEquals("utf-8", fetcher.fetch(command(1024)).declaredCharset());
    }

    @Test
    @DisplayName("a charset the JVM does not know is left unset so the parser can sniff")
    void unsupportedCharsetIsIgnored() throws Exception {
        // utf8mb4 is MySQL's name, and real sites send it. Passing it to the parser
        // throws, which loses the page — and a lost page counts as missing, so two
        // runs later it is deleted from the knowledge base.
        respond(200, Map.of("Content-Type", List.of("text/html; charset=utf8mb4")), "<html>hi</html>".getBytes());

        FetchedPage page = fetcher.fetch(command(0));

        assertNull(page.declaredCharset(), "an unusable charset must be dropped, not forwarded");
    }

    @Test
    @DisplayName("a malformed charset from a broken proxy is dropped too")
    void malformedCharsetIsIgnored() throws Exception {
        respond(200, Map.of("Content-Type", List.of("text/html; charset=utf-8, utf-8")), "<html>hi</html>".getBytes());

        FetchedPage page = fetcher.fetch(command(0));

        assertNull(page.declaredCharset());
    }

    @Test
    @DisplayName("the seed is SSRF-checked: a loopback URL is refused before anything is sent")
    void loopbackSeedIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(new FetchCommand("http://127.0.0.1:8080/admin",
                "EDDI-Crawler/1.0", Duration.ofSeconds(5), null, null, 1024)));
        assertTrue(engine.hops.isEmpty());
    }

    @Test
    @DisplayName("a 304 comes back as not-modified with no body")
    void notModified() throws Exception {
        respond(304, Map.of("ETag", List.of("\"v1\"")), new byte[0]);

        FetchedPage page = fetcher.fetch(command(1024));

        assertTrue(page.isNotModified());
        assertFalse(page.isOk());
        assertEquals(0, page.body().length);
        assertEquals("\"v1\"", page.etag());
    }

    @Test
    @DisplayName("sends conditional headers when the caller supplies validators")
    void sendsConditionalHeaders() throws Exception {
        respond(200, Map.of("Content-Type", List.of("text/html")), new byte[]{1});

        fetcher.fetch(new FetchCommand(URL, "EDDI-Crawler/1.0", Duration.ofSeconds(5),
                "\"v1\"", "Wed, 21 Oct 2026 07:28:00 GMT", 1024));

        Hop request = capturedRequest();
        assertEquals("\"v1\"", header(request, "If-None-Match"));
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", header(request, "If-Modified-Since"));
    }

    @Test
    @DisplayName("omits conditional headers when there are no validators")
    void omitsConditionalHeaders() throws Exception {
        respond(200, Map.of("Content-Type", List.of("text/html")), new byte[]{1});

        fetcher.fetch(command(1024));

        Hop request = capturedRequest();
        assertNull(header(request, "If-None-Match"));
        assertNull(header(request, "If-Modified-Since"));
    }

    @Test
    @DisplayName("identifies the crawler and carries the caller's timeout")
    void setsUserAgentAndTimeout() throws Exception {
        respond(200, Map.of("Content-Type", List.of("text/html")), new byte[]{1});

        fetcher.fetch(command(1024));

        Hop request = capturedRequest();
        assertEquals("EDDI-Crawler/1.0", header(request, "User-Agent"));
        assertEquals(Duration.ofSeconds(5), request.hopTimeout());
        assertEquals("GET", request.method());
    }

    @Test
    @DisplayName("reports validators from the response for the next run")
    void reportsValidators() throws Exception {
        respond(200, Map.of(
                "Content-Type", List.of("text/html"),
                "ETag", List.of("\"v9\""),
                "Last-Modified", List.of("Wed, 21 Oct 2026 07:28:00 GMT")), new byte[]{1});

        FetchedPage page = fetcher.fetch(command(1024));

        assertEquals("\"v9\"", page.etag());
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", page.lastModified());
    }

    @Test
    @DisplayName("a transport failure propagates rather than being swallowed")
    void transportFailurePropagates() throws Exception {
        engine.fail(new IOException("connection reset"));

        IOException thrown = assertThrows(IOException.class,
                () -> fetcher.fetch(command(1024)));
        assertEquals("connection reset", thrown.getMessage());
    }

    @Test
    @DisplayName("a body that trickles forever is cut off instead of holding the thread")
    void slowBodyHitsTheReadDeadline() throws Exception {
        // HttpRequest.timeout covers the response HEADERS only. Without a deadline on
        // the body, a server sending one byte per second holds this thread for weeks,
        // and the crawl's own budget is only checked between pages.
        respond(200, Map.of("Content-Type", List.of("text/html")), new TrickleStream());

        long start = System.nanoTime();
        FetchedPage page = fetcher.fetch(new FetchCommand(URL, "EDDI-Crawler/1.0",
                Duration.ofMillis(50), null, null, 10_000_000));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(page.truncated(), "the read must give up rather than run forever");
        assertTrue(elapsedMillis < 5_000, "gave up after " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("a body that stalls completely is cut off at the deadline")
    void stalledBodyHitsTheReadDeadline() throws Exception {
        // The trickle case above returns from read() often enough for the loop to
        // check its deadline. A server that sends headers and then nothing never
        // returns from read(), so only closing the stream from outside ends it.
        // Closed on every exit, so a failing run does not leave a reader blocked.
        try (StallingStream stalling = new StallingStream("<p>partial")) {
            respond(200, Map.of("Content-Type", List.of("text/html")), stalling);

            FetchedPage page = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> fetcher.fetch(new FetchCommand(URL, "EDDI-Crawler/1.0",
                            Duration.ofMillis(50), null, null, 10_000_000)));

            assertTrue(page.truncated(), "a stalled body must come back truncated");
            assertEquals("<p>partial", new String(page.body(), StandardCharsets.UTF_8),
                    "what arrived before the stall is kept");
        }
    }

    @Test
    @DisplayName("a failure before the deadline still propagates")
    void readFailureBeforeTheDeadlinePropagates() throws Exception {
        respond(200, Map.of(), new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        });

        assertThrows(IOException.class, () -> fetcher.fetch(command(0)));
    }

    /** Sends a prefix, then blocks until closed — a server that stopped talking. */
    private static final class StallingStream extends InputStream {
        private final byte[] prefix;
        private final CountDownLatch closed = new CountDownLatch(1);
        private boolean prefixSent;

        StallingStream(String prefix) {
            this.prefix = prefix.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0];
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (!prefixSent) {
                prefixSent = true;
                System.arraycopy(prefix, 0, buffer, offset, prefix.length);
                return prefix.length;
            }
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            // What the JDK's response stream does when closed under a blocked read.
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    /** Never ends, and never blocks long enough to look like a network failure. */
    private static final class TrickleStream extends InputStream {
        @Override
        public int read() {
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            buffer[offset] = 'x';
            return 1;
        }
    }

    @Test
    @DisplayName("content-type classification decides what is worth parsing")
    void contentTypeClassification() {
        assertTrue(page("text/html").isHtml());
        assertTrue(page("application/xhtml+xml").isHtml());
        assertTrue(page("text/html; charset=utf-8").isHtml());
        assertFalse(page("image/png").isHtml());
        assertFalse(page("application/pdf").isHtml());
        assertFalse(page("application/zip").isHtml());
        assertTrue(page(null).isHtml(), "a missing Content-Type should not lose the page");
        assertTrue(page("").isHtml());
    }

    private static FetchedPage page(String contentType) {
        return new FetchedPage(200, "https://example.com/a", contentType, null, new byte[0], null, null, false);
    }
}
