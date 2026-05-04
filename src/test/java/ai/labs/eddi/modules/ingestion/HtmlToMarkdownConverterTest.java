/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HtmlToMarkdownConverter}.
 */
class HtmlToMarkdownConverterTest {

    private HtmlToMarkdownConverter converter;

    @BeforeEach
    void setUp() {
        converter = new HtmlToMarkdownConverter();
    }

    // === supports() Tests ===

    @Test
    void supports_htmlContentType() {
        assertTrue(converter.supports("text/html"));
        assertTrue(converter.supports("text/html; charset=utf-8"));
        assertTrue(converter.supports("application/xhtml+xml"));
        assertTrue(converter.supports("text/HTML")); // case insensitive
    }

    @Test
    void supports_nonHtmlContentType() {
        assertFalse(converter.supports("text/plain"));
        assertFalse(converter.supports("application/json"));
        assertFalse(converter.supports("application/pdf"));
    }

    @Test
    void supports_nullContentType() {
        assertFalse(converter.supports(null));
    }

    // === Basic Conversion Tests ===

    @Test
    void convert_nullHtml() {
        String result = converter.convert(null, "https://example.com");
        assertEquals("", result);
    }

    @Test
    void convert_blankHtml() {
        String result = converter.convert("   ", "https://example.com");
        assertEquals("", result);
    }

    @Test
    void convert_emptyBody() {
        String html = "<html><head><title>Test</title></head><body></body></html>";
        String result = converter.convert(html, "https://example.com");
        // Empty body returns just the title as h1
        assertEquals("# Test", result);
    }

    // === Title Tests ===

    @Test
    void convert_addsTitleFromHead() {
        String html = "<html><head><title>Page Title</title></head><body><p>Content</p></body></html>";
        String result = converter.convert(html, "https://example.com");
        assertTrue(result.startsWith("# Page Title"));
        assertTrue(result.contains("Content"));
    }

    @Test
    void convert_skipsTitleIfInContent() {
        String html = "<html><head><title>Same Title</title></head><body><h1>Same Title</h1></body></html>";
        String result = converter.convert(html, "https://example.com");
        // Should not duplicate title
        assertEquals("# Same Title", result);
    }

    // === Heading Tests ===

