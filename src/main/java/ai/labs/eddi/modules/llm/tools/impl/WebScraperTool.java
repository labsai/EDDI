/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl;

/**
 * Web content extraction tool for scraping and parsing HTML content.
 */
@ApplicationScoped
public class WebScraperTool {
    private static final Logger LOGGER = Logger.getLogger(WebScraperTool.class);

    /** Cap on what a single extraction hands back to the model. */
    private static final int MAX_EXTRACTED_CHARACTERS = 5000;

    /** Backstop response cap used when no configuration is supplied (5 MiB). */
    static final long DEFAULT_MAX_RESPONSE_BYTES = 5L * 1024 * 1024;

    private final SafeHttpClient httpClient;
    private final HtmlToMarkdownConverter htmlToMarkdownConverter;
    private final long maxResponseBytes;

    @Inject
    public WebScraperTool(SafeHttpClient httpClient, HtmlToMarkdownConverter htmlToMarkdownConverter,
            @ConfigProperty(name = "eddi.tools.web-scraper.max-response-bytes",
                            defaultValue = "5242880") long maxResponseBytes) {
        this.httpClient = httpClient;
        this.htmlToMarkdownConverter = htmlToMarkdownConverter;
        this.maxResponseBytes = maxResponseBytes > 0 ? maxResponseBytes : DEFAULT_MAX_RESPONSE_BYTES;
    }

    /** Convenience constructor for tests and callers with no configured cap. */
    public WebScraperTool(SafeHttpClient httpClient, HtmlToMarkdownConverter htmlToMarkdownConverter) {
        this(httpClient, htmlToMarkdownConverter, DEFAULT_MAX_RESPONSE_BYTES);
    }

    @Tool("Extracts the readable content of a web page URL as Markdown, with navigation, scripts and other page chrome removed.")
    public String extractWebPageText(@P("url") String url) {

        try {
            LOGGER.info("Extracting text from URL: " + url);

            FetchedPage page = fetchUrl(url);

            // Delegated rather than re-implemented: this tool used to run its own
            // "script, style, nav, footer, header, aside" strip plus a flat text()
            // dump, which dropped page titles living in <article><header><h1> and
            // merged adjacent blocks into single words. The converter keeps the
            // structure an LLM can actually use — headings, lists, tables, code.
            return page.annotate(htmlToMarkdownConverter.convert(page.html(), url, MAX_EXTRACTED_CHARACTERS));

        } catch (Exception e) {
            LOGGER.error("Web page extraction error for " + url + ": " + e.getMessage());
            return "Error: Could not extract content from web page - " + e.getMessage();
        }
    }

    @Tool("Extracts all links (URLs) from a web page")
    public String extractLinks(@P("url") String url, @P("maxLinks") Integer maxLinks) {

        try {
            if (maxLinks == null || maxLinks < 1) {
                maxLinks = 20;
            }
            if (maxLinks > 50) {
                maxLinks = 50;
            }

            LOGGER.info("Extracting links from URL: " + url);

            FetchedPage page = fetchUrl(url);
            Document doc = Jsoup.parse(page.html());

            Elements links = doc.select("a[href]");

            StringBuilder result = new StringBuilder();
            result.append("Links found on ").append(url).append(":\n\n");

            int count = 0;
            for (Element link : links) {
                if (count >= maxLinks)
                    break;

                String href = link.attr("abs:href"); // Get absolute URL
                String text = link.text();

                if (!href.isEmpty()) {
                    result.append(++count).append(". ");
                    if (!text.isEmpty()) {
                        result.append(text).append(" - ");
                    }
                    result.append(href).append("\n");
                }
            }

            if (count == 0) {
                result.append("No links found.");
            }

            LOGGER.debug("Extracted " + count + " links from " + url);
            return page.annotate(result.toString());

        } catch (Exception e) {
            LOGGER.error("Link extraction error for " + url + ": " + e.getMessage());
            return "Error: Could not extract links - " + e.getMessage();
        }
    }

    @Tool("Extracts structured data from a web page using CSS selectors")
    public String extractWithSelector(@P("url") String url, @P("cssSelector") String cssSelector) {

        try {
            LOGGER.info("Extracting elements matching '" + cssSelector + "' from " + url);

            FetchedPage page = fetchUrl(url);
            Document doc = Jsoup.parse(page.html());

            Elements elements = doc.select(cssSelector);

            if (elements.isEmpty()) {
                return "No elements found matching selector: " + cssSelector;
            }

            StringBuilder result = new StringBuilder();
            result.append("Found ").append(elements.size()).append(" element(s) matching '").append(cssSelector).append("':\n\n");

            int count = 0;
            for (Element element : elements) {
                if (count >= 20) { // Limit to 20 elements
                    result.append("\n[Additional elements truncated]");
                    break;
                }

                result.append(++count).append(". ").append(element.text()).append("\n");
            }

            LOGGER.debug("Extracted " + count + " elements from " + url);
            return page.annotate(result.toString());

        } catch (Exception e) {
            LOGGER.error("Selector extraction error: " + e.getMessage());
            return "Error: Could not extract with selector - " + e.getMessage();
        }
    }

