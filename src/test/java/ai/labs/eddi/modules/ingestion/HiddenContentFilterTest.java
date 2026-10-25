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

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hidden-content pass: in mail, text a reader never sees never reaches the
 * model; on pages, content that script reveals is kept and only what has no
 * legitimate page use goes.
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
    @DisplayName("mail: element-level hiding")
    class MailElementLevel {

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
                "<div style=\"display:none\"><p style=\"display:block\">INJECTED</p></div>",
                "<div style=\"visibility:hidden\"><span>INJECTED</span></div>"})
        @DisplayName("is removed in mail")
        void removed(String html) {
            String mail = mail(html);

            assertFalse(mail.contains("INJECTED"), mail);
            assertTrue(mail.contains("VISIBLE"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "<style>.a{display:none}</style><div class=\"a\">INJECTED</div>",
                "<style>#b, .c { visibility: hidden }</style><p id=\"b\">INJECTED</p>",
                "<style>DIV.Wrap > SPAN.x{display:none}</style><div class=\"wrap\"><span class=\"x\">INJECTED</span></div>",
                "<style>td .y:first-child{display:none}</style><table><tr><td><span class=\"y\">INJECTED</span></td></tr></table>",
                "<style>.\\61 bc{display:none}</style><p class=\"abc\">INJECTED</p>",
                "<style>p[data-x]{display:none}</style><p data-x=\"1\">INJECTED</p>",
                "<style>/* a */ .z /* b */ { /* c */ display : none /* d */ }</style><p class=\"z\">INJECTED</p>",
                "<style>@import url(x.css); @media print { .p { color: red } } .q{display:none}</style><p class=\"q\">INJECTED</p>"})
        @DisplayName("style-block rules are applied, over-approximating where exactness would cost more")
        void stylesheet(String html) {
            assertFalse(mail(html).contains("INJECTED"), mail(html));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "<div style=\"display:block\">KEPT</div>",
                "<div style=\"opacity:0.9\">KEPT</div>",
                "<div style=\"position:absolute;left:10px\">KEPT</div>",
                "<div style=\"height:0\">KEPT</div>",
                "<style>@media (max-width:600px){ .m{display:none} }</style><div class=\"m\">KEPT</div>",
                "<style>.d:hover{display:none}</style><div class=\"d\">KEPT</div>",
                "<style>.e::before{display:none}</style><div class=\"e\">KEPT</div>",
                "<style>.f{display:block}</style><div class=\"f\">KEPT</div>",
                "<div style=\"visibility:hidden\"><span style=\"visibility:visible\">KEPT</span></div>"})
        @DisplayName("look-alikes a mail reader does see are kept")
        void kept(String html) {
            assertTrue(mail(html).contains("KEPT"), mail(html));
        }
    }

    @Nested
    @DisplayName("pages: script-revealed content survives")
    class PageMode {

        @Test
        @DisplayName("an accordion hidden by display:none and a class rule")
        void accordion() {
            String html = "<style>.faq-answer{display:none}.open .faq-answer{display:block}</style>"
                    + "<div class=\"faq\"><h3>How do I reset my password?</h3><div class=\"faq-answer\" style=\"display:none\">"
                    + "ACCORDION-ANSWER</div></div>";

            assertTrue(page(html).contains("ACCORDION-ANSWER"), page(html));
        }

        @Test
        @DisplayName("a tab panel with the hidden attribute")
        void hiddenTabPanel() {
            String html = "<div role=\"tabpanel\" id=\"tab-2\" hidden><p>TAB-PANEL-TWO</p></div>";

            assertTrue(page(html).contains("TAB-PANEL-TWO"), page(html));
        }

        @Test
        @DisplayName("a fade-in-on-scroll block starting at opacity:0")
        void fadeIn() {
            String html = "<section class=\"reveal\" style=\"opacity:0;transform:translateY(20px)\"><p>FADE-IN-CONTENT</p></section>";

            assertTrue(page(html).contains("FADE-IN-CONTENT"), page(html));
        }

        @Test
        @DisplayName("screen-reader-only text, inline or by class")
        void srOnly() {
            String html = "<style>.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}</style>"
                    + "<span class=\"sr-only\">SR-ONLY-CLASS</span>"
                    + "<span style=\"position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0)\">SR-ONLY-INLINE</span>"
                    + "<a href=\"#main\" style=\"position:absolute;left:-10000px\">SKIP-LINK-TEXT</a>"
                    + "<div style=\"visibility:hidden\">TOGGLED-VISIBILITY</div>";

            String out = page(html);
            assertTrue(out.contains("SR-ONLY-CLASS"), out);
            assertTrue(out.contains("SR-ONLY-INLINE"), out);
            assertTrue(out.contains("TOGGLED-VISIBILITY"), out);
            assertTrue(out.contains("SKIP-LINK-TEXT"), out);
        }

        @Test
        @DisplayName("white text with no stated background is kept: a page's background comes from its stylesheet")
        void whiteOnUnknown() {
            assertTrue(page("<p style=\"color:#ffffff\">WHITE</p>").contains("WHITE"));
            assertFalse(mail("<p style=\"color:#ffffff\">WHITE</p>").contains("WHITE"), "mail clients render on white");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "<!-- INJECTED -->",
                "<p style=\"font-size:0\">INJECTED</p>",
                "<p style=\"font-size:0px\">INJECTED</p>",
                "<p style=\"font-size:1px\">INJECTED</p>",
                "<p style=\"font-size:0.5pt\">INJECTED</p>",
                "<div style=\"font-size:0\"><span>INJECTED</span></div>",
                "<p style=\"color:transparent\">INJECTED</p>",
                "<p style=\"color:rgba(0,0,0,0)\">INJECTED</p>",
                "<div style=\"background-color:#336699\"><span style=\"color:#336699\">INJECTED</span></div>",
                "<div style=\"background:#123456 url(x.png) no-repeat\"><span style=\"color:#123457\">INJECTED</span></div>",
                "<table bgcolor=\"black\"><tr><td><font color=\"#000\">INJECTED</font></td></tr></table>",
                "<div style=\"background:rgb(10, 20, 30)\"><p style=\"color:rgb(12,20,30)\">INJECTED</p></div>"})
        @DisplayName("comments, 1px text, transparent text and explicitly same-coloured text still go — in both modes")
        void stillRemoved(String html) {
            assertFalse(page(html).contains("INJECTED"), page(html));
            assertFalse(mail(html).contains("INJECTED"), mail(html));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "<div style=\"font-size:0\"><span style=\"font-size:14px\">KEPT</span></div>",
                "<div style=\"background:#000\"><span style=\"color:#fff\">KEPT</span></div>",
                "<p style=\"font-size:0.8em\">KEPT</p>",
                "<p style=\"color:#336699\">KEPT</p>"})
        @DisplayName("an override or a contrasting colour keeps the text, in both modes")
        void kept(String html) {
            assertTrue(page(html).contains("KEPT"), page(html));
            assertTrue(mail(html).contains("KEPT"), mail(html));
        }
    }

    @Test
    @DisplayName("mail: black-on-black from a bgcolor counts the default text colour")
    void defaultTextOnBlack() {
        assertFalse(mail("<table bgcolor=\"#000000\"><tr><td>DARK</td></tr></table>").contains("DARK"));
        assertTrue(mail("<table bgcolor=\"#000000\"><tr><td style=\"color:#eee\">LIGHT</td></tr></table>").contains("LIGHT"));
        assertTrue(page("<table bgcolor=\"#000000\"><tr><td>DARK</td></tr></table>").contains("DARK"), "no assumed text colour on pages");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2ex", "3ch", "1vw", "2cm", "1in"})
    @DisplayName("a font size in a unit with no conversion, and no inherited size, keeps the text")
    void unconvertedFontUnit(String size) {
        String html = "<p style=\"font-size:" + size + "\">SIZED</p>";
        assertTrue(mail(html).contains("SIZED"));
        assertTrue(page(html).contains("SIZED"));
    }

    @Test
    @DisplayName("the count reports what was removed, and a deep document does not overflow the stack")
    void countAndDepth() {
        var doc = Jsoup.parse("<div style=\"display:none\">a</div><!-- b --><p style=\"font-size:0\">c</p>");
        assertEquals(3, HiddenContentFilter.removeHidden(doc, HiddenContentFilter.Mode.MAIL));
        var page = Jsoup.parse("<div style=\"display:none\">a</div><!-- b --><p style=\"font-size:0\">c</p>");
        assertEquals(2, HiddenContentFilter.removeHidden(page, HiddenContentFilter.Mode.PAGE));

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            deep.append("<div style=\"color:#111\">");
        }
        deep.append("text");
        HiddenContentFilter.removeHidden(Jsoup.parse(deep.toString()), HiddenContentFilter.Mode.MAIL);
    }

    @Nested
    @DisplayName("hostile style blocks are read in linear time, and never only in part")
    class HostileStylesheets {

        private static final Duration BUDGET = Duration.ofSeconds(10);

        private void assertStillApplied(String junk) {
            String html = "<style>" + junk + "</style><style>.late{display:none}</style><p>VISIBLE</p><p class=\"late\">INJECTED</p>";
            String out = assertTimeoutPreemptively(BUDGET, () -> converter.convertMail(html, 0));
            assertTrue(out.contains("VISIBLE"), out);
            assertFalse(out.contains("INJECTED"), "a rule after the junk must still be read");
        }

        @Test
        @DisplayName("'/* ' repeated over 3 MB, never closed")
        void repeatedCommentOpeners() {
            // With a space between them no two openers form a "*/", so a lazy
            // /\*.*?\*/ regex scans to the end from every one of them.
            assertStillApplied("/* ".repeat(1_000_000));
            assertStillApplied("/*".repeat(1_000_000));
        }

        @Test
        @DisplayName("one unterminated '/*' and 4 MB of body")
        void unterminatedComment() {
            assertStillApplied("/*" + "x".repeat(4_000_000));
        }

        @Test
        @DisplayName("a 4 MB brace-less block")
        void braceless() {
            assertStillApplied("a b c ".repeat(700_000));
        }

        @Test
        @DisplayName("2 MB of at-statements and of unclosed at-blocks")
        void atRules() {
            assertStillApplied("@a;".repeat(700_000));
            assertStillApplied("@media x {".repeat(200_000));
        }

        @Test
        @DisplayName("a 5 MB block of opening braces")
        void openBraces() {
            assertStillApplied("a{".repeat(2_500_000));
        }

        @Test
        @DisplayName("a rule after 3 MB of ordinary rules is still applied (no rule cap)")
        void ruleAfterManyRules() {
            assertStillApplied(".r{color:red}".repeat(250_000));
        }

        @Test
        @DisplayName("thousands of hiding rules for one element: hidden, in bounded work per element")
        void manyCandidates() {
            StringBuilder css = new StringBuilder();
            for (int i = 0; i < 100_000; i++) {
                css.append(".a.b").append(i).append("{display:none}");
            }
            String html = "<style>" + css + "</style><p>VISIBLE</p>" + "<p class=\"a\">MANY</p>".repeat(2000);
            String out = assertTimeoutPreemptively(BUDGET, () -> converter.convertMail(html, 0));
            assertTrue(out.contains("VISIBLE"));
            assertFalse(out.contains("MANY"), "an element hundreds of rules could hide counts as hidden");
        }
    }

    @Test
    @DisplayName("the selector reader: rightmost compound, escapes, state pseudo-classes and pseudo-elements")
    void selectorReader() {
        var compound = HiddenStylesheetRules.rightmostCompound("body > div.Outer  td#Cell.x.y:nth-child(2)[data-q]");
        assertEquals("td", compound.tag());
        assertEquals("cell", compound.id());
        assertEquals(Set.of("x", "y"), compound.classes());
        assertNull(HiddenStylesheetRules.rightmostCompound("a:hover"));
        assertNull(HiddenStylesheetRules.rightmostCompound("p::after"));
        assertEquals(Set.of("abc"), HiddenStylesheetRules.rightmostCompound(".\\61 bc").classes());
        assertEquals(2, HiddenStylesheetRules.splitTopLevel(".a, :is(.b, .c)").size());
    }
}
