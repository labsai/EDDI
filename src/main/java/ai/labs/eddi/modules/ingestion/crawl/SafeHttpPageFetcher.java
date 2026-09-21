/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import ai.labs.eddi.engine.runtime.IRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The production {@link PageFetcher}: {@link SafeHttpClient} with a bounded
 * body read.
 *
 * <p>
 * Every request goes through {@code sendValidated}, so the seed and each
 * redirect hop are checked against the SSRF rules. The crawler follows links
 * harvested from third-party pages, which is as user-controlled as a URL gets —
 * so validation happens here, per request, rather than once on the seed.
 *
 * <p>
 * The body is read through a stream with a hard cap instead of
 * {@code BodyHandlers.ofString()}. The draft read the entire response into a
 * String <em>before</em> checking its Content-Type, so a documentation page
 * linking a 1 GB installer downloaded the installer in full and then threw it
 * away. A cap also removes the tarpit: a server that streams forever cannot
 * exhaust the heap.
 */
@ApplicationScoped
public class SafeHttpPageFetcher implements PageFetcher {

    private static final String HEADER_USER_AGENT = "User-Agent";
    private static final String HEADER_ACCEPT = "Accept";
    /** Body reads get this multiple of the request timeout — see readBounded. */
    private static final int BODY_READ_TIMEOUT_FACTOR = 4;

    private static final String ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    private final SafeHttpClient httpClient;
    private final IRuntime runtime;

    @Inject
    public SafeHttpPageFetcher(SafeHttpClient httpClient, IRuntime runtime) {
        this.httpClient = httpClient;
        this.runtime = runtime;
    }

    @Override
    public FetchedPage fetch(FetchCommand command) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(command.url()))
                .timeout(command.timeout())
                .header(HEADER_USER_AGENT, command.userAgent())
                .header(HEADER_ACCEPT, ACCEPT_HTML)
                .GET();

        // Conditional headers turn an unchanged page into a 304 with no body: the
        // difference between re-downloading a whole site nightly and asking it what
        // changed.
        if (command.ifNoneMatch() != null && !command.ifNoneMatch().isBlank()) {
            builder.header("If-None-Match", command.ifNoneMatch());
        }
        if (command.ifModifiedSince() != null && !command.ifModifiedSince().isBlank()) {
            builder.header("If-Modified-Since", command.ifModifiedSince());
        }

        HttpResponse<InputStream> response = httpClient.sendValidated(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

        String contentType = header(response, "Content-Type");
        String etag = header(response, "ETag");
        String lastModified = header(response, "Last-Modified");
        String finalUrl = response.uri() != null ? response.uri().toString() : command.url();

        if (response.statusCode() == 304) {
            closeQuietly(response.body());
            return new FetchedPage(304, finalUrl, contentType, null, new byte[0], etag, lastModified, false);
        }

        BoundedBody bounded = readBounded(response.body(), command.maxBytes(), command.timeout());
        return new FetchedPage(response.statusCode(), finalUrl, contentType, charsetOf(contentType),
                bounded.bytes(), etag, lastModified, bounded.truncated());
    }

    /**
     * Reads at most {@code maxBytes}, then stops. Closing the stream early aborts
     * the transfer rather than politely draining a response we have no use for.
     */
    private BoundedBody readBounded(InputStream stream, long maxBytes, Duration timeout) throws IOException {
        long cap = maxBytes > 0 ? maxBytes : Long.MAX_VALUE;
        // The request timeout covers the response HEADERS only. Without a deadline
        // on the body a server that trickles one byte per second holds this thread
        // for weeks — the crawl's own budget is checked between pages, not during
        // one.
        Duration budget = readBudget(timeout);
        long deadlineNanos = System.nanoTime() + budget.toNanos();

        // The check in the loop only runs when a read returns, and a server that
        // sends its headers and then nothing never returns one. Closing the stream
        // from outside is what unblocks a read stuck there: it throws, and what was
        // collected so far comes back as truncated.
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> watchdog = runtime.getScheduledExecutorService().schedule(() -> {
            expired.set(true);
            closeQuietly(stream);
        }, budget.toMillis(), TimeUnit.MILLISECONDS);

        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try (InputStream body = stream) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                if (System.nanoTime() > deadlineNanos) {
                    // Closing the stream on the way out aborts the transfer; what was
                    // read is returned as truncated rather than discarded.
                    return new BoundedBody(collected.toByteArray(), true);
                }
                if (total + read > cap) {
                    collected.write(buffer, 0, (int) (cap - total));
                    return new BoundedBody(collected.toByteArray(), true);
                }
                collected.write(buffer, 0, read);
                total += read;
            }
            return new BoundedBody(collected.toByteArray(), expired.get());
        } catch (IOException e) {
            if (expired.get()) {
                return new BoundedBody(collected.toByteArray(), true);
            }
            throw e;
        } finally {
            watchdog.cancel(false);
        }
    }

    /**
     * How long a body may take. A multiple of the per-request timeout: a large page
     * on a slow link is legitimate, a page that never ends is not.
     */
    private static Duration readBudget(Duration requestTimeout) {
        Duration base = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? Duration.ofSeconds(15)
                : requestTimeout;
        return base.multipliedBy(BODY_READ_TIMEOUT_FACTOR);
    }

    private static void closeQuietly(InputStream stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            // Nothing useful to do: the response is already decided.
        }
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    /**
     * Charset from the Content-Type header, or null so the parser can sniff the
     * document's own {@code <meta charset>}. Assuming UTF-8 — which
     * {@code BodyHandlers.ofString()} does — turns every legacy Windows-1252 page
     * into mojibake, and mojibake embeds perfectly happily.
     */
    private static String charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("charset=")) {
                String charset = trimmed.substring("charset=".length()).replace("\"", "").trim();
                if (charset.isEmpty()) {
                    return null;
                }
                // A name the JVM does not know (utf8mb4) or cannot even parse
                // ("utf-8, utf-8", which a broken proxy really does send) would throw
                // out of the parser and lose the page — and a lost page counts as a
                // miss, so two runs later it is deleted. Null instead: the parser
                // sniffs the document's own meta charset.
                try {
                    return Charset.isSupported(charset) ? charset : null;
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private record BoundedBody(byte[] bytes, boolean truncated) {
    }
}
