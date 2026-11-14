/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Converts HTML into Markdown suitable for embedding.
 *
 * <p>
 * Derived from the draft by nic (christoph.niederer99@gmail.com) in
 * <a href="https://github.com/labsai/EDDI/pull/529">PR #529</a>. The walk and
 * its element handling are theirs; this version fixes the defects listed in
 * HtmlToMarkdownConverterSalvageTest and adds the depth cap.
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
     * How many prefixing containers — blockquotes, and list items whose content is
     * re-indented — stack before deeper ones are written in place, without their
     * {@code > } or indent.
     *
     * <p>
     * Each renders its content into a buffer of its own and copies every line out
     * of it behind a prefix, so a line nested {@code d} deep is copied {@code d}
     * times and grows at each copy. The {@link Walk} budgets count source text and
     * elements, not prefixes, and {@link #MAX_DEPTH} still allows about a hundred
     * quote levels: a reply chain quoted that deep, or lists nested through
     * {@code li > div > ul}, cost work and output that grew with the square of the
     * depth — 340 MB for a 240 KB mail. An embedding gains nothing from a ninth
     * level of markers, and with the cap every line is copied at most this many
     * times.
     */
    private static final int MAX_PREFIXED_NESTING = 8;

    /**
     * How many elements one conversion visits before it stops. A walk that visits
     * each element once stays far below this on any real page; it exists so that a
     * shape nobody anticipated degrades into a truncated document instead of a
     * stalled worker. See {@link Walk}.
     */
    static final int MAX_ELEMENTS = 1_000_000;

    /** Text written, as a multiple of the character cap, before the walk stops. */
    private static final long TEXT_BUDGET_FACTOR = 2;
    private static final long TEXT_BUDGET_SLACK = 10_000;

    private static final Logger LOGGER = Logger.getLogger(HtmlToMarkdownConverter.class);

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
     *
     * <p>
     * Deliberately not {@code body > div > header}. A content wrapper is often a
     * direct child of body, and its own {@code <header>} holds the page's {@code
     *
    <h1>}; removing it here dropped that heading before the wrapper was even chosen
     * as the root. Site banners nested in a div are covered by
     * {@code [role=banner]} and {@code .site-header} in {@link #NOISE_SELECTOR},
     * which name what they are instead of guessing from depth.
     */
    private static final String PAGE_HEADER_SELECTOR = "body > header";

    /**
     * Where a page's real content usually lives, most specific first — and read in
     * that order.
     *
     * <p>
     * As one comma-separated selector it was not: {@code selectFirst} answers in
     * document order, so a page with a {@code <div class="content">} wrapper above
     * its {@code <main>} was rooted at the wrapper, which is the less specific
     * match. Each entry is now tried in turn.
     */
    private static final List<String> MAIN_CONTENT_SELECTORS = List.of("main", "article", "#content", "#main", ".content", ".main-content");

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
        Document doc = Jsoup.parse(html);
        // Page mode is conservative: script reveals what a page hides with
        // display:none, hidden or opacity (accordions, tabs, "read more"), and that
        // is content a knowledge base wants. Only comments, 1px text, transparent
        // text and explicitly same-coloured text go.
        HiddenContentFilter.removeHidden(doc, HiddenContentFilter.Mode.PAGE);
        return convertDocument(doc, baseUrl, maxLength, true);
    }

    /**
     * Converts the HTML body of an e-mail.
     *
     * <p>
     * The same walk as {@link #convert(String, String, int)}, with three
     * differences that follow from mail being written by a stranger rather than
     * published as a page: the hidden-content pass runs in its strict
     * {@link HiddenContentFilter.Mode#MAIL} mode — mail runs no script, so
     * {@code display:none} and the like are never revealed, and the white
     * background and black text every mail client renders on are assumed; the walk
     * is not scoped to {@code main}/{@code article}, since a mail template's
     * "content" wrapper does not make the greeting above it chrome; and the
     * {@code <title>}, which mail clients never show, is not emitted.
     *
     * @param html
     *            the mail's HTML part, already stripped of quoted history if the
     *            caller wants that
     * @param maxLength
     *            character cap; non-positive falls back to the default
     */
    public String convertMail(String html, int maxLength) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return convertMail(Jsoup.parse(html), maxLength);
    }

    /**
     * {@link #convertMail(String, int)} on an already parsed document, which it
     * modifies.
     */
    public String convertMail(Document doc, int maxLength) {
        HiddenContentFilter.removeHidden(doc, HiddenContentFilter.Mode.MAIL);
        return convertDocument(doc, null, maxLength, false);
    }

    private String convertDocument(Document doc, String baseUrl, int maxLength, boolean page) {
        int cap = maxLength > 0 ? maxLength : DEFAULT_MAX_LENGTH;

        doc.select(PAGE_HEADER_SELECTOR).remove();
        doc.select(NOISE_SELECTOR).remove();

        Element root = doc.body();
        if (root == null) {
            return "";
        }

        if (page) {
            Element main = firstMatch(doc);
            if (main != null) {
                root = main;
            }
        }

        String title = page ? doc.title() : null;
        StringBuilder markdown = new StringBuilder();
        if (title != null && !title.isBlank() && !containsTitle(root, title)) {
            markdown.append("# ").append(escapeMarkdown(title)).append("\n\n");
        }

        convertElement(root, markdown, new Walk(baseUrl, cap), 0);

        String result = collapseBlankLines(markdown.toString()).trim();
        if (result.length() > cap) {
            // The notice counts against the cap. Appending it afterwards returned
            // more characters than the caller asked for — WebScraperTool passes
            // 5000 precisely because that is what it can afford to hold.
            String notice = "\n\n[Content truncated - exceeded " + cap + " character limit]";
            // Unless the cap is too small to hold the notice and anything else,
            // in which case the notice is dropped rather than served instead of
            // the content: a caller that asks for 11 characters wants 11
            // characters of document, not 51 characters of apology.
            boolean roomForNotice = cap - notice.length() > 0;
            int room = roomForNotice ? cap - notice.length() : cap;
            // Never cut between the halves of a surrogate pair: a lone half is not
            // valid text, and some embedding providers reject the whole request.
            int end = Character.isHighSurrogate(result.charAt(room - 1)) ? room - 1 : room;
            result = result.substring(0, end) + (roomForNotice ? notice : "");
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

    private void convertElement(Element element, StringBuilder output, Walk walk, int depth) {
        if (!walk.visit()) {
            return;
        }
        if (depth > MAX_DEPTH) {
            // Stop descending rather than overflowing the stack. The text below is
            // still collected, just without further structure.
            appendFlattened(output, element, walk);
            return;
        }
        String tagName = element.tagName().toLowerCase();

        switch (tagName) {
            case "h1" -> appendHeading(output, element, 1, walk, depth);
            case "h2" -> appendHeading(output, element, 2, walk, depth);
            case "h3" -> appendHeading(output, element, 3, walk, depth);
            case "h4" -> appendHeading(output, element, 4, walk, depth);
            case "h5" -> appendHeading(output, element, 5, walk, depth);
            case "h6" -> appendHeading(output, element, 6, walk, depth);
            case "p" -> appendParagraph(output, element, walk, depth);
            case "pre" -> appendPreBlock(output, element, walk);
            case "blockquote" -> appendBlockquote(output, element, walk, depth);
            case "ul" -> appendList(output, element, walk, false, depth);
            case "ol" -> appendList(output, element, walk, true, depth);
            case "dl" -> appendDefinitionList(output, element, walk, depth);
            case "table" -> appendTable(output, element, walk, depth);
            case "details" -> appendDetails(output, element, walk, depth);
            case "figure" -> appendFigure(output, element, walk, depth);
            case "hr" -> output.append("\n---\n\n");
            case "br" -> output.append("\n");
            // Block containers: a separator is required, otherwise adjacent blocks
            // run together and "<div>Hello</div><div>World</div>" embeds as
            // "HelloWorld" — a real defect for div-soup pages.
            case "div", "section", "article", "main", "body", "li", "dd", "dt", "header" ->
                appendBlock(output, element, walk, depth);
            case "span", "label", "small", "time", "cite", "abbr" -> appendInline(output, element, walk, depth);
            case "a" -> appendLink(output, element, walk);
            case "img" -> appendImage(output, element, walk);
            case "strong", "b" -> appendInlineFormatted(output, element, walk, "**", depth);
            case "em", "i" -> appendInlineFormatted(output, element, walk, "*", depth);
            case "code" -> appendInlineCode(output, walk.charge(element.text()));
            case "del", "s", "strike" -> appendInlineFormatted(output, element, walk, "~~", depth);
            case "sub" -> appendInlineFormatted(output, element, walk, "~", depth);
            case "sup" -> appendInlineFormatted(output, element, walk, "^", depth);
            default -> appendChildren(output, element, walk, depth);
        }
    }

    /**
     * Appends a block container's children, guaranteeing a line break on each side
     * so neighbouring blocks stay separate words.
     */
    private void appendBlock(StringBuilder output, Element element, Walk walk, int depth) {
        int before = output.length();
        StringBuilder inner = new StringBuilder();
        appendChildren(inner, element, walk, depth + 1);

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

    private void appendChildren(StringBuilder output, Element element, Walk walk, int depth) {
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode textNode) {
                appendText(output, textNode, walk);
            } else if (child instanceof Element childElement) {
                convertElement(childElement, output, walk, depth + 1);
            }
        }
    }

    private void appendHeading(StringBuilder output, Element element, int level, Walk walk, int depth) {
        output.append("\n").append("#".repeat(level)).append(" ");
        appendInline(output, element, walk, depth + 1);
        output.append("\n\n");
    }

    private void appendParagraph(StringBuilder output, Element element, Walk walk, int depth) {
        output.append("\n");
        appendInline(output, element, walk, depth + 1);
        output.append("\n\n");
    }

    private void appendPreBlock(StringBuilder output, Element element, Walk walk) {
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
        output.append("\n").append(stripTrailingNewline(walk.charge(source.wholeText())));
        output.append("\n```\n\n");
    }

    /**
     * The text without its trailing line breaks, copied once.
     *
     * <p>
     * Trimming one character per {@code substring} copied the remaining text for
     * every break, which is quadratic: a {@code
     *
     *

    <pre>
     * } ending in a few million newlines — a 5 MB page can hold that — spent
     * minutes copying before the output cap was ever consulted.
     */
    static String stripTrailingNewline(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
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

    private void appendBlockquote(StringBuilder output, Element element, Walk walk, int depth) {
        if (walk.prefixNesting >= MAX_PREFIXED_NESTING) {
            // Past the cap the quote keeps its content and loses its marker, written
            // in place rather than through a buffer of its own.
            appendInPlace(output, element, walk, depth);
            return;
        }
        StringBuilder inner = new StringBuilder();
        walk.prefixNesting++;
        appendChildren(inner, element, walk, depth + 1);
        walk.prefixNesting--;

        output.append("\n");
        for (String line : inner.toString().split("\\r?\\n")) {
            if (!line.isBlank()) {
                output.append("> ").append(line.trim()).append("\n");
            }
        }
        output.append("\n");
    }

    private void appendList(StringBuilder output, Element element, Walk walk, boolean ordered, int depth) {
        appendList(output, element, walk, ordered, depth, 0);
    }

    /**
     * A list, indenting nested levels as it writes them.
     *
     * <p>
     * The obvious implementation renders a nested list into its own buffer and then
     * re-indents every line of it — which copies the whole subtree once per level,
     * so output grows with items × depth and the copies with it. A 200 KB page of
     * nested lists measured 255 MB of heap that way, and the 5 MB a source may
     * legitimately fetch would be gigabytes: an OutOfMemoryError from a page the
     * operator does not control. Writing the indent directly keeps it linear.
     *
     * @param indentLevel
     *            how deep this list is nested, in list levels rather than in
     *            elements
     */
    private void appendList(StringBuilder output, Element element, Walk walk, boolean ordered, int depth,
                            int indentLevel) {

        if (indentLevel > 0 && !walk.visit()) {
            // A nested list arrives here directly rather than through
            // convertElement, so it is charged to the budget here.
            return;
        }
        if (depth > MAX_DEPTH) {
            // A nested list recurses here directly, never through convertElement, so
            // it needs the same bound or a page of nested lists overflows the stack.
            appendFlattened(output, element, walk);
            return;
        }
        if (indentLevel == 0) {
            output.append("\n");
        }
        String indent = "    ".repeat(indentLevel);
        int number = 1;
        for (Element item : element.children()) {
            if (!item.tagName().equalsIgnoreCase("li")) {
                continue;
            }
            if (!walk.visit()) {
                // An item is an element too, and one written here never passes through
                // convertElement: count it, or a page of empty items escapes the budget.
                break;
            }

            output.append(indent).append(ordered ? number + "." : "-").append(" ");
            List<Element> nestedLists = new ArrayList<>();
            if (walk.prefixNesting < MAX_PREFIXED_NESTING) {
                // The item's own content, without its directly nested lists: the only
                // thing that needs re-indenting. A list nested through another element
                // (li > div > ul) is part of it, hence the cap.
                StringBuilder itemContent = new StringBuilder();
                walk.prefixNesting++;
                appendItemContent(itemContent, item, nestedLists, walk, depth);
                walk.prefixNesting--;
                output.append(itemContent.toString().trim().replace("\n", "\n" + indent + "    ")).append("\n");
            } else {
                appendItemContent(output, item, nestedLists, walk, depth);
                if (!endsWithNewline(output)) {
                    output.append("\n");
                }
            }

            for (Element nested : nestedLists) {
                appendList(output, nested, walk, nested.tagName().equalsIgnoreCase("ol"), depth + 1,
                        indentLevel + 1);
            }
            number++;
        }
        if (indentLevel == 0) {
            output.append("\n");
        }
    }

    /**
     * Writes a list item's content to {@code output}, collecting its directly
     * nested lists into {@code nestedLists} instead.
     */
    private void appendItemContent(StringBuilder output, Element item, List<Element> nestedLists, Walk walk,
                                   int depth) {
        for (Node child : item.childNodes()) {
            if (child instanceof TextNode textNode) {
                appendText(output, textNode, walk);
            } else if (child instanceof Element childElement) {
                String childTag = childElement.tagName().toLowerCase();
                if (childTag.equals("ul") || childTag.equals("ol")) {
                    nestedLists.add(childElement);
                } else {
                    convertElement(childElement, output, walk, depth + 1);
                }
            }
        }
    }

    /**
     * A block's children written straight into {@code output}, on lines of their
     * own: {@link #appendBlock} without its buffer, for a container past
     * {@link #MAX_PREFIXED_NESTING}.
     */
    private void appendInPlace(StringBuilder output, Element element, Walk walk, int depth) {
        if (!endsWithNewline(output)) {
            output.append("\n");
        }
        appendChildren(output, element, walk, depth + 1);
        if (!endsWithNewline(output)) {
            output.append("\n");
        }
    }

    /**
     * {@code &lt;dl&gt;} as a term/definition list. Without this the terms and
     * their definitions ran together into one unreadable line.
     */
    private void appendDefinitionList(StringBuilder output, Element element, Walk walk, int depth) {
        output.append("\n");
        for (Element child : element.children()) {
            String tag = child.tagName().toLowerCase();
            StringBuilder inner = new StringBuilder();
            appendInline(inner, child, walk, depth + 1);
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
    private void appendDetails(StringBuilder output, Element element, Walk walk, int depth) {
        // A direct child only. A descendant search found a nested <details>' summary
        // too, rendered it here and again where it sits — and a summary holding
        // another such structure doubled the work at every level.
        Element summary = firstChild(element, "summary");
        output.append("\n");
        if (summary != null) {
            StringBuilder label = new StringBuilder();
            appendInline(label, summary, walk, depth + 1);
            String labelText = label.toString().trim();
            if (!labelText.isEmpty()) {
                output.append("**").append(labelText).append("**\n\n");
            }
        }
        for (Node child : element.childNodes()) {
            if (child instanceof Element childElement && !childElement.tagName().equalsIgnoreCase("summary")) {
                convertElement(childElement, output, walk, depth + 1);
            } else if (child instanceof TextNode textNode) {
                appendText(output, textNode, walk);
            }
        }
        output.append("\n");
    }

    /** {@code &lt;figure&gt;}: content followed by its caption in italics. */
    private void appendFigure(StringBuilder output, Element element, Walk walk, int depth) {
        // A direct child only, for the same reason as appendDetails' summary: a
        // nested caption was rendered both as this caption and in place.
        Element caption = firstChild(element, "figcaption");
        output.append("\n");
        for (Node child : element.childNodes()) {
            if (child instanceof Element childElement && !childElement.tagName().equalsIgnoreCase("figcaption")) {
                convertElement(childElement, output, walk, depth + 1);
            }
        }
        if (caption != null) {
            StringBuilder captionText = new StringBuilder();
            appendInline(captionText, caption, walk, depth + 1);
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

    /**
     * A table, reading only the rows and cells that belong to it.
     *
     * <p>
     * {@code select("tr")} and {@code select("td, th")} are descendant searches:
     * they also returned the rows and cells of every table nested inside a cell,
     * each of which was then rendered again as part of the cell that holds it.
     * Every level of nesting doubled the work, and the cells restarted the depth
     * count at zero, so {@link #MAX_DEPTH} never stopped it — twenty nested tables,
     * a few hundred bytes, kept a worker busy for minutes. A table owns the
     * {@code tr}s that are its children or children of its own {@code thead},
     * {@code tbody} and {@code tfoot}, and a row owns its child cells.
     */
    private void appendTable(StringBuilder output, Element element, Walk walk, int depth) {
        output.append("\n");

        List<Element> rows = new ArrayList<>();
        Element headerRow = null;
        for (Element child : element.children()) {
            String tag = child.tagName().toLowerCase();
            if (tag.equals("tr")) {
                rows.add(child);
            } else if (tag.equals("thead") || tag.equals("tbody") || tag.equals("tfoot")) {
                for (Element row : child.children()) {
                    if (row.tagName().equalsIgnoreCase("tr")) {
                        if (headerRow == null && tag.equals("thead")) {
                            headerRow = row;
                        }
                        rows.add(row);
                    }
                }
            }
        }
        if (headerRow == null && !rows.isEmpty()) {
            headerRow = rows.get(0);
        }

        if (headerRow != null) {
            List<Element> headers = cellsOf(headerRow);
            if (!headers.isEmpty()) {
                appendRow(output, headers, walk, depth);
                output.append("| ");
                for (int i = 0; i < headers.size(); i++) {
                    output.append("--- | ");
                }
                output.append("\n");
            }
        }

        for (Element row : rows) {
            if (row == headerRow) {
                continue;
            }
            if (walk.exhausted()) {
                break;
            }
            List<Element> cells = cellsOf(row);
            if (!cells.isEmpty()) {
                appendRow(output, cells, walk, depth);
            }
        }

        output.append("\n");
    }

    private static List<Element> cellsOf(Element row) {
        List<Element> cells = new ArrayList<>();
        for (Element child : row.children()) {
            String tag = child.tagName().toLowerCase();
            if (tag.equals("td") || tag.equals("th")) {
                cells.add(child);
            }
        }
        return cells;
    }

    /**
     * A row, with each cell rendered the way the same content would be rendered
     * anywhere else.
     *
     * <p>
     * {@code cell.text()} kept the words and dropped everything that carries
     * meaning: a link's destination, an image's alt text, inline code, emphasis,
     * and the line breaks a {@code <br>
     * } stands for. A table is where a page puts its most structured facts, so it
     * was the worst place to flatten.
     *
     * <p>
     * The cells continue the depth of the table they sit in. Starting them at zero
     * let nested tables escape {@link #MAX_DEPTH}.
     */
    private void appendRow(StringBuilder output, List<Element> cells, Walk walk, int depth) {
        output.append("| ");
        for (Element cell : cells) {
            StringBuilder rendered = new StringBuilder();
            appendInline(rendered, cell, walk, depth + 2);
            // escapeTableCell still runs over the result: it collapses the newlines
            // a <br> produced and escapes any pipe, whether it came from the text
            // or from a rendered link.
            output.append(escapeTableCell(rendered.toString())).append(" | ");
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

    private void appendLink(StringBuilder output, Element element, Walk walk) {
        String href = element.attr("href");
        String text = walk.charge(element.text().trim());

        if (href.isBlank()) {
            output.append(text);
            return;
        }

        href = resolveUrl(href, walk.baseUrl);
        if (href == null) {
            // A javascript:, data: or otherwise unusable destination: the visible text
            // stays, the attribute — text no reader of the page ever saw — does not.
            output.append(text);
            return;
        }
        if (text.equals(href)) {
            output.append("<").append(href.replace("<", "%3C").replace(">", "%3E")).append(">");
        } else {
            output.append("[").append(escapeMarkdown(text)).append("](").append(asDestination(href)).append(")");
        }
    }

    /**
     * A link destination, in angle brackets when it needs them.
     *
     * <p>
     * A bare destination ends at the first space, and its parentheses have to
     * balance. A URL containing either — which query strings and Wikipedia-style
     * paths both produce — silently truncated the link and spilled the rest into
     * the text.
     */
    private static String asDestination(String href) {
        if (href == null || href.isEmpty()) {
            return "";
        }
        if (!href.contains(" ") && parenthesesBalance(href)) {
            return href;
        }
        // Angle brackets take anything but a literal < or >, which cannot appear in
        // a URL that has been through resolveUrl anyway.
        return "<" + href.replace("<", "%3C").replace(">", "%3E") + ">";
    }

    private static boolean parenthesesBalance(String href) {
        int depth = 0;
        for (int i = 0; i < href.length(); i++) {
            char character = href.charAt(i);
            if (character == '(') {
                depth++;
            } else if (character == ')' && --depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    /**
     * Images contribute only their alt text to an embedding, so a decorative image
     * with no alt is dropped entirely rather than left as a bare {@code ![](url)}
     * that costs tokens and says nothing.
     */
    private void appendImage(StringBuilder output, Element element, Walk walk) {
        String src = element.attr("src");
        String alt = element.attr("alt");

        if (src.isBlank() || alt.isBlank()) {
            return;
        }
        String destination = resolveUrl(src, walk.baseUrl);
        if (destination == null) {
            // The alt text is what an image contributes; an unusable source adds nothing.
            output.append(escapeMarkdown(alt.trim()));
            return;
        }
        // Through asDestination like a link's: a ')' in a raw src closed the image
        // early and spilled the rest of the attribute into the chunk as text.
        output.append("![").append(escapeMarkdown(alt.trim())).append("](").append(asDestination(destination)).append(")");
    }

    private void appendInline(StringBuilder output, Element element, Walk walk, int depth) {
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode textNode) {
                appendText(output, textNode, walk);
            } else if (child instanceof Element childElement) {
                convertElement(childElement, output, walk, depth + 1);
            }
        }
    }

    private void appendInlineFormatted(StringBuilder output, Element element, Walk walk, String wrapper,
                                       int depth) {
        StringBuilder inner = new StringBuilder();
        appendInline(inner, element, walk, depth + 1);
        String text = inner.toString();
        if (text.isBlank()) {
            return;
        }
        output.append(wrapper).append(text.trim()).append(wrapper);
    }

    /**
     * Longest link or image destination kept; anything longer is not a citation.
     */
    static final int MAX_DESTINATION_LENGTH = 2048;

    /**
     * A link or image destination fit for a RAG chunk: an {@code http},
     * {@code https} or {@code mailto} URL after resolution against the base, at
     * most {@value #MAX_DESTINATION_LENGTH} characters — or null. A
     * {@code javascript:} or {@code data:} URL, a relative one with no base to
     * resolve it against, and arbitrary text hidden in the attribute used to reach
     * the chunk, and from there the model's context.
     */
    private String resolveUrl(String url, String baseUrl) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DESTINATION_LENGTH) {
            return null;
        }
        String resolved;
        if (hasSafeScheme(trimmed)) {
            resolved = trimmed;
        } else if (baseUrl == null) {
            // No base (an uploaded file): a relative link stays relative, as the
            // extractor promises — but only a real relative reference, never prose
            // or a javascript:/data: URL hidden in the attribute.
            return isSafeRelativeReference(trimmed) ? trimmed : null;
        } else {
            try {
                resolved = new URI(baseUrl).resolve(trimmed).toString();
            } catch (URISyntaxException | IllegalArgumentException e) {
                return null;
            }
        }
        return hasSafeScheme(resolved) && resolved.length() <= MAX_DESTINATION_LENGTH ? resolved : null;
    }

    /**
     * A relative reference kept as written when there is no base to resolve it
     * against: it parses as a URI with no scheme and no authority (so neither
     * {@code javascript:} nor {@code //host}), holds no whitespace — attribute
     * prose has spaces, a path does not — and no {@code <}, {@code >} or backslash.
     */
    static boolean isSafeRelativeReference(String reference) {
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '<' || c == '>' || c == '\\') {
                return false;
            }
        }
        try {
            URI uri = new URI(reference);
            return uri.getScheme() == null && uri.getRawAuthority() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean hasSafeScheme(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
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

    /**
     * A code span, fenced by more backticks than it contains.
     *
     * <p>
     * Backslash-escaping was wrong: Markdown does not process escapes inside a code
     * span, so {@code a\`b} reached the reader with the backslash in it and the
     * span ended at the wrong place. CommonMark's rule is to use a longer fence,
     * and to pad with a space when the content starts or ends with a backtick so
     * the fence and the content do not run together.
     */
    private void appendInlineCode(StringBuilder output, String text) {
        String code = text == null ? "" : text;
        String fence = "`".repeat(longestBacktickRun(code) + 1);
        boolean pad = code.startsWith("`") || code.endsWith("`");
        output.append(fence);
        if (pad) {
            output.append(' ');
        }
        output.append(code);
        if (pad) {
            output.append(' ');
        }
        output.append(fence);
    }

    private static int longestBacktickRun(String text) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            current = text.charAt(i) == '`' ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return longest;
    }

    private void appendText(StringBuilder output, TextNode textNode, Walk walk) {
        output.append(walk.charge(normalizeWhitespace(textNode.text())));
    }

    /** An element's text without structure, for when the walk stops descending. */
    private void appendFlattened(StringBuilder output, Element element, Walk walk) {
        output.append(walk.charge(normalizeWhitespace(element.text())));
    }

    /** The first child element with the given tag, ignoring case, or null. */
    private static Element firstChild(Element parent, String tag) {
        for (Element child : parent.children()) {
            if (child.tagName().equalsIgnoreCase(tag)) {
                return child;
            }
        }
        return null;
    }

    /**
     * The state of one conversion: the base URL, the work budget, and how many
     * prefixing containers are open.
     *
     * <p>
     * The structural fixes make the walk linear, and this is the backstop that
     * keeps it so whatever shape the next unforeseen input takes. Two allowances,
     * both checked while the walk is still running rather than after it has built
     * its output:
     * <ul>
     * <li>{@link #MAX_ELEMENTS} elements visited, however they are reached;</li>
     * <li>text written, bounded by a multiple of the caller's character cap. Once
     * the output is certain to be truncated anyway, rendering the rest of a 5 MB
     * page only to discard it is wasted work. The margin covers whitespace and
     * markup the final pass trims away.</li>
     * </ul>
     * When either runs out, the walk stops adding to the output and unwinds; what
     * has been written so far is kept and truncated as usual.
     */
    private static final class Walk {
        final String baseUrl;
        private final long maxTextChars;
        private int elements;
        private long textChars;
        private boolean exhausted;
        /**
         * Prefixing containers currently open; see {@link #MAX_PREFIXED_NESTING}.
         */
        private int prefixNesting;

        Walk(String baseUrl, int cap) {
            this.baseUrl = baseUrl;
            this.maxTextChars = TEXT_BUDGET_FACTOR * cap + TEXT_BUDGET_SLACK;
        }

        /** Counts one element; false once the budget is spent. */
        boolean visit() {
            if (exhausted) {
                return false;
            }
            if (++elements > MAX_ELEMENTS) {
                exhausted = true;
                LOGGER.debugf("HTML conversion stopped after %d elements", MAX_ELEMENTS);
                return false;
            }
            return true;
        }

        /** Charges text about to be written; empty once the budget is spent. */
        String charge(String text) {
            if (exhausted) {
                return "";
            }
            // Whitespace-only text is not charged. An indented page has a whitespace
            // node between every pair of tags and most never reach the output — a
            // blank block or an empty inline is dropped — so charging them stopped a
            // long page with a small cap before its first paragraph. How many there
            // can be is bounded by MAX_ELEMENTS anyway.
            if (!text.isBlank()) {
                textChars += text.length();
            }
            if (textChars > maxTextChars) {
                // This text still goes out: it is what pushed the output past the
                // point where it will be truncated. Nothing after it does.
                exhausted = true;
            }
            return text;
        }

        boolean exhausted() {
            return exhausted;
        }
    }

    /** The first of {@link #MAIN_CONTENT_SELECTORS} that matches, in that order. */
    private static Element firstMatch(Document doc) {
        for (String selector : MAIN_CONTENT_SELECTORS) {
            Element found = doc.selectFirst(selector);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
