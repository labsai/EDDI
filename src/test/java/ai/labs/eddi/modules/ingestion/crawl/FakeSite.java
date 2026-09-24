/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import ai.labs.eddi.modules.ingestion.crawl.PageFetcher.FetchCommand;
import ai.labs.eddi.modules.ingestion.crawl.PageFetcher.FetchedPage;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory website, served through the {@link PageFetcher} seam.
 *
 * <p>
 * The whole point of injecting the fetcher: the crawler's scope decisions,
 * budgets, redirect handling, robots compliance, conditional requests and error
 * accounting are all exercised here with no network, no container and no test
 * server — so they run in the unit gate on every build rather than in an
 * integration job that the unit run skips.
 */
public final class FakeSite implements PageFetcher {

    private final Map<String, Response> responses = new LinkedHashMap<>();
    private final List<FetchCommand> requests = new ArrayList<>();
    private final Map<String, IOException> failures = new HashMap<>();

    /** Registers an HTML page. */
    public FakeSite page(String url, String html) {
        responses.put(url, new Response(200, url, "text/html; charset=utf-8",
                html.getBytes(StandardCharsets.UTF_8), null, null));
        return this;
    }

    /** Registers a page whose body is encoded in something other than UTF-8. */
    public FakeSite pageEncoded(String url, String html, Charset charset, boolean declareInHeader) {
        responses.put(url, new Response(200, url,
                declareInHeader ? "text/html; charset=" + charset.name() : "text/html",
                html.getBytes(charset), null, null));
        return this;
    }

    /**
     * Registers a page carrying validators, so a later fetch can be conditional.
     */
    public FakeSite pageWithValidators(String url, String html, String etag, String lastModified) {
        responses.put(url, new Response(200, url, "text/html", html.getBytes(StandardCharsets.UTF_8),
                etag, lastModified));
        return this;
    }

    /** Registers a page that answers 304 when the request carries any validator. */
    public FakeSite conditional(String url, String html, String etag) {
        responses.put(url, new Response(200, url, "text/html", html.getBytes(StandardCharsets.UTF_8), etag, null)
                .answering304WhenConditional());
        return this;
    }

    /** Registers a URL that redirects: the fetch lands on {@code finalUrl}. */
    public FakeSite redirect(String requestedUrl, String finalUrl, String html) {
        responses.put(requestedUrl, new Response(200, finalUrl, "text/html",
                html.getBytes(StandardCharsets.UTF_8), null, null));
        return this;
    }

    /** Registers a non-HTML resource. */
    public FakeSite binary(String url, String contentType, int sizeBytes) {
        responses.put(url, new Response(200, url, contentType, new byte[sizeBytes], null, null));
        return this;
    }

    /** Registers an HTTP error status. */
    public FakeSite status(String url, int statusCode) {
        responses.put(url, new Response(statusCode, url, "text/html", new byte[0], null, null));
        return this;
    }

    /** Registers a transport failure. */
    public FakeSite failure(String url, String message) {
        failures.put(url, new IOException(message));
        return this;
    }

    public FakeSite robots(String baseUrl, String content) {
        responses.put(baseUrl + "/robots.txt", new Response(200, baseUrl + "/robots.txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8), null, null));
        return this;
    }

    public FakeSite sitemap(String url, String... pageUrls) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset>");
        for (String pageUrl : pageUrls) {
            xml.append("<url><loc>").append(pageUrl).append("</loc></url>");
        }
        xml.append("</urlset>");
        responses.put(url, new Response(200, url, "application/xml",
                xml.toString().getBytes(StandardCharsets.UTF_8), null, null));
        return this;
    }

    @Override
    public FetchedPage fetch(FetchCommand command) throws IOException {
        requests.add(command);

        IOException failure = failures.get(command.url());
        if (failure != null) {
            throw failure;
        }

        Response response = responses.get(command.url());
        if (response == null) {
            // Anything not registered is a 404, like a real site.
            return new FetchedPage(404, command.url(), "text/html", null, new byte[0], null, null, false);
        }
        return response.toFetchedPage(command);
    }

    /** Every request the crawler made, in order. */
    public List<FetchCommand> requests() {
        return requests;
    }

    public List<String> requestedUrls() {
        return requests.stream().map(FetchCommand::url).toList();
    }

    public boolean wasRequested(String url) {
        return requestedUrls().contains(url);
    }

    public long requestCount(String url) {
        return requestedUrls().stream().filter(url::equals).count();
    }

    private static final class Response {
        private final int statusCode;
        private final String finalUrl;
        private final String contentType;
        private final byte[] body;
        private final String etag;
        private final String lastModified;
        private boolean answer304WhenConditional;

        private Response(int statusCode, String finalUrl, String contentType, byte[] body,
                String etag, String lastModified) {
            this.statusCode = statusCode;
            this.finalUrl = finalUrl;
            this.contentType = contentType;
            this.body = body;
            this.etag = etag;
            this.lastModified = lastModified;
        }

        Response answering304WhenConditional() {
            this.answer304WhenConditional = true;
            return this;
        }

        FetchedPage toFetchedPage(FetchCommand command) {
            boolean conditional = (command.ifNoneMatch() != null && !command.ifNoneMatch().isBlank())
                    || (command.ifModifiedSince() != null && !command.ifModifiedSince().isBlank());
            if (answer304WhenConditional && conditional) {
                return new FetchedPage(304, finalUrl, contentType, null, new byte[0], etag, lastModified, false);
            }

            byte[] served = body;
            boolean truncated = false;
            if (command.maxBytes() > 0 && served.length > command.maxBytes()) {
                served = new byte[(int) command.maxBytes()];
                System.arraycopy(body, 0, served, 0, served.length);
                truncated = true;
            }
            String charset = charsetOf(contentType);
            return new FetchedPage(statusCode, finalUrl, contentType, charset, served, etag, lastModified, truncated);
        }

        private static String charsetOf(String contentType) {
            if (contentType == null) {
                return null;
            }
            for (String part : contentType.split(";")) {
                String trimmed = part.trim().toLowerCase();
                if (trimmed.startsWith("charset=")) {
                    return trimmed.substring("charset=".length());
                }
            }
            return null;
        }
    }
}
