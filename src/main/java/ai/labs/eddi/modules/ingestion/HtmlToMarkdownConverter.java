/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import jakarta.enterprise.context.ApplicationScoped;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Converts HTML to clean Markdown format using JSoup.
 * <p>
 * Strips noise elements (nav, footer, header, aside, script, style, iframe),
 * then walks the DOM tree and converts to Markdown syntax.
 * <p>
 * Supported conversions:
 * <ul>
 * <li>{@code
 *
<h1>-
 *
<h6>} → {@code # Heading} (with appropriate level)</li>
 * <li>{@code
 *
<p>
 * } → paragraph text with blank lines</li>
 * <li>{@code
 *
 *

<pre>
 * <code>} → {@code ```language\ncontent\n```}</li>
 * <li>{@code <a href>} → {@code [text](href)}</li>
 * <li>{@code
 *
<table>
 * } → Markdown table format</li>
 * <li>{@code
 *
<ul>
 * /
 *
<ol>
 * } → {@code - item} / {@code 1. item}</li>
 * <li>{@code <blockquote>} → {@code > quoted text}</li>
 * <li>{@code <img>} → {@code ![alt](src)}</li>
 * <li>{@code <strong>/<b>} → {@code **bold**}</li>
 * <li>{@code <em>/<i>} → {@code *italic*}</li>
 * <li>{@code <code>} → {@code `inline code`}</li>
 * </ul>
 *
 * @since 6.0.3
 */
@ApplicationScoped
public class HtmlToMarkdownConverter implements ContentConverter {

    /**
     * Checks if this converter supports the given content type.
     *
     * @param contentType
     *            the MIME type (e.g., "text/html", "application/xhtml+xml")
     * @return true if this converter can handle the content type
     */
    @Override
    public boolean supports(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase();
        return normalized.contains("html") || normalized.contains("xhtml");
    }

    /**
     * Converts raw HTML to clean Markdown format.
     *
     * @param html
     *            the raw HTML content
     * @param baseUrl
     *            the base URL for resolving relative links
     * @param maxLength
     *            maximum content length (truncates if exceeded)
     * @return the Markdown-formatted content
     */
    @Override
    public String convert(String html, String baseUrl, int maxLength) {
        if (html == null || html.isBlank()) {
            return "";
        }

        Document doc = Jsoup.parse(html);

        // Remove noise elements
        doc.select("script, style, nav, footer, header, aside, iframe, noscript").remove();

        // Extract title
        String title = doc.title();

        // Get the body or main content
        Element root = doc.body();
        if (root == null) {
            return "";
        }

        // Try to find main content area
        Element main = doc.selectFirst("main, article, #content, #main, .content, .main-content");
        if (main != null) {
            root = main;
        }

        // Build markdown
        StringBuilder markdown = new StringBuilder();

        // Add title if present and not already in content
        if (title != null && !title.isBlank() && !containsTitle(root, title)) {
            markdown.append("# ").append(escapeMarkdown(title)).append("\n\n");
        }

        // Convert root element
        convertElement(root, markdown, baseUrl, false);

        String result = markdown.toString().trim();

        // Truncate if exceeds max length
        if (result.length() > maxLength) {
            result = result.substring(0, maxLength) + "\n\n[Content truncated - exceeded " + maxLength + " character limit]";
        }

        return result;
    }

    /**
     * Converts raw HTML to clean Markdown format with default max length.
     *
     * @param html
     *            the raw HTML content
     * @param baseUrl
     *            the base URL for resolving relative links
     * @return the Markdown-formatted content
     */
    public String convert(String html, String baseUrl) {
        return convert(html, baseUrl, 100_000);
    }

    private boolean containsTitle(Element root, String title) {
        Elements h1s = root.select("h1");
        for (Element h1 : h1s) {
            if (h1.text().trim().equals(title)) {
                return true;
            }
        }
        return false;
    }

    private void convertElement(Element element, StringBuilder output, String baseUrl, boolean inPreBlock) {
        String tagName = element.tagName().toLowerCase();

        switch (tagName) {
            case "h1" -> appendHeading(output, element, 1);
            case "h2" -> appendHeading(output, element, 2);
            case "h3" -> appendHeading(output, element, 3);
            case "h4" -> appendHeading(output, element, 4);
            case "h5" -> appendHeading(output, element, 5);
            case "h6" -> appendHeading(output, element, 6);
            case "p" -> appendParagraph(output, element, baseUrl);
            case "pre" -> appendPreBlock(output, element, baseUrl);
            case "blockquote" -> appendBlockquote(output, element, baseUrl);
            case "ul" -> appendList(output, element, baseUrl, false);
            case "ol" -> appendList(output, element, baseUrl, true);
            case "table" -> appendTable(output, element);
            case "hr" -> output.append("\n---\n\n");
            case "br" -> output.append("\n");
            case "div", "section", "article", "main", "body" -> appendChildren(output, element, baseUrl, inPreBlock);
            case "span" -> appendInline(output, element, baseUrl);
            case "a" -> {
                if (!inPreBlock) {
                    appendLink(output, element, baseUrl);
                } else {
                    appendInline(output, element, baseUrl);
                }
            }
            case "img" -> appendImage(output, element, baseUrl);
            case "strong", "b" -> appendInlineFormatted(output, element, baseUrl, "**");
            case "em", "i" -> appendInlineFormatted(output, element, baseUrl, "*");
            case "code" -> {
                if (!inPreBlock) {
                    output.append("`").append(escapeInlineCode(element.text())).append("`");
                } else {
                    output.append(element.text());
                }
            }
            case "del", "s", "strike" -> appendInlineFormatted(output, element, baseUrl, "~~");
            case "sub" -> appendInlineWrapped(output, element, baseUrl, "~");
            case "sup" -> appendInlineWrapped(output, element, baseUrl, "^");
            default -> appendChildren(output, element, baseUrl, inPreBlock);
        }
    }

    private void appendChildren(StringBuilder output, Element element, String baseUrl, boolean inPreBlock) {
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode textNode) {
                String text = textNode.text();
                if (!inPreBlock) {
                    text = normalizeWhitespace(text);
                }
                output.append(text);
            } else if (child instanceof Element childElement) {
                convertElement(childElement, output, baseUrl, inPreBlock);
            }
        }
    }

    private void appendHeading(StringBuilder output, Element element, int level) {
        output.append("\n").append("#".repeat(level)).append(" ");
        appendInline(output, element, null);
        output.append("\n\n");
    }

    private void appendParagraph(StringBuilder output, Element element, String baseUrl) {
        output.append("\n");
        appendInline(output, element, baseUrl);
        output.append("\n\n");
    }

    private void appendPreBlock(StringBuilder output, Element element, String baseUrl) {
        output.append("\n```");

        // Try to detect language from class
        Element code = element.selectFirst("code");
        if (code != null) {
            String classAttr = code.className();
            if (classAttr != null && !classAttr.isBlank()) {
                // Common pattern: class="language-java" or class="lang-java"
                String lang = extractLanguage(classAttr);
                if (!lang.isBlank()) {
                    output.append(lang);
                }
            }
            output.append("\n").append(code.text());
        } else {
            output.append("\n").append(element.text());
        }

        output.append("\n```\n\n");
    }

    private String extractLanguage(String classAttr) {
        String[] classes = classAttr.split("\\s+");
        for (String cls : classes) {
            String lower = cls.toLowerCase();
            if (lower.startsWith("language-")) {
                return lower.substring(9);
            }
            if (lower.startsWith("lang-")) {
                return lower.substring(5);
            }
        }
        return "";
    }

    private void appendBlockquote(StringBuilder output, Element element, String baseUrl) {
        output.append("\n");
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode textNode) {
                output.append("> ").append(normalizeWhitespace(textNode.text()));
            } else if (child instanceof Element childElement) {
                String childTag = childElement.tagName().toLowerCase();
                if (childTag.equals("p") || childTag.equals("div")) {
                    StringBuilder inner = new StringBuilder();
                    appendInline(inner, childElement, baseUrl);
                    String[] lines = inner.toString().split("\\r?\\n");
                    for (String line : lines) {
                        if (!line.isBlank()) {
                            output.append("> ").append(line).append("\n");
                        }
                    }
                } else {
                    convertElement(childElement, output, baseUrl, false);
                }
            }
        }
        output.append("\n");
    }

    private void appendList(StringBuilder output, Element element, String baseUrl, boolean ordered) {
        output.append("\n");
        // Use element.children() and filter for li elements (JSoup doesn't support
        // :scope)
        int number = 1;
        for (Element item : element.children()) {
            if (!item.tagName().equalsIgnoreCase("li")) {
                continue;
            }
            String marker = ordered ? number + "." : "-";
            output.append(marker).append(" ");

            // Process item content
            StringBuilder itemContent = new StringBuilder();
            for (Node child : item.childNodes()) {
                if (child instanceof TextNode textNode) {
                    itemContent.append(normalizeWhitespace(textNode.text()));
                } else if (child instanceof Element childElement) {
                    String childTag = childElement.tagName().toLowerCase();
                    if (childTag.equals("ul") || childTag.equals("ol")) {
                        // Nested list - indent
                        StringBuilder nested = new StringBuilder();
                        appendList(nested, childElement, baseUrl, childTag.equals("ol"));
                        String[] nestedLines = nested.toString().split("\\r?\\n");
                        for (String line : nestedLines) {
                            if (!line.isBlank()) {
                                itemContent.append("\n    ").append(line.trim());
                            }
                        }
                    } else {
                        convertElement(childElement, itemContent, baseUrl, false);
                    }
                }
            }

            output.append(itemContent.toString().trim().replace("\n", "\n    "));
            output.append("\n");
            number++;
        }
        output.append("\n");
    }

    private void appendTable(StringBuilder output, Element element) {
        output.append("\n");

        Element thead = element.selectFirst("thead");
        Element tbody = element.selectFirst("tbody");

        // Process header
        Element headerRow = thead != null ? thead.selectFirst("tr") : element.selectFirst("tr");
        if (headerRow != null) {
            Elements headers = headerRow.select("th, td");
            if (!headers.isEmpty()) {
                output.append("| ");
                for (Element th : headers) {
                    output.append(th.text().trim()).append(" | ");
                }
                output.append("\n| ");
                for (int i = 0; i < headers.size(); i++) {
                    output.append("--- | ");
                }
                output.append("\n");
            }
        }

        // Process body rows
        Elements rows = tbody != null ? tbody.select("tr") : element.select("tr");
        // Skip header row if already processed
        boolean firstRow = headerRow != null;
        for (Element row : rows) {
            if (firstRow && row.equals(headerRow)) {
                firstRow = false;
                continue;
            }
            Elements cells = row.select("td, th");
            if (!cells.isEmpty()) {
                output.append("| ");
                for (Element cell : cells) {
                    output.append(cell.text().trim()).append(" | ");
                }
                output.append("\n");
            }
        }

        output.append("\n");
    }

    private void appendLink(StringBuilder output, Element element, String baseUrl) {
        String href = element.attr("href");
        String text = element.text().trim();

        if (href == null || href.isBlank()) {
            output.append(text);
            return;
        }

        // Resolve relative URLs
        href = resolveUrl(href, baseUrl);

        // If text equals href, use autolink style
        if (text.equals(href)) {
            output.append("<").append(href).append(">");
        } else {
            output.append("[").append(escapeMarkdown(text)).append("](").append(href).append(")");
        }
    }

    private void appendImage(StringBuilder output, Element element, String baseUrl) {
        String src = element.attr("src");
        String alt = element.attr("alt");

        if (src == null || src.isBlank()) {
            return;
        }

        src = resolveUrl(src, baseUrl);
        output.append("![").append(alt != null ? alt : "").append("](").append(src).append(")");
    }

    private void appendInline(StringBuilder output, Element element, String baseUrl) {
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode textNode) {
                output.append(normalizeWhitespace(textNode.text()));
            } else if (child instanceof Element childElement) {
                convertElement(childElement, output, baseUrl, false);
            }
        }
    }

    private void appendInlineFormatted(StringBuilder output, Element element, String baseUrl, String wrapper) {
        output.append(wrapper);
        appendInline(output, element, baseUrl);
        output.append(wrapper);
    }

    private void appendInlineWrapped(StringBuilder output, Element element, String baseUrl, String wrapper) {
        output.append(wrapper);
        appendInline(output, element, baseUrl);
        output.append(wrapper);
    }

    private String resolveUrl(String url, String baseUrl) {
        if (url == null || baseUrl == null) {
            return url;
        }

        // Already absolute
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("//")) {
            return url;
        }

        try {
            URI base = new URI(baseUrl);
            URI resolved = base.resolve(url);
            return resolved.toString();
        } catch (URISyntaxException e) {
            return url;
        }
    }

    private String normalizeWhitespace(String text) {
        if (text == null) {
            return "";
        }
        // Collapse multiple whitespace to single space
        return text.replaceAll("\\s+", " ");
    }

    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        // Escape special markdown characters: *, _, `, [, ], <, >
        return text
                .replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("<", "\\<")
                .replace(">", "\\>");
    }

    private String escapeInlineCode(String text) {
        if (text == null) {
            return "";
        }
        // Only escape backticks in inline code
        return text.replace("`", "\\`");
    }
}
