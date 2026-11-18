/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.llm.tools.ToolFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The scraper must not present a mis-read page as the page (review P3):
 * relative links resolve against the fetched URL, the declared charset decodes
 * the body, and a body that is not text is refused.
 */
class WebScraperToolAnswersTest {

    private OutboundHttp http;
    private WebScraperTool tool;

    @BeforeEach
    void setUp() {
        http = mock(OutboundHttp.class);
        tool = new WebScraperTool(http, new HtmlToMarkdownConverter());
    }

    private void answer(String finalUri, Map<String, List<String>> headers, byte[] body) throws Exception {
        when(http.send(any(OutboundRequest.class), any()))
                .thenReturn(new OutboundResponse<>(200, null, OutboundRequest.caseInsensitiveCopy(headers), URI.create(finalUri), List.of(), body,
                        false));
    }

    @Test
    void relativeLinksResolveAgainstTheFetchedUrlAfterRedirects() throws Exception {
        answer("https://docs.example.org/guide/index.html", Map.of(),
                "<html><body><a href='/about'>About</a><a href='intro.html'>Intro</a><a href='https://x.test/abs'>Abs</a></body></html>"
                        .getBytes(StandardCharsets.UTF_8));

        String result = tool.extractLinks("https://example.com/start", 10);

        assertTrue(result.contains("https://docs.example.org/about"), result);
        assertTrue(result.contains("https://docs.example.org/guide/intro.html"), result);
        assertTrue(result.contains("https://x.test/abs"), result);
        assertFalse(result.contains("No links found"), result);
    }

    @Test
    void declaredCharsetDecodesTheBody() throws Exception {
        String page = "<html><head><title>Grüße</title></head><body><p>Café über</p></body></html>";
        answer("https://example.test/", Map.of("Content-Type", List.of("text/html; charset=ISO-8859-1")),
                page.getBytes(Charset.forName("ISO-8859-1")));

        String result = tool.extractWebPageText("https://example.com");

        assertTrue(result.contains("Café über"), result);
    }

    @Test
    void metaCharsetIsHonouredWhenTheHeaderDeclaresNone() throws Exception {
        String page = "<html><head><meta charset=\"windows-1252\"><title>t</title></head><body><p>naïve été</p></body></html>";
        answer("https://example.test/", Map.of(), page.getBytes(Charset.forName("windows-1252")));

        assertTrue(tool.extractWebPageText("https://example.com").contains("naïve été"));
    }

    @Test
    void undeclaredCharsetStaysUtf8() throws Exception {
        answer("https://example.test/", Map.of(), "<html><body><p>中文</p></body></html>".getBytes(StandardCharsets.UTF_8));

        assertTrue(tool.extractWebPageText("https://example.com").contains("中文"));
    }

    @Test
    void nonTextContentIsRefusedWithAClearMessage() throws Exception {
        answer("https://example.test/file.pdf", Map.of("Content-Type", List.of("application/pdf")), new byte[]{'%', 'P', 'D', 'F', 0, 1, 2});

        ToolFailureException refused = assertThrows(ToolFailureException.class, () -> tool.extractWebPageText("https://example.com/file.pdf"));

        assertTrue(refused.getMessage().startsWith("Error:"), refused.getMessage());
        assertTrue(refused.getMessage().contains("application/pdf"), refused.getMessage());
    }

    @Test
    void aBaseHrefDecidesWhereRelativeLinksPoint() throws Exception {
        answer("https://example.test/page", Map.of(),
                "<html><head><base href='https://cdn.example/docs/'></head><body><a href='a.html'>A</a></body></html>"
                        .getBytes(StandardCharsets.UTF_8));

        assertTrue(tool.extractLinks("https://example.com/page", 10).contains("https://cdn.example/docs/a.html"));
    }

