/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import ai.labs.eddi.modules.ingestion.crawl.WebCrawler.Sitemap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every sitemap form {@link WebCrawler#parseSitemap} reads — and the lookalikes
 * it must not mistake for pages.
 */
class SitemapParsingTest {

    private static final String BASE = "https://docs.example.com";
    private static final String NS = "http://www.sitemaps.org/schemas/sitemap/0.9";

    private static Sitemap parse(String body) throws IOException {
        return WebCrawler.parseSitemap(body.getBytes(StandardCharsets.UTF_8), "UTF-8", BASE + "/sitemap.xml");
    }

    /**
     * Built from bytes: a unicode escape here would be turned into the raw
     * character by the formatter.
     */
    private static byte[] withUtf8Bom(byte[] body) {
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        return withBom;
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(plain);
        }
        return out.toByteArray();
    }

    private static String urlset(String... locs) {
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset xmlns=\"" + NS + "\">");
        for (String loc : locs) {
            xml.append("<url><loc>").append(loc).append("</loc></url>");
        }
        return xml.append("</urlset>").toString();
    }

    @Nested
    @DisplayName("the sitemap protocol")
    class Protocol {

        @Test
        @DisplayName("a urlset lists pages")
        void urlsetListsPages() throws IOException {
            Sitemap sitemap = parse(urlset(BASE + "/a", BASE + "/b"));

            assertEquals(List.of(BASE + "/a", BASE + "/b"), sitemap.pageUrls());
            assertTrue(sitemap.childSitemaps().isEmpty());
        }

        @Test
        @DisplayName("a sitemap index lists sitemaps, not pages")
        void sitemapIndex() throws IOException {
            Sitemap sitemap = parse("<?xml version=\"1.0\"?><sitemapindex xmlns=\"" + NS + "\">"
                    + "<sitemap><loc>" + BASE + "/sitemap-pages.xml</loc><lastmod>2026-01-01</lastmod></sitemap>"
                    + "<sitemap><loc>" + BASE + "/sitemap-blog.xml.gz</loc></sitemap>"
                    + "</sitemapindex>");

            assertTrue(sitemap.pageUrls().isEmpty(), sitemap.pageUrls().toString());
            assertEquals(List.of(BASE + "/sitemap-pages.xml", BASE + "/sitemap-blog.xml.gz"), sitemap.childSitemaps());
        }

        @Test
        @DisplayName("image, video and news extension <loc>s are not pages")
        void extensionLocsAreNotPages() throws IOException {
            // Each extension nests its own <loc> (image:loc, video:content_loc ...)
            // inside <url>. Reading every <loc> turned every image into a "page".
            Sitemap sitemap = parse("<urlset xmlns=\"" + NS + "\""
                    + " xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\""
                    + " xmlns:video=\"http://www.google.com/schemas/sitemap-video/1.1\""
                    + " xmlns:news=\"http://www.google.com/schemas/sitemap-news/0.9\">"
                    + "<url><loc>" + BASE + "/article</loc>"
                    + "<image:image><image:loc>" + BASE + "/img/hero.png</image:loc></image:image>"
                    + "<video:video><video:content_loc>" + BASE + "/v.mp4</video:content_loc>"
                    + "<video:player_loc>" + BASE + "/player</video:player_loc></video:video>"
                    + "<news:news><news:title>t</news:title></news:news>"
                    + "</url></urlset>");

            assertEquals(List.of(BASE + "/article"), sitemap.pageUrls());
        }

        @Test
        @DisplayName("hreflang alternates are not followed — the scope decides languages, not the sitemap")
        void hreflangAlternatesAreNotPages() throws IOException {
            Sitemap sitemap = parse("<urlset xmlns=\"" + NS + "\" xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">"
                    + "<url><loc>" + BASE + "/en/page</loc>"
                    + "<xhtml:link rel=\"alternate\" hreflang=\"de\" href=\"" + BASE + "/de/page\"/></url></urlset>");

            assertEquals(List.of(BASE + "/en/page"), sitemap.pageUrls());
        }

        @Test
        @DisplayName("a namespace prefix on the sitemap elements is ignored")
        void prefixedElements() throws IOException {
            Sitemap sitemap = parse("<sm:urlset xmlns:sm=\"" + NS + "\">"
                    + "<sm:url><sm:loc>" + BASE + "/a</sm:loc></sm:url></sm:urlset>");

            assertEquals(List.of(BASE + "/a"), sitemap.pageUrls());
        }

        @Test
        @DisplayName("entities, CDATA and surrounding whitespace are decoded")
        void escapingAndWhitespace() throws IOException {
            Sitemap sitemap = parse("<urlset xmlns=\"" + NS + "\">"
                    + "<url><loc>\n   " + BASE + "/search?q=a&amp;page=2   \n</loc></url>"
                    + "<url><loc><![CDATA[" + BASE + "/cdata?x=1&y=2]]></loc></url>"
                    + "</urlset>");

            assertEquals(List.of(BASE + "/search?q=a&page=2", BASE + "/cdata?x=1&y=2"), sitemap.pageUrls());
        }

        @Test
        @DisplayName("only absolute http(s) URLs are taken")
        void onlyAbsoluteHttp() throws IOException {
            Sitemap sitemap = parse(urlset("/relative", "ftp://docs.example.com/f", "javascript:alert(1)", "", BASE + "/ok"));

            assertEquals(List.of(BASE + "/ok"), sitemap.pageUrls());
        }

        @Test
        @DisplayName("a URL listed twice is taken once")
        void duplicates() throws IOException {
            assertEquals(List.of(BASE + "/a"), parse(urlset(BASE + "/a", BASE + "/a")).pageUrls());
        }

        @Test
        @DisplayName("no more than MAX_SITEMAP_URLS are taken from one sitemap")
        void urlCap() throws IOException {
            String[] locs = new String[5_100];
            for (int i = 0; i < locs.length; i++) {
                locs[i] = BASE + "/p" + i;
            }
            assertEquals(5_000, parse(urlset(locs)).pageUrls().size());
        }

        @Test
        @DisplayName("something that is not a sitemap yields nothing, not an exception")
        void garbage() throws IOException {
            assertTrue(parse("<html><body><a href=\"" + BASE + "/a\">a</a></body></html>").pageUrls().isEmpty());
            assertTrue(parse("").pageUrls().isEmpty());
            assertTrue(parse("<urlset><url><loc>" + BASE + "/unterminated").pageUrls().size() <= 1);
        }
    }

    @Nested
    @DisplayName("encodings")
    class Encodings {

        @Test
        @DisplayName("a UTF-8 byte-order mark before the declaration")
        void utf8Bom() throws IOException {
            byte[] body = withUtf8Bom(urlset(BASE + "/a").getBytes(StandardCharsets.UTF_8));

            assertEquals(List.of(BASE + "/a"), WebCrawler.parseSitemap(body, null, BASE + "/sitemap.xml").pageUrls());
        }

        @Test
        @DisplayName("UTF-16 with a byte-order mark")
        void utf16() throws IOException {
            byte[] body = urlset(BASE + "/a").replace("UTF-8", "UTF-16").getBytes(StandardCharsets.UTF_16);

            assertEquals(List.of(BASE + "/a"), WebCrawler.parseSitemap(body, null, BASE + "/sitemap.xml").pageUrls());
        }

        @Test
        @DisplayName("non-ASCII paths survive")
        void nonAsciiPaths() throws IOException {
            assertEquals(List.of(BASE + "/über-uns"), parse(urlset(BASE + "/über-uns")).pageUrls());
        }
    }

    @Nested
    @DisplayName("gzip")
    class Gzip {

        @Test
        @DisplayName("a gzipped urlset is read whatever the Content-Type")
        void gzippedUrlset() throws IOException {
            byte[] body = gzip(urlset(BASE + "/a").getBytes(StandardCharsets.UTF_8));

            assertEquals(List.of(BASE + "/a"), WebCrawler.parseSitemap(body, null, BASE + "/sitemap.xml.gz").pageUrls());
        }

        @Test
        @DisplayName("a gzipped index")
        void gzippedIndex() throws IOException {
            byte[] body = gzip(("<sitemapindex xmlns=\"" + NS + "\"><sitemap><loc>" + BASE + "/s1.xml.gz</loc></sitemap>"
                    + "</sitemapindex>").getBytes(StandardCharsets.UTF_8));

            assertEquals(List.of(BASE + "/s1.xml.gz"),
                    WebCrawler.parseSitemap(body, "ISO-8859-1", BASE + "/sitemap_index.xml.gz").childSitemaps());
        }

        @Test
        @DisplayName("a gzip bomb is read only up to the decompression cap")
        void decompressionIsCapped() throws IOException {
            // ~20 MB of padding compresses to a few kilobytes. The URL before it is
            // still found; the rest is never inflated.
            String head = "<urlset xmlns=\"" + NS + "\"><url><loc>" + BASE + "/first</loc></url><!--";
            byte[] padding = new byte[WebCrawler.MAX_DECOMPRESSED_SITEMAP_BYTES + 4 * 1024 * 1024];
            Arrays.fill(padding, (byte) ' ');
            var plain = new ByteArrayOutputStream();
            plain.write(head.getBytes(StandardCharsets.UTF_8));
            plain.write(padding);
            plain.write(("--><url><loc>" + BASE + "/after-the-cap</loc></url></urlset>").getBytes(StandardCharsets.UTF_8));
            byte[] body = gzip(plain.toByteArray());
            assertTrue(body.length < 1024 * 1024, "the compressed body fits the fetch cap: " + body.length);

            Sitemap sitemap = WebCrawler.parseSitemap(body, null, BASE + "/sitemap.xml.gz");

            assertEquals(List.of(BASE + "/first"), sitemap.pageUrls());
        }
    }

    @Nested
    @DisplayName("text sitemaps and feeds")
    class TextAndFeeds {

        @Test
        @DisplayName("a text sitemap is one URL per line; blank lines, CRLF and other lines are skipped")
        void textSitemap() throws IOException {
            Sitemap sitemap = parse(BASE + "/a\r\n\r\n  " + BASE + "/b  \n# a comment\nnot a url\n" + BASE + "/c");

            assertEquals(List.of(BASE + "/a", BASE + "/b", BASE + "/c"), sitemap.pageUrls());
        }

        @Test
        @DisplayName("a text sitemap with a byte-order mark and a declared charset")
        void textSitemapBomAndCharset() throws IOException {
            byte[] body = withUtf8Bom((BASE + "/café").getBytes(StandardCharsets.UTF_8));
            assertEquals(List.of(BASE + "/café"),
                    WebCrawler.parseSitemap(body, "UTF-8", BASE + "/sitemap.txt").pageUrls());

            byte[] latin1 = (BASE + "/café").getBytes(Charset.forName("ISO-8859-1"));
            assertEquals(List.of(BASE + "/café"),
                    WebCrawler.parseSitemap(latin1, "ISO-8859-1", BASE + "/sitemap.txt").pageUrls());
        }

        @Test
        @DisplayName("an RSS 2.0 feed lists its items' links, not the channel's")
        void rss() throws IOException {
            Sitemap sitemap = parse("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
                    + "<link>" + BASE + "/</link><title>Blog</title>"
                    + "<item><title>One</title><link>" + BASE + "/blog/one</link></item>"
                    + "<item><title>Two</title><link>" + BASE + "/blog/two</link></item>"
                    + "</channel></rss>");

            assertEquals(List.of(BASE + "/blog/one", BASE + "/blog/two"), sitemap.pageUrls());
        }

        @Test
        @DisplayName("an Atom feed lists its entries' alternate links, not self or edit links")
        void atom() throws IOException {
            Sitemap sitemap = parse("<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\">"
                    + "<link rel=\"self\" href=\"" + BASE + "/feed.atom\"/>"
                    + "<entry><link href=\"" + BASE + "/blog/one\"/></entry>"
                    + "<entry><link rel=\"alternate\" href=\"" + BASE + "/blog/two\"/>"
                    + "<link rel=\"edit\" href=\"" + BASE + "/api/edit/2\"/></entry>"
                    + "</feed>");

            assertEquals(List.of(BASE + "/blog/one", BASE + "/blog/two"), sitemap.pageUrls());
        }
    }
}