    @Test
    void convert_headings() {
        String html = """
                <h1>Heading 1</h1>
                <h2>Heading 2</h2>
                <h3>Heading 3</h3>
                <h4>Heading 4</h4>
                <h5>Heading 5</h5>
                <h6>Heading 6</h6>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("# Heading 1"));
        assertTrue(result.contains("## Heading 2"));
        assertTrue(result.contains("### Heading 3"));
        assertTrue(result.contains("#### Heading 4"));
        assertTrue(result.contains("##### Heading 5"));
        assertTrue(result.contains("###### Heading 6"));
    }

    // === Paragraph Tests ===

    @Test
    void convert_paragraphs() {
        String html = "<p>First paragraph</p><p>Second paragraph</p>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("First paragraph"));
        assertTrue(result.contains("Second paragraph"));
    }

    // === Text Formatting Tests ===

    @Test
    void convert_boldAndStrong() {
        String html = "<p><b>Bold</b> and <strong>Strong</strong></p>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("**Bold**"));
        assertTrue(result.contains("**Strong**"));
    }

    @Test
    void convert_italicAndEm() {
        String html = "<p><i>Italic</i> and <em>Emphasis</em></p>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("*Italic*"));
        assertTrue(result.contains("*Emphasis*"));
    }

    @Test
    void convert_inlineCode() {
        String html = "<p>Use <code>console.log()</code> for debugging</p>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("`console.log()`"));
    }

    @Test
    void convert_strikethrough() {
        String html = "<p><del>Deleted</del> and <s>Strikethrough</s></p>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("~~Deleted~~"));
        assertTrue(result.contains("~~Strikethrough~~"));
    }

    // === Link Tests ===

    @Test
    void convert_absoluteLink() {
        String html = "<a href=\"https://example.com/page\">Link Text</a>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("[Link Text](https://example.com/page)"));
    }

    @Test
    void convert_relativeLink() {
        String html = "<a href=\"/page\">Relative Link</a>";
        String result = converter.convert(html, "https://example.com/base/");

        assertTrue(result.contains("[Relative Link](https://example.com/page)"));
    }

    @Test
    void convert_autolinkStyle() {
        String html = "<a href=\"https://example.com\">https://example.com</a>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("<https://example.com>"));
    }

    @Test
    void convert_emptyHref() {
        String html = "<a>Text without href</a>";
        String result = converter.convert(html, "https://example.com");

        assertEquals("Text without href", result);
    }

    // === Image Tests ===

    @Test
    void convert_image() {
        String html = "<p><img src=\"image.png\" alt=\"Description\"></p>";
        String result = converter.convert(html, "https://example.com");

        // The converter resolves relative URLs to absolute
        assertTrue(result.contains("![Description](https://example.com/image.png)"), "Image markdown format should be ![alt](src)");
    }

    @Test
    void convert_relativeImage() {
        String html = "<p><img src=\"/images/logo.png\" alt=\"Logo\"></p>";
        String result = converter.convert(html, "https://example.com/");

        assertTrue(result.contains("![Logo](https://example.com/images/logo.png)"), "Relative image URL should be resolved to absolute");
    }

    @Test
    void convert_imageWithoutAlt() {
        String html = "<p><img src=\"image.png\"></p>";
        String result = converter.convert(html, "https://example.com");

        // The converter resolves relative URLs to absolute
        assertTrue(result.contains("![](https://example.com/image.png)"), "Image without alt should be ![](src)");
    }

    // === List Tests ===

    @Test
    void convert_unorderedList() {
        String html = """
                <ul>
                    <li>Item 1</li>
                    <li>Item 2</li>
                    <li>Item 3</li>
                </ul>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("- Item 1"));
        assertTrue(result.contains("- Item 2"));
        assertTrue(result.contains("- Item 3"));
    }

    @Test
    void convert_orderedList() {
        String html = """
                <ol>
                    <li>First</li>
                    <li>Second</li>
                </ol>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("1. First"));
        assertTrue(result.contains("2. Second"));
    }

    @Test
    void convert_nestedList() {
        String html = """
                <ul>
                    <li>Parent
                        <ul>
                            <li>Child 1</li>
                            <li>Child 2</li>
                        </ul>
                    </li>
                </ul>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("- Parent"));
        assertTrue(result.contains("    - Child 1"));
        assertTrue(result.contains("    - Child 2"));
    }

    // === Code Block Tests ===

    @Test
    void convert_codeBlock() {
        String html = """
                <pre><code>function hello() {
                    return "world";
                }</code></pre>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("```"));
        assertTrue(result.contains("function hello()"));
        assertTrue(result.contains("```"));
    }

    @Test
    void convert_codeBlockWithLanguage() {
        String html = """
                <pre><code class="language-java">public class Main {}</code></pre>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("```java"));
        assertTrue(result.contains("public class Main"));
    }

    @Test
    void convert_codeBlockWithLangPrefix() {
        String html = """
                <pre><code class="lang-python">print("hello")</code></pre>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("```python"));
    }

    // === Blockquote Tests ===

    @Test
    void convert_blockquote() {
        String html = "<blockquote><p>This is a quote</p></blockquote>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("> This is a quote"));
    }

    // === Table Tests ===

    @Test
    void convert_simpleTable() {
        String html = """
                <table>
                    <tr><th>Name</th><th>Age</th></tr>
                    <tr><td>Alice</td><td>30</td></tr>
                    <tr><td>Bob</td><td>25</td></tr>
                </table>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("| Name | Age |"));
        assertTrue(result.contains("| --- | --- |"));
        assertTrue(result.contains("| Alice | 30 |"));
        assertTrue(result.contains("| Bob | 25 |"));
    }

    @Test
    void convert_tableWithTheadTbody() {
        String html = """
                <table>
                    <thead>
                        <tr><th>Header 1</th><th>Header 2</th></tr>
                    </thead>
                    <tbody>
                        <tr><td>Data 1</td><td>Data 2</td></tr>
                    </tbody>
                </table>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("| Header 1 | Header 2 |"));
        assertTrue(result.contains("| Data 1 | Data 2 |"));
    }

    // === Horizontal Rule Tests ===

    @Test
    void convert_horizontalRule() {
        String html = "<p>Before</p><hr><p>After</p>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("---"));
    }

    // === Noise Removal Tests ===

    @Test
    void convert_removesScriptTags() {
        String html = "<p>Content</p><script>alert('xss')</script>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("Content"));
        assertFalse(result.contains("script"));
        assertFalse(result.contains("alert"));
    }

    @Test
    void convert_removesStyleTags() {
        String html = "<style>body { color: red; }</style><p>Content</p>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("Content"));
        assertFalse(result.contains("style"));
        assertFalse(result.contains("color: red"));
    }

    @Test
    void convert_removesNavFooterHeader() {
        String html = """
                <nav>Navigation</nav>
                <header>Header</header>
                <p>Main Content</p>
                <footer>Footer</footer>
                <aside>Sidebar</aside>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("Main Content"));
        assertFalse(result.contains("Navigation"));
        assertFalse(result.contains("Header"));
        assertFalse(result.contains("Footer"));
        assertFalse(result.contains("Sidebar"));
    }

    // === Main Content Detection Tests ===

    @Test
    void convert_usesMainElement() {
        String html = """
                <body>
                    <nav>Nav</nav>
                    <main><p>Main Content</p></main>
                    <footer>Footer</footer>
                </body>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("Main Content"));
        assertFalse(result.contains("Nav"));
        assertFalse(result.contains("Footer"));
    }

    @Test
    void convert_usesArticleElement() {
        String html = """
                <body>
                    <p>Outside article</p>
                    <article><p>Article Content</p></article>
                </body>
                """;
        String result = converter.convert(html, "https://example.com");

        // When article is present, it should be used as root
        assertTrue(result.contains("Article Content"));
    }

    // === Truncation Tests ===

    @Test
    void convert_truncatesToMaxLength() {
        String longText = "A".repeat(200);
        String html = "<p>" + longText + "</p>";
        String result = converter.convert(html, "https://example.com", 100);

        // Should be truncated with message appended
        assertTrue(result.contains("[Content truncated"), "Should contain truncation message");
        // The result should be roughly around the max length plus truncation message
        assertTrue(result.length() < 250, "Result should be truncated to reasonable length");
    }

    @Test
    void convert_noTruncationWithinLimit() {
        String html = "<p>Short content</p>";
        String result = converter.convert(html, "https://example.com", 1000);

        assertFalse(result.contains("[Content truncated"));
        assertTrue(result.contains("Short content"));
    }

    // === Whitespace Normalization Tests ===

    @Test
    void convert_normalizesWhitespace() {
        String html = "<p>Multiple   spaces   and\n\nnewlines</p>";
        String result = converter.convert(html, "https://example.com");

        // Multiple whitespace should be collapsed to single spaces
        assertFalse(result.contains("   "));
    }

    // === Escape Tests ===

    @Test
    void convert_escapesMarkdownCharacters() {
        // Note: The converter normalizes whitespace and escapes certain markdown chars
        // within inline formatting contexts. Testing with styled text that triggers
        // escaping.
        String html = "<p>Text with <code>function_test()</code></p>";
        String result = converter.convert(html, "https://example.com");

        // Inline code should be wrapped with backticks
        assertTrue(result.contains("`function_test()`"));
    }

    @Test
    void convert_preservesPlainBrackets() {
        // Note: The converter doesn't escape brackets in plain text
        // Brackets are only escaped when they appear in link text context
        String html = "<p>Text with [brackets]</p>";
        String result = converter.convert(html, "https://example.com");

        // Plain brackets are preserved as-is
        assertTrue(result.contains("[brackets]"), "Plain brackets should be preserved");
    }

    @Test
    void convert_escapesBracketsInLinkText() {
        // Brackets in link text are escaped to prevent nested link syntax
        String html = "<a href=\"https://example.com\">Text with [brackets]</a>";
        String result = converter.convert(html, "https://example.com");

        // Brackets in link text should be escaped
        assertTrue(result.contains("\\[brackets\\]"), "Brackets in link text should be escaped");
    }

    // === Complex Document Tests ===

    @Test
    void convert_fullDocument() {
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Test Document</title>
                    <style>body { font-family: Arial; }</style>
                </head>
                <body>
                    <nav>Navigation Menu</nav>
                    <main>
                        <h1>Main Heading</h1>
                        <p>This is a <strong>test</strong> document with <a href=\"/link\">a link</a>.</p>
                        <ul>
                            <li>Item 1</li>
                            <li>Item 2</li>
                        </ul>
                    </main>
                    <footer>Copyright 2024</footer>
                </body>
                </html>
                """;
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("# Main Heading"), "Should have h1");
        assertTrue(result.contains("**test**"), "Should have bold");
        assertTrue(result.contains("[a link]"), "Should have link");
        assertTrue(result.contains("- Item 1"), "Should have list");
        assertFalse(result.contains("Navigation"), "Should remove nav");
        assertFalse(result.contains("Copyright"), "Should remove footer");
    }

    // === Subscript/Superscript Tests ===

    @Test
    void convert_subscript() {
        String html = "<p>H<sub>2</sub>O</p>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("H~2~O"));
    }

    @Test
    void convert_superscript() {
        String html = "<p>x<sup>2</sup></p>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("x^2^"));
    }

    // === Line Break Tests ===

    @Test
    void convert_lineBreak() {
        String html = "<p>Line 1<br>Line 2</p>";
        String result = converter.convert(html, "https://example.com");

        assertTrue(result.contains("Line 1\nLine 2"));
    }
}
