/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hidden-content pass: text a reader never sees never reaches the model.
 * Each case is a real injection carrier; each has a visible twin that must
 * survive.
 */
class HiddenContentFilterTest {

    private final HtmlToMarkdownConverter converter = new HtmlToMarkdownConverter();

    private String mail(String body) {
        return converter.convertMail("<html><body><p>VISIBLE</p>" + body + "</body></html>", 0);
    }

    private String page(String body) {
        return converter.convert("<html><body><p>VISIBLE</p>" + body + "</body></html>", null, 0);
    }

    @Nested
    @DisplayName("element-level hiding, in mail and on pages")
    class ElementLevel {

        @ParameterizedTest
        @ValueSource(strings = {
                "<div style=\"display:none\">INJECTED</div>",
                "<div style=\"DISPLAY : None !important\">INJECTED</div>",
                "<div style=\"opacity:0\">INJECTED</div>",
                "<div style=\"opacity:0.01\">INJECTED</div>",
                "<div style=\"mso-hide:all\">INJECTED</div>",
                "<span style=\"max-height:0;overflow:hidden\">INJECTED</span>",
                "<span style=\"width:0px;overflow:hidden\">INJECTED</span>",
                "<div style=\"position:absolute;left:-9999px\">INJECTED</div>",
                "<div style=\"position:fixed;top:-5000px\">INJECTED</div>",
                "<div style=\"text-indent:-9999px\">INJECTED</div>",
                "<div style=\"clip:rect(0,0,0,0);position:absolute\">INJECTED</div>",
                "<div style=\"transform:scale(0)\">INJECTED</div>",
                "<div hidden>INJECTED</div>",
                "<!-- INJECTED -->",
                "<style>.a{display:none}</style><div class=\"a\">INJECTED</div>",
                "<style>#b, .c { visibility: hidden }</style><p id=\"b\">INJECTED</p>",
                "<div style=\"display:none\"><p style=\"display:block\">INJECTED</p></div>"})
        @DisplayName("is removed")
        void removed(String html) {
            String mail = mail(html);
            String page = page(html);

            assertFalse(mail.contains("INJECTED"), mail);
            assertFalse(page.contains("INJECTED"), page);
            assertTrue(mail.contains("VISIBLE"));
            assertTrue(page.contains("VISIBLE"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "<div style=\"display:block\">KEPT</div>",
                "<div style=\"opacity:0.9\">KEPT</div>",
                "<div style=\"position:absolute;left:10px\">KEPT</div>",
                "<div style=\"height:0\">KEPT</div>",
                "<style>@media (max-width:600px){ .m{display:none} }</style><div class=\"m\">KEPT</div>",
                "<style>.d:hover{display:none}</style><div class=\"d\">KEPT</div>"})
        @DisplayName("look-alikes that a reader does see are kept")
        void kept(String html) {
            assertTrue(mail(html).contains("KEPT"), mail(html));
            assertTrue(page(html).contains("KEPT"), page(html));
        }
    }

    @Nested
    @DisplayName("text-level hiding")
    class TextLevel {

        @ParameterizedTest
        @ValueSource(strings = {
                "<p style=\"font-size:0\">INJECTED</p>",
                "<p style=\"font-size:1px\">INJECTED</p>",
                "<p style=\"font-size:0.5pt\">INJECTED</p>",
                "<div style=\"font-size:0\"><span>INJECTED</span></div>",
                "<div style=\"visibility:hidden\"><span>INJECTED</span></div>",
                "<p style=\"color:transparent\">INJECTED</p>",
                "<p style=\"color:rgba(0,0,0,0)\">INJECTED</p>",
                "<div style=\"background-color:#336699\"><span style=\"color:#336699\">INJECTED</span></div>",
                "<div style=\"background:#123456 url(x.png) no-repeat\"><span style=\"color:#123457\">INJECTED</span></div>",
                "<table bgcolor=\"black\"><tr><td><font color=\"#000\">INJECTED</font></td></tr></table>",
                "<div style=\"background:rgb(10, 20, 30)\"><p style=\"color:rgb(12,20,30)\">INJECTED</p></div>"})
        @DisplayName("is removed whenever the background is stated")
        void removed(String html) {
            assertFalse(mail(html).contains("INJECTED"), mail(html));
            assertFalse(page(html).contains("INJECTED"), page(html));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "<div style=\"font-size:0\"><span style=\"font-size:14px\">KEPT</span></div>",
                "<div style=\"visibility:hidden\"><span style=\"visibility:visible\">KEPT</span></div>",
                "<div style=\"background:#000\"><span style=\"color:#fff\">KEPT</span></div>",
                "<p style=\"font-size:0.8em\">KEPT</p>",
                "<p style=\"color:#336699\">KEPT</p>"})
        @DisplayName("an override or a contrasting colour keeps the text")
        void kept(String html) {
            assertTrue(mail(html).contains("KEPT"), mail(html));
            assertTrue(page(html).contains("KEPT"), page(html));
        }

        @Test
        @DisplayName("white text with no stated background: hidden in mail, kept on a web page")
        void whiteOnUnknown() {
            String html = "<p style=\"color:#ffffff\">WHITE</p><p><font color=\"white\">WHITE2</font></p>";

            assertFalse(mail(html).contains("WHITE"), "mail clients render on white");
            assertTrue(page(html).contains("WHITE"), "a page's background usually comes from a stylesheet");
            assertTrue(page(html).contains("WHITE2"));
        }

        @Test
        @DisplayName("black on black from a bgcolor: hidden in mail by default text colour too")
        void defaultTextOnBlack() {
            assertFalse(mail("<table bgcolor=\"#000000\"><tr><td>DARK</td></tr></table>").contains("DARK"));
            assertTrue(mail("<table bgcolor=\"#000000\"><tr><td style=\"color:#eee\">LIGHT</td></tr></table>").contains("LIGHT"));
        }
    }

    @Test
    @DisplayName("the count reports what was removed, and a deep document does not overflow the stack")
    void countAndDepth() {
        var doc = Jsoup.parse("<div style=\"display:none\">a</div><!-- b --><p style=\"font-size:0\">c</p>");
        assertEquals(3, HiddenContentFilter.removeHidden(doc, false));

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            deep.append("<div style=\"color:#111\">");
        }
        deep.append("text");
        HiddenContentFilter.removeHidden(Jsoup.parse(deep.toString()), true);
    }

    @Test
    @DisplayName("@media blocks are removed with their nested rules; statements are skipped")
    void atBlocks() {
        assertEquals(" .a{display:none}", HiddenContentFilter.removeAtBlocks("@import url(x.css); .a{display:none}@media print{.b{color:red}}"));
    }
}
