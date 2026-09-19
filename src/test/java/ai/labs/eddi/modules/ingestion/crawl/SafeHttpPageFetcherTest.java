/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.modules.ingestion.crawl.PageFetcher.FetchCommand;
import ai.labs.eddi.modules.ingestion.crawl.PageFetcher.FetchedPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private SafeHttpClient httpClient;
    private SafeHttpPageFetcher fetcher;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        httpClient = mock(SafeHttpClient.class);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        IRuntime runtime = mock(IRuntime.class);
        when(runtime.getScheduledExecutorService()).thenReturn(scheduler);
        fetcher = new SafeHttpPageFetcher(httpClient, runtime);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static FetchCommand command(long maxBytes) {
        return new FetchCommand("https://example.com/a", "EDDI-Crawler/1.0", Duration.ofSeconds(5),
                null, null, maxBytes);
    }

    @SuppressWarnings("unchecked")
    private void respond(int statusCode, Map<String, List<String>> headers, byte[] body) throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.uri()).thenReturn(URI.create("https://example.com/a"));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (k, v) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        when(httpClient.sendValidated(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    private HttpRequest capturedRequest() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).sendValidated(captor.capture(), any());
        return captor.getValue();
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

        fetcher.fetch(new FetchCommand("https://example.com/a", "EDDI-Crawler/1.0", Duration.ofSeconds(5),
                "\"v1\"", "Wed, 21 Oct 2026 07:28:00 GMT", 1024));

        HttpRequest request = capturedRequest();
        assertEquals(Optional.of("\"v1\""), request.headers().firstValue("If-None-Match"));
        assertEquals(Optional.of("Wed, 21 Oct 2026 07:28:00 GMT"), request.headers().firstValue("If-Modified-Since"));
    }

    @Test
    @DisplayName("omits conditional headers when there are no validators")
    void omitsConditionalHeaders() throws Exception {
        respond(200, Map.of("Content-Type", List.of("text/html")), new byte[]{1});

        fetcher.fetch(command(1024));

        HttpRequest request = capturedRequest();
        assertTrue(request.headers().firstValue("If-None-Match").isEmpty());
        assertTrue(request.headers().firstValue("If-Modified-Since").isEmpty());
    }

    @Test
    @DisplayName("identifies the crawler and carries the caller's timeout")
    void setsUserAgentAndTimeout() throws Exception {
        respond(200, Map.of("Content-Type", List.of("text/html")), new byte[]{1});

        fetcher.fetch(command(1024));

        HttpRequest request = capturedRequest();
        assertEquals(Optional.of("EDDI-Crawler/1.0"), request.headers().firstValue("User-Agent"));
        assertEquals(Optional.of(Duration.ofSeconds(5)), request.timeout());
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
        when(httpClient.sendValidated(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection reset"));

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
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.uri()).thenReturn(URI.create("https://example.com/a"));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/html")),
                (k, v) -> true));
        when(response.body()).thenReturn(new TrickleStream());
        when(httpClient.sendValidated(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        long start = System.nanoTime();
        FetchedPage page = fetcher.fetch(new FetchCommand("https://example.com/a", "EDDI-Crawler/1.0",
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
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.uri()).thenReturn(URI.create("https://example.com/a"));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/html")),
                (k, v) -> true));
        // Closed on every exit, so a failing run does not leave a reader blocked.
        try (StallingStream stalling = new StallingStream("<p>partial")) {
            when(response.body()).thenReturn(stalling);
            when(httpClient.sendValidated(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(response);

            FetchedPage page = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> fetcher.fetch(new FetchCommand("https://example.com/a", "EDDI-Crawler/1.0",
                            Duration.ofMillis(50), null, null, 10_000_000)));

            assertTrue(page.truncated(), "a stalled body must come back truncated");
            assertEquals("<p>partial", new String(page.body(), StandardCharsets.UTF_8),
                    "what arrived before the stall is kept");
        }
    }

    @Test
    @DisplayName("a failure before the deadline still propagates")
    void readFailureBeforeTheDeadlinePropagates() throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.uri()).thenReturn(URI.create("https://example.com/a"));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
        when(response.body()).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        });
        when(httpClient.sendValidated(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

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
