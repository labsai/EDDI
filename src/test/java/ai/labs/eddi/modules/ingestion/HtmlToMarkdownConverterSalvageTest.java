/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    @DisplayName("deeply nested markup does not overflow the stack")
    void deepNestingDoesNotOverflow() throws Exception {
        // The walk is recursive and the HTML is third-party — jsoup builds the full
        // DOM, 60,000 levels deep if the page says so. A StackOverflowError is an
        // Error, so it sails past every catch(Exception) in the ingestion pipeline and
        // kills the run mid-document, leaving its state-store row RUNNING and the
        // source blocked until something reaps it.
        //
        // Run on a deliberately small stack so the test is decisive rather than
        // dependent on the JVM's default: without the depth cap this overflows, with
        // it the conversion completes.
        String html = "<body>" + "<div>".repeat(20_000) + "deep content" + "</div>".repeat(20_000) + "</body>";

        assertConvertsOnSmallStack(html);
    }

    @Test
    @DisplayName("nested lists allocate what they contain, not what they contain times their depth")
    void nestedListsDoNotAllocateQuadratically() {
        // Rendering each nested level into its own buffer and re-indenting every line
        // copies the whole subtree once per level, so the allocation grows with
        // items x depth even though the final string does not. A 200 KB page of
        // nested lists measured 255 MB of heap that way, and a source may
        // legitimately fetch 5 MB.
        var threadBean = ManagementFactory.getThreadMXBean();
        assumeTrue(threadBean instanceof ThreadMXBean, "allocation counters are a HotSpot extension");
        var hotspot = (ThreadMXBean) threadBean;
        assumeTrue(hotspot.isThreadAllocatedMemorySupported(), "allocation counters are not enabled");

        int depth = 200;
        String text = "x".repeat(200);
        StringBuilder html = new StringBuilder("<body>");
        for (int i = 0; i < depth; i++) {
            html.append("<ul><li>").append(text);
        }
        for (int i = 0; i < depth; i++) {
            html.append("</li></ul>");
        }
        String page = html.append("</body>").toString();
        converter.convert(page, null, 50_000_000);

        long threadId = Thread.currentThread().threadId();
        long before = hotspot.getThreadAllocatedBytes(threadId);
        String markdown = converter.convert(page, null, 50_000_000);
        long allocated = hotspot.getThreadAllocatedBytes(threadId) - before;

        assertTrue(markdown.contains(text), "the content itself must still be there");
        // The page is about 45 KB. Linear handling costs a few megabytes including
        // jsoup's own parse; the quadratic version cost over 60 MB here.
        assertTrue(allocated < 32L * 1024 * 1024,
                "converting a 45 KB nested list allocated " + (allocated / (1024 * 1024)) + " MB, which grows with "
                        + "depth rather than with content");
    }

    @Test
    @DisplayName("deeply nested lists do not overflow the stack")
    void deepListNestingDoesNotOverflow() throws Exception {
        // A list nested in a list item recurses through appendList directly, never
        // through convertElement, so the cap there alone did not bound it.
        String html = "<body>" + "<ul><li>".repeat(20_000) + "deep content" + "</li></ul>".repeat(20_000)
                + "</body>";

        assertConvertsOnSmallStack(html);
    }

    private void assertConvertsOnSmallStack(String html) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> output = new AtomicReference<>();
        Thread worker = new Thread(null, () -> {
            try {
                output.set(converter.convert(html, null));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "deep-nesting", 256 * 1024);
        worker.start();
        worker.join(60_000);

        assertNull(failure.get(), "conversion must not blow the stack, was: " + failure.get());
        assertTrue(output.get() != null && output.get().contains("deep content"),
                "the text below the depth cap must still be collected");
    }

    @Test
    @DisplayName("truncation never splits a surrogate pair")
    void truncationKeepsSurrogatePairsIntact() {
        // A lone surrogate half is not valid text, and some embedding providers
        // reject the whole request over one.
        String emoji = "😀";
        String html = "<p>" + emoji.repeat(50) + "</p>";

        String result = converter.convert(html, null, 11);

        // Eleven characters cannot hold the truncation notice as well, so the
        // notice is dropped rather than served instead of the document.
        assertTrue(result.length() <= 11, "the cap is a cap: " + result.length());
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 1)),
                "the cut must not leave half a pair");
    }

    @Test
    @DisplayName("the truncation notice fits inside the cap rather than pushing past it")
    void truncationNoticeCountsAgainstTheCap() {
        String html = "<p>" + "word ".repeat(400) + "</p>";

        String result = converter.convert(html, null, 500);

        // WebScraperTool passes 5000 because that is what it can afford to hold.
        // Appending the notice after truncating returned more than was asked for.
        assertTrue(result.length() <= 500, "the cap is a cap: " + result.length());
        assertTrue(result.contains("[Content truncated"), result);
        assertTrue(result.indexOf("[Content truncated") > 0, "content must come before the notice");
    }

    @Test
    @DisplayName("a content wrapper keeps its own header")
    void aContentWrapperKeepsItsHeader() {
        // .content is a direct child of body, so "body > div > header" matched the
        // header holding the page's h1 and removed it — before .content was even
        // chosen as the root.
        String html = """
                <html><body>
                  <div class="content">
                    <header><h1>Guide</h1></header>
                    <p>How to do the thing.</p>
                  </div>
                </body></html>
                """;

        String markdown = converter.convert(html, null);

        assertTrue(markdown.contains("Guide"), markdown);
        assertTrue(markdown.contains("How to do the thing."), markdown);
    }

    @Test
    @DisplayName("a site banner wrapped in a div is still dropped")
    void aWrappedSiteBannerIsStillDropped() {
        // What "body > div > header" was reaching for. [role=banner] and
        // .site-header name what they are instead of guessing from depth.
        String html = """
                <html><body>
                  <div><header class="site-header"><a href="/">Acme</a></header></div>
                  <main><p>Real content.</p></main>
                </body></html>
                """;

        String markdown = converter.convert(html, null);

        assertFalse(markdown.contains("Acme"), markdown);
        assertTrue(markdown.contains("Real content."), markdown);
    }

    @Test
    @DisplayName("the main-content selectors are a priority order, not document order")
    void mainContentSelectorsAreAPriorityOrder() {
        // A .content wrapper above <main>. selectFirst answers in document order,
        // so the less specific match won and the page was rooted at the wrapper.
        String html = """
                <html><body>
                  <div class="content"><p>Sidebar blurb.</p></div>
                  <main><p>The actual article.</p></main>
                </body></html>
                """;

        String markdown = converter.convert(html, null);

        assertTrue(markdown.contains("The actual article."), markdown);
        assertFalse(markdown.contains("Sidebar blurb."), markdown);
    }

    @Test
    @DisplayName("a code span is fenced by more backticks than it contains")
    void codeSpansChooseTheirFence() {
        // Markdown does not process backslash escapes inside a code span, so
        // replacing ` with \` left the backslash in the output and ended the span
        // in the wrong place.
        String markdown = converter.convert("<p><code>a`b</code></p>", null);

        assertFalse(markdown.contains("\\`"), "a backslash escape does nothing inside a code span: " + markdown);
        assertTrue(markdown.contains("``a`b``"), markdown);
    }

    @Test
    @DisplayName("a code span that starts or ends with a backtick is padded")
    void codeSpansPadAgainstTheirFence() {
        String markdown = converter.convert("<p><code>`tick</code></p>", null);

        // Without the space the fence and the content run together and the span
        // does not parse.
        assertTrue(markdown.contains("`` `tick ``"), markdown);
    }

    @Test
    @DisplayName("a table cell keeps the markup every other context keeps")
    void tableCellsKeepTheirMarkup() {
        String html = """
                <table>
                  <tr><th>Name</th><th>Where</th></tr>
                  <tr><td><code>id</code></td><td><a href="https://example.com/docs">the docs</a></td></tr>
                </table>
                """;

        String markdown = converter.convert(html, null);

        // cell.text() kept the words and dropped everything that carries meaning:
        // a link's destination, inline code, emphasis, an image's alt text.
        assertTrue(markdown.contains("`id`"), markdown);
        assertTrue(markdown.contains("[the docs](https://example.com/docs)"), markdown);
    }

    @Test
    @DisplayName("a pipe from a rendered link cannot break the columns")
    void tableCellsEscapeWhatTheyRender() {
        String html = """
                <table><tr><td><a href="https://example.com/a|b">x</a></td><td>y</td></tr></table>
                """;

        String markdown = converter.convert(html, null);

        assertFalse(markdown.contains("example.com/a|b"), "an unescaped pipe shifts every later column: " + markdown);
    }

    @Test
    @DisplayName("a link destination with a space or unbalanced parens gets angle brackets")
    void linkDestinationsAreBracketedWhenTheyNeedIt() {
        String withSpace = converter.convert("<p><a href=\"https://example.com/a b\">x</a></p>", null);
        // A bare destination ends at the first space, so the rest spilled into the
        // text and the link pointed somewhere else.
        assertTrue(withSpace.contains("(<https://example.com/a b>)"), withSpace);

        String unbalanced = converter.convert("<p><a href=\"https://example.com/a(b\">x</a></p>", null);
        assertTrue(unbalanced.contains("(<https://example.com/a(b>)"), unbalanced);

        // A destination that needs nothing is left alone, brackets included.
        String plain = converter.convert("<p><a href=\"https://example.com/a(b)c\">x</a></p>", null);
        assertTrue(plain.contains("(https://example.com/a(b)c)"), plain);
    }

    @Test
    @DisplayName("an empty bold or italic wrapper is dropped rather than left as stray markers")
    void emptyInlineFormattingIsDropped() {
        String result = converter.convert("<p>Text <strong></strong> more</p>", null);

        assertFalse(result.contains("****"), "empty strong should not leave marker soup, was: " + result);
    }
}
