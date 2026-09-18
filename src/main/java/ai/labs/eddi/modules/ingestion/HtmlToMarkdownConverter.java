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
 * Converts HTML into Markdown suitable for embedding.
 *
 * <p>
 * This is tuned for retrieval, not for round-tripping a document: it keeps the
 * structure that carries meaning (headings, lists, tables, code, link text) and
 * drops the chrome that pollutes a vector store (navigation, cookie banners,
 * buttons, decorative images). Markdown — rather than flat text — is what lets
 * a later chunker split on heading boundaries and record which section a
 * passage came from.
 *
 * <p>
 * A hand-written walker is deliberate. The obvious alternative, a general
 * HTML→Markdown library, optimises for fidelity to the source document, while
 * ingestion wants the opposite: aggressive removal of everything a reader would
 * skip. jsoup is already on the classpath for exactly this kind of work.
 *
 * <h2>Boilerplate handling</h2>
 * <p>
 * Noise is removed by selector, then the walk is scoped to
 * {@code main}/{@code article} when the page offers one. Note that
 * {@code header} is only stripped at page level: most documentation themes put
 * the page title inside {@code &lt;article&gt;&lt;header&gt;&lt;h1&gt;}, and
 * stripping every {@code header} deleted it.
 */
@ApplicationScoped
public class HtmlToMarkdownConverter {

    /** Fallback cap when a caller does not specify one. */
    private static final int DEFAULT_MAX_LENGTH = 100_000;

    /**
     * How deep the walk will go before it stops descending.
     *
     * <p>
     * The walk is recursive and the HTML comes from third parties. A page with tens
     * of thousands of nested elements — generated markup, or a deliberately hostile
     * one — overflows the stack, and a {@code StackOverflowError} is an
     * {@code Error}: it sails past every {@code catch (Exception)} in the ingestion
     * pipeline and kills the run mid-document. Real documents are nowhere near this
     * deep.
     */
    private static final int MAX_DEPTH = 200;

    /**
     * Page furniture that never carries retrievable meaning. Deliberately
     * selector-based rather than a text-density heuristic: predictable, and good
     * enough once the walk is scoped to {@code main}/{@code article}.
     *
     * <p>
     * {@code header} is NOT here — see the class Javadoc. It is removed separately,
     * and only at page level.
     */
    private static final String NOISE_SELECTOR = String.join(", ",
            "script", "style", "noscript", "template", "iframe", "svg", "canvas",
            "nav", "footer", "aside", "form", "button", "dialog",
            "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=complementary]",
            "[role=search]", "[aria-hidden=true]",
            ".sidebar", ".site-header", ".site-footer", ".breadcrumb", ".breadcrumbs",
            ".cookie-banner", ".cookie-consent", ".skip-link");

    /**
     * Page-level header only. {@code body > header} catches the site banner while
     * leaving {@code article > header} — which usually holds the title — intact.
     */
    private static final String PAGE_HEADER_SELECTOR = "body > header, body > div > header";

    /** Where a page's real content usually lives, most specific first. */
    private static final String MAIN_CONTENT_SELECTOR = "main, article, #content, #main, .content, .main-content";

    /**
     * Whether this converter handles the given MIME type.
     */
    public boolean supports(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase();
        return normalized.contains("html") || normalized.contains("xhtml");
    }

    /**
     * Converts raw HTML to Markdown.
     *
     * @param html
     *            raw HTML; null or blank yields an empty string
     * @param baseUrl
     *            base for resolving relative links, may be null
     * @param maxLength
     *            character cap; a non-positive value falls back to
     *            {@value #DEFAULT_MAX_LENGTH}
     */
    public String convert(String html, String baseUrl, int maxLength) {
        if (html == null || html.isBlank()) {
            return "";
        }
        int cap = maxLength > 0 ? maxLength : DEFAULT_MAX_LENGTH;

        Document doc = Jsoup.parse(html);
        doc.select(PAGE_HEADER_SELECTOR).remove();
        doc.select(NOISE_SELECTOR).remove();

        Element root = doc.body();
        if (root == null) {
            return "";
        }

        Element main = doc.selectFirst(MAIN_CONTENT_SELECTOR);
        if (main != null) {
            root = main;
        }

        String title = doc.title();
        StringBuilder markdown = new StringBuilder();
        if (title != null && !title.isBlank() && !containsTitle(root, title)) {
            markdown.append("# ").append(escapeMarkdown(title)).append("\n\n");
        }

        convertElement(root, markdown, baseUrl, 0);

        String result = collapseBlankLines(markdown.toString()).trim();
        if (result.length() > cap) {
            // Never cut between the halves of a surrogate pair: a lone half is not
            // valid text, and some embedding providers reject the whole request.
            int end = Character.isHighSurrogate(result.charAt(cap - 1)) ? cap - 1 : cap;
            result = result.substring(0, end) + "\n\n[Content truncated - exceeded " + cap + " character limit]";
        }
        return result;
    }

