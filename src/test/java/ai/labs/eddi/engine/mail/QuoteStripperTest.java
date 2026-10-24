/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteStripperTest {

    @Nested
    @DisplayName("plain text")
    class Text {

        @Test
        @DisplayName("a trailing > block and its attribution are removed")
        void trailingQuote() {
            var result = QuoteStripper.stripText("""
                    Sounds good.

                    On Fri, 23 Oct 2026 at 18:00, Support <support@example.org> wrote:
                    > Shall we?
                    >
                    > Support
                    """);

            assertTrue(result.removed());
            assertEquals("Sounds good.", result.content());
        }

        @Test
        @DisplayName("an attribution wrapped over two lines is removed with the quote")
        void wrappedAttribution() {
            var result = QuoteStripper.stripText("""
                    Sounds good.

                    On Fri, 23 Oct 2026 at 18:00, Support Team Example
                    <support@example.org> wrote:
                    > Shall we?
                    """);

            assertEquals("Sounds good.", result.content());
        }

        @Test
        @DisplayName("German and French attributions")
        void localisedAttributions() {
            assertTrue(QuoteStripper.isAttribution("Am 23.10.2026 um 18:00 schrieb Support <support@example.org>:"));
            assertTrue(QuoteStripper.isAttribution("Le ven. 23 oct. 2026 à 18:00, Support <support@example.org> a écrit :"));
            assertFalse(QuoteStripper.isAttribution("On the whole I think it is fine:"));
        }

        @Test
        @DisplayName("quotes interleaved with answers are kept whole")
        void interleaved() {
            String text = """
                    > Which plan?
                    Business.
                    > How many seats?
                    Twelve.
                    """;

            var result = QuoteStripper.stripText(text);

            assertFalse(result.removed());
            assertEquals(text, result.content());
        }

        @Test
        @DisplayName("a trailing quote after an earlier quote counts as interleaved")
        void interleavedEndingInQuote() {
            var result = QuoteStripper.stripText("""
                    > Which plan?
                    Business.
                    > Thanks,
                    > Support
                    """);

            assertFalse(result.removed(), "the answer 'Business.' needs the quote above it");
        }

        @Test
        @DisplayName("text after the quote (bottom-posting) is kept, quote included")
        void bottomPosted() {
            var result = QuoteStripper.stripText("""
                    > Which plan?

                    Business.
                    """);

            assertFalse(result.removed());
        }

        @Test
        @DisplayName("a message that is nothing but a quote is kept")
        void onlyQuote() {
            var result = QuoteStripper.stripText("> forwarded text\n> more\n");

            assertFalse(result.removed());
        }

        @Test
        @DisplayName("an Outlook header block and everything after it are removed")
        void outlookBlock() {
            var result = QuoteStripper.stripText("""
                    Thursday works.

                    From: Support <support@example.org>
                    Sent: Thursday, October 22, 2026 4:02 PM
                    To: Bob <bob@example.com>
                    Subject: Delivery

                    Which day?
                    """);

            assertTrue(result.removed());
            assertEquals("Thursday works.", result.content());
        }

        @Test
        @DisplayName("an Outlook 'Original Message' separator")
        void originalMessage() {
            var result = QuoteStripper.stripText("Fine.\n\n-----Original Message-----\nFrom: x\nWhatever\n");

            assertEquals("Fine.", result.content());
        }

        @Test
        @DisplayName("a lone 'From:' line in the sender's own text is not a header block")
        void fromLineAlone() {
            String text = "From: the warehouse team, with thanks.\nWe shipped it today.\n";

            var result = QuoteStripper.stripText(text);

            assertFalse(result.removed());
        }

        @Test
        @DisplayName("a Gmail forward is not quoted history")
        void forwardKept() {
            String text = "FYI\n\n---------- Forwarded message ---------\nFrom: A <a@example.com>\nDate: Fri\nSubject: x\nTo: b@example.org\n\nBody\n";

            var result = QuoteStripper.stripText(text);

            assertFalse(result.removed(), "the forwarded mail is the content");
            assertTrue(result.content().contains("Body"));
        }

        @Test
        @DisplayName("forward subjects in the common languages are recognised")
        void forwardSubjects() {
            assertTrue(QuoteStripper.isForwardSubject("Fwd: invoice"));
            assertTrue(QuoteStripper.isForwardSubject("FW: invoice"));
            assertTrue(QuoteStripper.isForwardSubject("WG: Rechnung"));
            assertTrue(QuoteStripper.isForwardSubject("TR : facture"));
            assertFalse(QuoteStripper.isForwardSubject("RE: invoice"));
            assertFalse(QuoteStripper.isForwardSubject("Invoice"));
        }
    }

    @Nested
    @DisplayName("HTML")
    class Html {

        @Test
        @DisplayName("a trailing gmail_quote is removed with its attribution")
        void gmail() {
            var result = QuoteStripper.stripHtml("<div>Yes.</div><div class=\"gmail_quote\"><div class=\"gmail_attr\">On Fri, X wrote:</div>"
                    + "<blockquote class=\"gmail_quote\">Question?</blockquote></div>");

            assertTrue(result.removed());
            assertEquals("Yes.", Jsoup.parse(result.content()).body().text());
        }

        @Test
        @DisplayName("an older gmail_quote with the attribution as bare text")
        void olderGmail() {
            var result = QuoteStripper.stripHtml("<div>Yes.</div><div class=\"gmail_extra\"><div class=\"gmail_quote\">On Fri, 23 Oct 2026, X "
                    + "&lt;x@example.org&gt; wrote:<br><blockquote class=\"gmail_quote\">Question?</blockquote></div></div>");

            assertTrue(result.removed());
            assertEquals("Yes.", Jsoup.parse(result.content()).body().text());
        }

        @Test
        @DisplayName("a gmail_quote followed by the sender's text is kept")
        void gmailNotTrailing() {
            var result = QuoteStripper.stripHtml("<div class=\"gmail_quote\"><blockquote class=\"gmail_quote\">Q?</blockquote></div><div>A.</div>");

            assertFalse(result.removed());
        }

        @Test
        @DisplayName("two cite blockquotes with an answer between are an inline reply")
        void citeInterleaved() {
            var result = QuoteStripper.stripHtml("<blockquote type=\"cite\">Q1?</blockquote><p>A1.</p><blockquote type=\"cite\">Q2?</blockquote>");

            assertFalse(result.removed());
        }

        @Test
        @DisplayName("an unrecognised plain <blockquote> is kept")
        void plainBlockquoteKept() {
            var result = QuoteStripper.stripHtml("<p>As the manual says:</p><blockquote>Restart the router.</blockquote>");

            assertFalse(result.removed());
        }

        @Test
        @DisplayName("Outlook's header paragraph and everything after it are removed")
        void outlookHeaderParagraph() {
            var doc = Jsoup.parse("<div><p>Done.</p><div style=\"border-top:solid #E1E1E1 1pt\"><p><b>Von:</b> A<br><b>Gesendet:</b> Freitag<br>"
                    + "<b>An:</b> B<br><b>Betreff:</b> C</p></div><p>Old text</p></div>");

            assertTrue(QuoteStripper.stripHtml(doc));
            assertEquals("Done.", doc.body().text());
        }

        @Test
        @DisplayName("removal that would leave nothing is not made")
        void neverEmpty() {
            var result = QuoteStripper.stripHtml("<blockquote type=\"cite\">Only a quote</blockquote>");

            assertFalse(result.removed());
        }
    }
}