    @Test
    void aByteOrderMarkBeatsTheHeaderAndIsNotPartOfTheText() {
        byte[] utf8WithBom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertEquals(StandardCharsets.UTF_8, WebScraperTool.charsetOf("text/html; charset=ISO-8859-1", utf8WithBom));
        assertEquals(3, WebScraperTool.bomLength(utf8WithBom, StandardCharsets.UTF_8));
        byte[] utf16le = {(byte) 0xFF, (byte) 0xFE, 'h', 0};
        assertEquals(StandardCharsets.UTF_16LE, WebScraperTool.charsetOf("text/html; charset=utf-8", utf16le));
        assertEquals(2, WebScraperTool.bomLength(utf16le, StandardCharsets.UTF_16LE));
    }

    @Test
    void metaSniffingReadsOnlyRealDeclarations() {
        byte[] description = "<meta name='description' content='set charset=windows-1252 here'><p>x</p>".getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, WebScraperTool.charsetOf(null, description), "text inside another attribute is not a declaration");
        byte[] httpEquiv = "<meta http-equiv='Content-Type' content='text/html; charset=windows-1252'>".getBytes(StandardCharsets.UTF_8);
        assertEquals(Charset.forName("windows-1252"), WebScraperTool.charsetOf(null, httpEquiv));
        byte[] utf16 = "<meta charset=\"utf-16\">".getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, WebScraperTool.charsetOf(null, utf16), "a page that wrote its <meta> in ASCII is not UTF-16");
        byte[] late = (" ".repeat(2000) + "<meta charset='windows-1252'>").getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, WebScraperTool.charsetOf(null, late), "only the first 1024 bytes are sniffed");
    }

    @Test
    void aCommentedOutMetaCharsetIsNoDeclaration() throws Exception {
        String page = "<html><head><!-- <meta charset=\"windows-1252\"> --><title>t</title></head><body><p>Café</p></body></html>";
        answer("https://example.test/", Map.of(), page.getBytes(StandardCharsets.UTF_8));

        assertTrue(tool.extractWebPageText("https://example.com").contains("Café"));
        byte[] afterComment = "<!-- old --><meta charset='windows-1252'>".getBytes(StandardCharsets.UTF_8);
        assertEquals(Charset.forName("windows-1252"), WebScraperTool.charsetOf(null, afterComment), "a real declaration after a comment");
        byte[] unterminated = "<!-- <meta charset='windows-1252'>".getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, WebScraperTool.charsetOf(null, unterminated), "an unterminated comment runs to the end");
    }

    @Test
    void aCutThroughAMultiByteCharacterIsDroppedInAnyCharset() {
        byte[] utf16 = "ab".getBytes(StandardCharsets.UTF_16LE);
        byte[] cut = Arrays.copyOf(utf16, utf16.length + 1);
        assertEquals("ab", WebScraperTool.decodeComplete(cut, 0, StandardCharsets.UTF_16LE));
        byte[] euro = "a€".getBytes(StandardCharsets.UTF_8);
        assertEquals("a", WebScraperTool.decodeComplete(Arrays.copyOf(euro, 3), 0, StandardCharsets.UTF_8));
    }

    @Test
    void anOctetStreamThatIsMarkupIsRead() throws Exception {
        answer("https://example.test/", Map.of("Content-Type", List.of("application/octet-stream")),
                "  <html><body><p>mislabelled page</p></body></html>".getBytes(StandardCharsets.UTF_8));
        assertTrue(tool.extractWebPageText("https://example.com").contains("mislabelled page"));
        assertTrue(WebScraperTool.nonTextRefusal("application/octet-stream", new byte[]{0, 1, 2}).startsWith("Error:"));
    }

    @Test
    void contentTypePolicy() {
        assertNull(WebScraperTool.nonTextRefusal(null));
        assertNull(WebScraperTool.nonTextRefusal("text/html; charset=utf-8"));
        assertNull(WebScraperTool.nonTextRefusal("application/xhtml+xml"));
        assertNull(WebScraperTool.nonTextRefusal("application/ld+json"));
        assertEquals(StandardCharsets.UTF_8, WebScraperTool.charsetOf("text/html; charset=nonsense-charset", new byte[0]));
        assertTrue(WebScraperTool.nonTextRefusal("image/png").contains("image/png"));
        assertTrue(WebScraperTool.nonTextRefusal("application/octet-stream").startsWith("Error:"));
    }
}