    /** Converts with the default length cap. */
    public String convert(String html, String baseUrl) {
        return convert(html, baseUrl, DEFAULT_MAX_LENGTH);
    }

    private boolean containsTitle(Element root, String title) {
        for (Element h1 : root.select("h1")) {
            if (h1.text().trim().equals(title.trim())) {
                return true;
            }
        }
        return false;
    }

    private void convertElement(Element element, StringBuilder output, String baseUrl, int depth) {
        if (depth > MAX_DEPTH) {
            // Stop descending rather than overflowing the stack. The text below is
            // still collected, just without further structure.
            output.append(normalizeWhitespace(element.text()));
            return;
        }
        String tagName = element.tagName().toLowerCase();

        switch (tagName) {
            case "h1" -> appendHeading(output, element, 1, baseUrl, depth);
            case "h2" -> appendHeading(output, element, 2, baseUrl, depth);
            case "h3" -> appendHeading(output, element, 3, baseUrl, depth);
            case "h4" -> appendHeading(output, element, 4, baseUrl, depth);
            case "h5" -> appendHeading(output, element, 5, baseUrl, depth);
            case "h6" -> appendHeading(output, element, 6, baseUrl, depth);
            case "p" -> appendParagraph(output, element, baseUrl, depth);
            case "pre" -> appendPreBlock(output, element);
            case "blockquote" -> appendBlockquote(output, element, baseUrl, depth);
            case "ul" -> appendList(output, element, baseUrl, false, depth);
            case "ol" -> appendList(output, element, baseUrl, true, depth);
            case "dl" -> appendDefinitionList(output, element, baseUrl, depth);
            case "table" -> appendTable(output, element);
            case "details" -> appendDetails(output, element, baseUrl, depth);
            case "figure" -> appendFigure(output, element, baseUrl, depth);
            case "hr" -> output.append("\n---\n\n");
            case "br" -> output.append("\n");
            // Block containers: a separator is required, otherwise adjacent blocks
            // run together and "<div>Hello</div><div>World</div>" embeds as
            // "HelloWorld" — a real defect for div-soup pages.
            case "div", "section", "article", "main", "body", "li", "dd", "dt", "header" ->
                appendBlock(output, element, baseUrl, depth);
            case "span", "label", "small", "time", "cite", "abbr" -> appendInline(output, element, baseUrl, depth);
            case "a" -> appendLink(output, element, baseUrl);
            case "img" -> appendImage(output, element, baseUrl);
            case "strong", "b" -> appendInlineFormatted(output, element, baseUrl, "**", depth);
            case "em", "i" -> appendInlineFormatted(output, element, baseUrl, "*", depth);
            case "code" -> output.append("`").append(escapeInlineCode(element.text())).append("`");
            case "del", "s", "strike" -> appendInlineFormatted(output, element, baseUrl, "~~", depth);
            case "sub" -> appendInlineFormatted(output, element, baseUrl, "~", depth);
            case "sup" -> appendInlineFormatted(output, element, baseUrl, "^", depth);
            default -> appendChildren(output, element, baseUrl, depth);
        }
    }

    /**
     * Appends a block container's children, guaranteeing a line break on each side
     * so neighbouring blocks stay separate words.
     */
    private void appendBlock(StringBuilder output, Element element, String baseUrl, int depth) {
        int before = output.length();
        StringBuilder inner = new StringBuilder();
        appendChildren(inner, element, baseUrl, depth + 1);

        String content = inner.toString();
        if (content.isBlank()) {
            return;
        }
        if (before > 0 && !endsWithNewline(output)) {
            output.append("\n");
        }
        output.append(content);
        if (!endsWithNewline(output)) {
            output.append("\n");
        }
    }

    private static boolean endsWithNewline(StringBuilder sb) {
        return sb.length() == 0 || sb.charAt(sb.length() - 1) == '\n';
    }