    @Tool("Gets metadata from a web page (title, description, keywords)")
    public String extractMetadata(@P("url") String url) {

        try {
            LOGGER.info("Extracting metadata from URL: " + url);

            FetchedPage page = fetchUrl(url);
            Document doc = Jsoup.parse(page.html());

            StringBuilder result = new StringBuilder();
            result.append("Metadata for ").append(url).append(":\n\n");

            // Title
            String title = doc.title();
            if (!title.isEmpty()) {
                result.append("Title: ").append(title).append("\n");
            }

            // Description
            Element descMeta = doc.selectFirst("meta[name=description]");
            if (descMeta != null) {
                result.append("Description: ").append(descMeta.attr("content")).append("\n");
            }

            // Keywords
            Element keywordsMeta = doc.selectFirst("meta[name=keywords]");
            if (keywordsMeta != null) {
                result.append("Keywords: ").append(keywordsMeta.attr("content")).append("\n");
            }

            // Author
            Element authorMeta = doc.selectFirst("meta[name=author]");
            if (authorMeta != null) {
                result.append("Author: ").append(authorMeta.attr("content")).append("\n");
            }

            // Open Graph title
            Element ogTitle = doc.selectFirst("meta[property=og:title]");
            if (ogTitle != null) {
                result.append("OG Title: ").append(ogTitle.attr("content")).append("\n");
            }

            // Open Graph description
            Element ogDesc = doc.selectFirst("meta[property=og:description]");
            if (ogDesc != null) {
                result.append("OG Description: ").append(ogDesc.attr("content")).append("\n");
            }

            LOGGER.debug("Metadata extracted from " + url);
            return page.annotate(result.toString());

        } catch (Exception e) {
            LOGGER.error("Metadata extraction error: " + e.getMessage());
            return "Error: Could not extract metadata - " + e.getMessage();
        }
    }

    /**
     * Fetches the content of a URL using the SSRF-safe SafeHttpClient. The URL is
     * validated (SSRF check) and redirects are handled safely.
     */
    private FetchedPage fetchUrl(String url) throws IOException, InterruptedException {
        validateUrl(url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (EDDI-Agent/1.0)")
                .GET().build();

        // Bounded read: an LLM-chosen URL must not be able to stream an unbounded
        // body into memory. The body is capped as it arrives rather than pulled in
        // full and measured afterwards.
        SafeHttpClient.BoundedResponse response = httpClient.sendBounded(request, maxResponseBytes);

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for URL: " + url);
        }

        if (!response.truncated()) {
            return new FetchedPage(new String(response.body(), StandardCharsets.UTF_8), null);
        }
        // A truncated body is either over the size cap or cut off by the read
        // deadline; the flag does not say which. Both leave a partial page, so the
        // caller's output carries an explicit note instead of passing the fragment
        // off as the whole page, and a multi-byte character split by the cut is
        // dropped rather than decoded to a replacement character.
        LOGGER.warnf("Response from %s was truncated (size cap of %d bytes or read deadline); returning partial content", url,
                maxResponseBytes);
        byte[] body = response.body();
        String html = new String(body, 0, completeUtf8Length(body), StandardCharsets.UTF_8);
        return new FetchedPage(html, "\n\n[Note: the page response was truncated (it exceeded " + maxResponseBytes
                + " bytes or the read deadline expired); the content above is partial.]");
    }

    /**
     * Length of {@code bytes} without a trailing, incomplete UTF-8 sequence — the
     * part a byte-count cut can leave dangling.
     */
    static int completeUtf8Length(byte[] bytes) {
        int end = bytes.length;
        int start = end - 1;
        // Walk back over at most three continuation bytes (10xxxxxx) to the lead byte.
        while (start >= 0 && end - start <= 4 && (bytes[start] & 0xC0) == 0x80) {
            start--;
        }
        if (start < 0 || end - start > 4) {
            return end; // no lead byte in reach: not UTF-8 we can repair, leave it
        }
        int lead = bytes[start] & 0xFF;
        int expected = lead < 0x80 ? 1 : lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
        return end - start < expected ? start : end;
    }

    /**
     * A fetched page and, when the body was truncated, the note to append to the
     * tool's output.
     */
    private record FetchedPage(String html, String truncationNote) {
        String annotate(String output) {
            return truncationNote == null ? output : output + truncationNote;
        }
    }

}
