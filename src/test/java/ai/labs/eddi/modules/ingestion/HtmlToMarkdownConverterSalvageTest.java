/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defects carried by the draft this converter was salvaged from.
 *
 * <p>
 * Each of these silently degraded what reached the vector store rather than
 * failing loudly, which is why they survived the draft's own 46-case suite:
 * every one of those cases still passes against this implementation. Bad
 * ingestion has no stack trace — it shows up months later as an answer that
 * cites the wrong number or cannot find a page that was crawled.
 */
class HtmlToMarkdownConverterSalvageTest {

    private HtmlToMarkdownConverter converter;

    @BeforeEach
    void setUp() {
        converter = new HtmlToMarkdownConverter();
    }

    @Test
    @DisplayName("adjacent block containers do not run their text together")
    void adjacentDivsAreSeparated() {
        // The draft appended children of div/section/article with no separator, so
        // this embedded as the single token-merged word "HelloWorld".
        String result = converter.convert("<body><div>Hello</div><div>World</div></body>", null);

        assertFalse(result.contains("HelloWorld"), "adjacent divs must not merge into one word, was: " + result);
        assertTrue(result.contains("Hello") && result.contains("World"), result);
    }

    @Test
    @DisplayName("nested block containers still separate their text")
    void nestedBlocksAreSeparated() {
        String html = "<body><section><div>First</div><div><span>Second</span></div></section></body>";

        String result = converter.convert(html, null);

        assertFalse(result.contains("FirstSecond"), "was: " + result);
    }

    @Test
    @DisplayName("an article's own header survives — that is where the title lives")
    void articleHeaderIsKept() {
        // Most documentation themes put the page title in <article><header><h1>.
        // The draft stripped every <header>, deleting it.
        String html = "<body><article><header><h1>Getting Started</h1></header>"
                + "<p>Install it first.</p></article></body>";

        String result = converter.convert(html, null);

        assertTrue(result.contains("Getting Started"),
                "the article header holds the page title and must be kept, was: " + result);
    }

    @Test
    @DisplayName("the page-level site banner is still stripped")
    void pageLevelHeaderIsStripped() {
        String html = "<body><header>Site Banner Nav</header><main><p>Real content.</p></main></body>";

        String result = converter.convert(html, null);

        assertFalse(result.contains("Site Banner"), "page banner should be dropped, was: " + result);
        assertTrue(result.contains("Real content."), result);
    }

    @Test
    @DisplayName("a pipe inside a table cell is escaped so columns cannot shift")
    void tableCellPipeIsEscaped() {
        // An unescaped pipe ends the column early and shifts every later value under
        // the wrong header — corruption that surfaces only as a wrong cited number.
        String html = "<table><tr><th>Flag</th><th>Meaning</th></tr>"
                + "<tr><td>-a | -b</td><td>either</td></tr></table>";

        String result = converter.convert(html, null);

        assertTrue(result.contains("-a \\| -b"), "pipe in a cell must be escaped, was: " + result);

        String dataRow = result.lines().filter(line -> line.contains("either")).findFirst().orElse("");
        long unescapedPipes = dataRow.chars().filter(c -> c == '|').count()
                - dataRow.split("\\\\\\|", -1).length + 1;
        assertEquals(3, unescapedPipes,
                "an escaped two-column row has exactly three structural pipes, was: " + dataRow);
    }

    @Test
    @DisplayName("a multi-line code block keeps its line breaks")
    void preBlockKeepsNewlines() {
        // text() collapses whitespace, which flattened every code sample onto a
        // single line; wholeText() preserves it.
        String html = "<pre><code class=\"language-java\">int a = 1;\nint b = 2;</code></pre>";

        String result = converter.convert(html, null);

        assertTrue(result.contains("```java"), "language should be detected, was: " + result);
        assertTrue(result.contains("int a = 1;\nint b = 2;"), "code block must keep its newlines, was: " + result);
    }

    @Test
    @DisplayName("a relative link inside a heading is resolved against the base URL")
    void headingLinkResolvesBaseUrl() {
        // The draft passed null as the baseUrl when rendering headings, so links
        // inside them stayed relative and were useless as citations.
        String html = "<h2><a href=\"/docs/install\">Install</a></h2>";

        String result = converter.convert(html, "https://example.com/start");

        assertTrue(result.contains("https://example.com/docs/install"),
                "heading link should be absolute, was: " + result);
    }

    @Test
    @DisplayName("definition lists keep term and definition apart")
    void definitionListIsStructured() {
        String html = "<dl><dt>Chunk</dt><dd>A slice of a document.</dd>"
                + "<dt>Embedding</dt><dd>A vector.</dd></dl>";

        String result = converter.convert(html, null);

        assertFalse(result.contains("ChunkA slice"), "term and definition must not merge, was: " + result);
        assertTrue(result.contains("**Chunk**"), result);
        assertTrue(result.contains("A slice of a document."), result);
    }

    @Test
    @DisplayName("details/summary keeps the collapsed content and its label")
    void detailsIsExpanded() {
        String html = "<details><summary>Advanced options</summary><p>Set the flag.</p></details>";

        String result = converter.convert(html, null);

        assertTrue(result.contains("**Advanced options**"), result);
        assertTrue(result.contains("Set the flag."),
                "collapsed content is still content and must be ingested, was: " + result);
    }

    @Test
    @DisplayName("a figure caption is kept alongside its figure")
    void figureCaptionIsKept() {
        String html = "<figure><img src=\"d.png\" alt=\"Pipeline diagram\">"
                + "<figcaption>The ingestion pipeline.</figcaption></figure>";

        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("The ingestion pipeline."), result);
        assertTrue(result.contains("Pipeline diagram"), result);
    }

    @Test
    @DisplayName("chrome that carries no meaning is stripped")
    void boilerplateIsStripped() {
        String html = "<body>"
                + "<div role=\"navigation\">Home About Contact</div>"
                + "<div class=\"cookie-banner\">We use cookies</div>"
                + "<button>Subscribe</button>"
                + "<main><p>The actual documentation.</p></main>"
                + "</body>";

        String result = converter.convert(html, null);

        assertFalse(result.contains("Home About"), "nav role should be stripped, was: " + result);
        assertFalse(result.contains("We use cookies"), "cookie banner should be stripped, was: " + result);
        assertFalse(result.contains("Subscribe"), "buttons should be stripped, was: " + result);
        assertTrue(result.contains("The actual documentation."), result);
    }

    @Test
    @DisplayName("a non-positive maxLength falls back to the default instead of truncating everything")
    void nonPositiveMaxLengthFallsBack() {
        String html = "<p>" + "content ".repeat(50) + "</p>";

        String result = converter.convert(html, null, 0);

        assertTrue(result.length() > 100, "maxLength 0 must not truncate to nothing, was " + result.length());
        assertFalse(result.contains("[Content truncated"), result);
    }

    @Test
    @DisplayName("blank lines never stack up from block separators")
    void blankLinesAreCollapsed() {
        String html = "<body><div><div><p>One</p></div></div><div><p>Two</p></div></body>";

        String result = converter.convert(html, null);

        assertFalse(result.contains("\n\n\n"), "no more than one blank line, was: " + result.replace("\n", "\\n"));
    }

    @Test
    @DisplayName("an empty bold or italic wrapper is dropped rather than left as stray markers")
    void emptyInlineFormattingIsDropped() {
        String result = converter.convert("<p>Text <strong></strong> more</p>", null);

        assertFalse(result.contains("****"), "empty strong should not leave marker soup, was: " + result);
    }
}