    private void appendChildren(StringBuilder output, Element element, String baseUrl, int depth) {
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode textNode) {
                output.append(normalizeWhitespace(textNode.text()));
            } else if (child instanceof Element childElement) {
                convertElement(childElement, output, baseUrl, depth + 1);
            }
        }
    }

    private void appendHeading(StringBuilder output, Element element, int level, String baseUrl, int depth) {
        output.append("\n").append("#".repeat(level)).append(" ");
        appendInline(output, element, baseUrl, depth + 1);
        output.append("\n\n");
    }

    private void appendParagraph(StringBuilder output, Element element, String baseUrl, int depth) {
        output.append("\n");
        appendInline(output, element, baseUrl, depth + 1);
        output.append("\n\n");
    }

    private void appendPreBlock(StringBuilder output, Element element) {
        output.append("\n```");

        Element code = element.selectFirst("code");
        Element source = code != null ? code : element;
        if (code != null) {
            String lang = extractLanguage(code.className());
            if (!lang.isBlank()) {
                output.append(lang);
            }
        }
        // wholeText preserves the newlines inside a code block; text() collapses
        // them, which turned every multi-line sample into one unreadable line.
        output.append("\n").append(stripTrailingNewline(source.wholeText()));
        output.append("\n```\n\n");
    }

    private static String stripTrailingNewline(String text) {
        String stripped = text;
        while (stripped.endsWith("\n") || stripped.endsWith("\r")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private String extractLanguage(String classAttr) {
        if (classAttr == null || classAttr.isBlank()) {
            return "";
        }
        for (String cls : classAttr.split("\\s+")) {
            String lower = cls.toLowerCase();
            if (lower.startsWith("language-")) {
                return lower.substring("language-".length());
            }
            if (lower.startsWith("lang-")) {
                return lower.substring("lang-".length());
            }
        }
        return "";
    }

    private void appendBlockquote(StringBuilder output, Element element, String baseUrl, int depth) {
        StringBuilder inner = new StringBuilder();
        appendChildren(inner, element, baseUrl, depth + 1);

        output.append("\n");
        for (String line : inner.toString().split("\\r?\\n")) {
            if (!line.isBlank()) {
                output.append("> ").append(line.trim()).append("\n");
            }
        }
        output.append("\n");
    }

    private void appendList(StringBuilder output, Element element, String baseUrl, boolean ordered, int depth) {
        output.append("\n");
        int number = 1;
        for (Element item : element.children()) {
            if (!item.tagName().equalsIgnoreCase("li")) {
                continue;
            }
            output.append(ordered ? number + "." : "-").append(" ");

            StringBuilder itemContent = new StringBuilder();
            for (Node child : item.childNodes()) {
                if (child instanceof TextNode textNode) {
                    itemContent.append(normalizeWhitespace(textNode.text()));
                } else if (child instanceof Element childElement) {
                    String childTag = childElement.tagName().toLowerCase();
                    if (childTag.equals("ul") || childTag.equals("ol")) {
                        StringBuilder nested = new StringBuilder();
                        appendList(nested, childElement, baseUrl, childTag.equals("ol"), depth + 1);
                        for (String line : nested.toString().split("\\r?\\n")) {
                            if (!line.isBlank()) {
                                itemContent.append("\n    ").append(line.trim());
                            }
                        }
                    } else {
                        convertElement(childElement, itemContent, baseUrl, depth + 1);
                    }
                }
            }

            output.append(itemContent.toString().trim().replace("\n", "\n    ")).append("\n");
            number++;
        }
        output.append("\n");
    }

    /**
     * {@code &lt;dl&gt;} as a term/definition list. Without this the terms and
     * their definitions ran together into one unreadable line.
     */
    private void appendDefinitionList(StringBuilder output, Element element, String baseUrl, int depth) {
        output.append("\n");
        for (Element child : element.children()) {
            String tag = child.tagName().toLowerCase();
            StringBuilder inner = new StringBuilder();
            appendInline(inner, child, baseUrl, depth + 1);
            String text = inner.toString().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (tag.equals("dt")) {
                output.append("**").append(text).append("**\n");
            } else if (tag.equals("dd")) {
                output.append(": ").append(text).append("\n");
            }
        }
        output.append("\n");
    }

    /** {@code &lt;details&gt;}: the summary is the label, the rest the content. */
    private void appendDetails(StringBuilder output, Element element, String baseUrl, int depth) {
        Element summary = element.selectFirst("summary");
        output.append("\n");
        if (summary != null) {
            StringBuilder label = new StringBuilder();
            appendInline(label, summary, baseUrl, depth + 1);
            String labelText = label.toString().trim();
            if (!labelText.isEmpty()) {
                output.append("**").append(labelText).append("**\n\n");
            }
        }
        for (Node child : element.childNodes()) {
            if (child instanceof Element childElement && !childElement.tagName().equalsIgnoreCase("summary")) {
                convertElement(childElement, output, baseUrl, depth + 1);
            } else if (child instanceof TextNode textNode) {
                output.append(normalizeWhitespace(textNode.text()));
            }
        }
        output.append("\n");
    }

    /** {@code &lt;figure&gt;}: content followed by its caption in italics. */
    private void appendFigure(StringBuilder output, Element element, String baseUrl, int depth) {
        Element caption = element.selectFirst("figcaption");
        output.append("\n");
        for (Node child : element.childNodes()) {
            if (child instanceof Element childElement && !childElement.tagName().equalsIgnoreCase("figcaption")) {
                convertElement(childElement, output, baseUrl, depth + 1);
            }
        }
        if (caption != null) {
            StringBuilder captionText = new StringBuilder();
            appendInline(captionText, caption, baseUrl, depth + 1);
            String text = captionText.toString().trim();
            if (!text.isEmpty()) {
                if (!endsWithNewline(output)) {
                    output.append("\n");
                }
                output.append("*").append(text).append("*\n");
            }
        }
        output.append("\n");
    }

    private void appendTable(StringBuilder output, Element element) {
        output.append("\n");

        Element thead = element.selectFirst("thead");
        Element tbody = element.selectFirst("tbody");

        Element headerRow = thead != null ? thead.selectFirst("tr") : element.selectFirst("tr");
        if (headerRow != null) {
            Elements headers = headerRow.select("th, td");
            if (!headers.isEmpty()) {
                appendRow(output, headers);
                output.append("| ");
                for (int i = 0; i < headers.size(); i++) {
                    output.append("--- | ");
                }
                output.append("\n");
            }
        }

        Elements rows = tbody != null ? tbody.select("tr") : element.select("tr");
        boolean skipHeaderRow = headerRow != null;
        for (Element row : rows) {
            if (skipHeaderRow && row.equals(headerRow)) {
                skipHeaderRow = false;
                continue;
            }
            Elements cells = row.select("td, th");
            if (!cells.isEmpty()) {
                appendRow(output, cells);
            }
        }

        output.append("\n");
    }

    private void appendRow(StringBuilder output, Elements cells) {
        output.append("| ");
        for (Element cell : cells) {
            output.append(escapeTableCell(cell.text())).append(" | ");
        }
        output.append("\n");
    }

    /**
     * A literal {@code |} inside a cell would end the column early and shift every
     * later value into the wrong header — the kind of corruption that is invisible
     * until an answer cites the wrong number.
     */
    private String escapeTableCell(String text) {
        if (text == null) {
            return "";
        }
        return normalizeWhitespace(text).trim().replace("|", "\\|");
    }

    private void appendLink(StringBuilder output, Element element, String baseUrl) {
        String href = element.attr("href");
        String text = element.text().trim();

        if (href.isBlank()) {
            output.append(text);
            return;
        }

        href = resolveUrl(href, baseUrl);
        if (text.equals(href)) {
            output.append("<").append(href).append(">");
        } else {
            output.append("[").append(escapeMarkdown(text)).append("](").append(href).append(")");
        }
    }

    /**
     * Images contribute only their alt text to an embedding, so a decorative image
     * with no alt is dropped entirely rather than left as a bare {@code ![](url)}
     * that costs tokens and says nothing.
     */
    private void appendImage(StringBuilder output, Element element, String baseUrl) {
        String src = element.attr("src");
        String alt = element.attr("alt");

        if (src.isBlank() || alt.isBlank()) {
            return;
        }
        output.append("![").append(escapeMarkdown(alt.trim())).append("](").append(resolveUrl(src, baseUrl)).append(")");
    }

    private void appendInline(StringBuilder output, Element element, String baseUrl, int depth) {
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode textNode) {
                output.append(normalizeWhitespace(textNode.text()));
            } else if (child instanceof Element childElement) {
                convertElement(childElement, output, baseUrl, depth + 1);
            }
        }
    }

    private void appendInlineFormatted(StringBuilder output, Element element, String baseUrl, String wrapper,
                                       int depth) {
        StringBuilder inner = new StringBuilder();
        appendInline(inner, element, baseUrl, depth + 1);
        String text = inner.toString();
        if (text.isBlank()) {
            return;
        }
        output.append(wrapper).append(text.trim()).append(wrapper);
    }

    private String resolveUrl(String url, String baseUrl) {
        if (url == null || baseUrl == null) {
            return url;
        }
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("//")) {
            return url;
        }
        try {
            return new URI(baseUrl).resolve(url).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return url;
        }
    }

    private String normalizeWhitespace(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ");
    }

    /** Keeps at most one blank line, so block separators cannot stack up. */
    private String collapseBlankLines(String text) {
        return text.replaceAll("(\\r?\\n){3,}", "\n\n");
    }

    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
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
        return text.replace("`", "\\`");
    }
}
