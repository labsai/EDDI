package ai.labs.eddi.modules.llm.tools.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import static ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl;

/**
 * Web content extraction tool for scraping and parsing HTML content.
 */
@ApplicationScoped
public class WebScraperTool {
    private static final Logger LOGGER = Logger.getLogger(WebScraperTool.class);

    /** Maximum number of HTTP redirects to follow per request. */
    private static final int MAX_REDIRECTS = 5;

    /** HTTP status codes that indicate a redirect. */
    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);

    private final HttpClient httpClient;

    public WebScraperTool() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Tool("Extracts text content from a web page URL. Returns the main text content without HTML tags.")
    public String extractWebPageText(@P("url") String url) {

        try {
            LOGGER.info("Extracting text from URL: " + url);

            // Fetch the web page
            String html = fetchUrl(url);

            // Parse HTML and extract text
            Document doc = Jsoup.parse(html);

            // Remove script and style elements
            doc.select("script, style, nav, footer, header, aside").remove();

            // Extract title
            String title = doc.title();

            // Extract main content
            String mainContent = extractMainContent(doc);

            StringBuilder result = new StringBuilder();
            if (!title.isEmpty()) {
                result.append("Title: ").append(title).append("\n\n");
            }
            result.append(mainContent);

            String resultStr = result.toString().trim();
            LOGGER.debug("Extracted " + resultStr.length() + " characters from " + url);

            // Limit result size
            if (resultStr.length() > 5000) {
                resultStr = resultStr.substring(0, 5000) + "\n\n[Content truncated - showing first 5000 characters]";
            }

            return resultStr;

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

            String html = fetchUrl(url);
            Document doc = Jsoup.parse(html);

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
            return result.toString();

        } catch (Exception e) {
            LOGGER.error("Link extraction error for " + url + ": " + e.getMessage());
            return "Error: Could not extract links - " + e.getMessage();
        }
    }

    @Tool("Extracts structured data from a web page using CSS selectors")
    public String extractWithSelector(@P("url") String url, @P("cssSelector") String cssSelector) {

        try {
            LOGGER.info("Extracting elements matching '" + cssSelector + "' from " + url);

            String html = fetchUrl(url);
            Document doc = Jsoup.parse(html);

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
            return result.toString();

        } catch (Exception e) {
            LOGGER.error("Selector extraction error: " + e.getMessage());
            return "Error: Could not extract with selector - " + e.getMessage();
        }
    }

    @Tool("Gets metadata from a web page (title, description, keywords)")
    public String extractMetadata(@P("url") String url) {

        try {
            LOGGER.info("Extracting metadata from URL: " + url);

            String html = fetchUrl(url);
            Document doc = Jsoup.parse(html);

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
            return result.toString();

        } catch (Exception e) {
            LOGGER.error("Metadata extraction error: " + e.getMessage());
            return "Error: Could not extract metadata - " + e.getMessage();
        }
    }

    /**
     * Fetches the content of a URL with SSRF-safe redirect handling. Validates the
     * initial URL and every redirect hop. Caps total redirects at
     * {@link #MAX_REDIRECTS}.
     *
     * @param url
     *            the URL to fetch
     * @return the response body as a string
     * @throws IOException
     *             if the request fails
     * @throws InterruptedException
     *             if the thread is interrupted
     * @throws IllegalArgumentException
     *             if any URL (initial or redirect) is unsafe
     */
    private String fetchUrl(String url) throws IOException, InterruptedException {
        validateUrl(url);

        URI currentUri = URI.create(url);
        int redirectCount = 0;

        while (true) {
            HttpRequest request = HttpRequest.newBuilder().uri(currentUri).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (EDDI-Agent/1.0)").GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (!REDIRECT_CODES.contains(statusCode)) {
                // Not a redirect — return the response
                if (statusCode != 200) {
                    throw new IOException("HTTP " + statusCode + " for URL: " + currentUri);
                }
                return response.body();
            }

            // Handle redirect
            redirectCount++;
            if (redirectCount > MAX_REDIRECTS) {
                throw new IOException("Too many redirects (" + redirectCount + ") for URL: " + url);
            }

            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null || location.isBlank()) {
                throw new IOException("Redirect " + statusCode + " without Location header from: " + currentUri);
            }

            // Resolve relative Location against the current URI
            URI resolvedUri = currentUri.resolve(location);

            // Validate the redirect target — prevents SSRF via 302 → internal
            validateUrl(resolvedUri.toString());

            LOGGER.debugf("Following redirect %d/%d: %s → %s", redirectCount, MAX_REDIRECTS, currentUri, resolvedUri);
            currentUri = resolvedUri;
        }
    }

    private String extractMainContent(Document doc) {
        // Try to find main content area
        Element main = doc.selectFirst("main, article, .content, .main-content, #content, #main");

        if (main != null) {
            return main.text();
        }

        // Fallback to body
        Element body = doc.body();
        if (body != null) {
            return body.text();
        }

        return doc.text();
    }
}
