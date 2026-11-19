/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.MentionExtractor.MentionHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The strict mention extractor (email v2 plan §7.3): a mention counts only in
 * the sender's own text. Fixtures are the shapes the major clients write; the
 * plan's open item §13.6 (native mentions in text/plain) still wants captures
 * from real clients.
 */
class MentionExtractorTest {

    private static final Map<String, String> KNOWN = Map.of("finance", "Finance", "legal", "Legal", "acme", "Acme");

    private static List<String> handles(String text) {
        return handles(text, Set.of());
    }

    private static List<String> handles(String text, Set<MailAnomaly> anomalies) {
        return MentionExtractor.extract(text, anomalies, KNOWN).stream().map(MentionHit::handle).toList();
    }

    @Nested
    @DisplayName("the sender's own text")
    class OwnText {

        @Test
        @DisplayName("a mention at the start of a line, after whitespace or in brackets counts; case is ignored")
        void tokens() {
            assertEquals(List.of("finance"), handles("@Finance please process this."));
            assertEquals(List.of("finance", "legal"), handles("Hi (@finance) and [@LEGAL],\nthanks"));
            assertEquals(List.of("finance"), handles("FYI: @finance, can you look?"), "a label before a mention is not a header");
        }

        @Test
        @DisplayName("hits come in order of first appearance, each handle once, with the target it stands for")
        void orderAndTarget() {
            assertEquals(List.of(new MentionHit("legal", "Legal"), new MentionHit("finance", "Finance")),
                    MentionExtractor.extract("@legal first, then @finance, then @legal again", Set.of(), KNOWN));
        }

        @Test
        @DisplayName("unknown handles are ignored: they may be people")
        void unknownIgnored() {
            assertEquals(List.of(), handles("@bob can you ask @alice?"));
        }

        @Test
        @DisplayName("an Outlook mention converted from HTML-only mail counts")
        void outlookHtmlMention() {
            assertEquals(List.of("finance"), handles("[@Finance](mailto:finance-agent@contoso.com) please book this"));
        }
    }

    @Nested
    @DisplayName("never an address, a domain or part of a word")
    class NotMentions {

        @ParameterizedTest
        @ValueSource(strings = {"Send it to bob@finance.com please", "see @finance.com", "mail @finance@contoso.com", "x@finance",
                "https://example.com/@finance/profile", "@financeé", "@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "@_finance", "email:@legal.org"})
        void noMatch(String text) {
            assertEquals(List.of(), MentionExtractor.extract(text, Set.of(),
                    Map.of("finance", "F", "legal", "L", "a".repeat(40), "A")).stream().map(MentionHit::handle).toList());
        }

        @Test
        @DisplayName("a 41-character run does not match its first 40")
        void longRun() {
            String forty = "a".repeat(40);
            assertEquals(List.of(), MentionExtractor.extract("@" + forty + "b ok", Set.of(), Map.of(forty, "T")));
            assertEquals(List.of(new MentionHit(forty, "T")), MentionExtractor.extract("@" + forty + " ok", Set.of(), Map.of(forty, "T")));
        }

        @Test
        @DisplayName("a mention at the end of a sentence still counts")
        void punctuation() {
            assertEquals(List.of("finance"), handles("Please ask @finance."));
            assertEquals(List.of("finance"), handles("Ask @finance's team"));
        }
    }

    @Nested
    @DisplayName("the head region stops before history, headers and signatures")
    class HeadRegion {

        @Test
        @DisplayName("Gmail English reply: the quoted mention does not count")
        void gmailEn() {
            assertEquals(List.of(), handles("Thanks, will do.\n\nOn Tue, 17 Nov 2026 at 10:15, Bob <bob@x.com> wrote:\n> ask @finance\n"));
        }

        @Test
        @DisplayName("Gmail English reply with the attribution wrapped over two lines")
        void gmailWrapped() {
            assertEquals(List.of(), handles("Thanks.\n\nOn Tue, 17 Nov 2026 at 10:15, Bob Example\nwrote:\n@finance not mine\n> quoted"));
        }

        @Test
        @DisplayName("Gmail German reply")
        void gmailDe() {
            assertEquals(List.of("legal"),
                    handles("@legal bitte prüfen.\n\nAm Di., 17. Nov. 2026 um 10:15 Uhr schrieb Bob <bob@x.com>:\n> @finance bitte\n"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "From: Bob Example <bob@x.com>\nSent: Tuesday, November 17, 2026 10:15 AM\nTo: Invoices\nSubject: x\n\n@finance please",
                "Von: Bob Example <bob@x.com>\nGesendet: Dienstag, 17. November 2026 10:15\nAn: Invoices\nBetreff: x\n\n@finance bitte",
                "De : Bob Example <bob@x.com>\nEnvoyé : mardi 17 novembre 2026 10:15\nÀ : Invoices\nObjet : x\n\n@finance svp",
                "De: Bob Example <bob@x.com>\nEnviado: martes, 17 de noviembre de 2026 10:15\nPara: Invoices\nAsunto: x\n\n@finance",
                "Da: Bob Example <bob@x.com>\nInviato: martedì 17 novembre 2026 10:15\nA: Invoices\nOggetto: x\n\n@finance",
                "Van: Bob Example <bob@x.com>\nVerzonden: dinsdag 17 november 2026 10:15\nAan: Invoices\nOnderwerp: x\n\n@finance",
                "-----Original Message-----\n@finance",
                "________________________________\nFrom: Bob\n@finance"})
        @DisplayName("Outlook header blocks (EN/DE/FR/ES/IT/NL) and separators end the head region")
        void outlook(String history) {
            assertEquals(List.of("legal"), handles("@legal see below.\n\n" + history));
        }

        @Test
        @DisplayName("Apple Mail reply")
        void appleMail() {
            assertEquals(List.of(), handles("Sure.\n\n> On 17 Nov 2026, at 10:15, Bob <bob@x.com> wrote:\n> \n> @finance please\n"));
        }

        @Test
        @DisplayName("an inline reply: the quoted line stops the scan, the reply below it is not read")
        void inlineReply() {
            assertEquals(List.of(), handles("> @finance can you do this?\nYes, @legal will.\n"));
            assertEquals(List.of("legal"), handles("@legal first.\n> @finance quoted\n@finance below"));
        }

        @Test
        @DisplayName("a signature mention does not count")
        void signature() {
            assertEquals(List.of(), handles("Thanks for the update.\n-- \nBob\nFollow us @acme"));
            assertEquals(List.of(), handles("Thanks.\n—\nFollow us @acme"));
        }

        @Test
        @DisplayName("a quoted EDDI reply containing 'ask @legal' does not count")
        void quotedEddiReply() {
            assertEquals(List.of(),
                    handles("Ok, thanks!\n\nOn Tue, 17 Nov 2026 at 10:15, Invoices <invoices@contoso.com> wrote:\n> For contracts, ask @legal.\n"));
        }

        @Test
        @DisplayName("a forward: the forwarder's own text above the forward header counts, the forwarded mail does not")
        void forward() {
            assertEquals(List.of("finance"), handles(
                    "@finance please process\n\n---------- Forwarded message ---------\nFrom: Supplier <s@y.com>\nDate: Tue\n\n@legal look\n"));
        }

        @Test
        @DisplayName("a mention on line 22 of a 25-line mail is past the head region")
        void lineLimit() {
            StringBuilder text = new StringBuilder();
            for (int i = 1; i <= 25; i++) {
                text.append(i == 22 ? "@finance line 22" : "line " + i).append('\n');
            }
            assertEquals(List.of(), handles(text.toString()));
            assertEquals(List.of("finance"), handles("line\n".repeat(19) + "@finance on 20\n"), "the 20th non-blank line is read");
            assertEquals(List.of("finance"), handles("\n".repeat(40) + "@finance after blanks"), "blank lines do not count");
        }

        @Test
        @DisplayName("the character limit: a line that would cross 2,000 characters is not read, even partly")
        void characterLimit() {
            String filler = "x".repeat(1990) + "\n";
            assertEquals(List.of(), handles(filler + "@finance is past the limit"));
            assertEquals(List.of(), handles("y".repeat(1995) + " @finance" + "z".repeat(50)), "a cut line is never read");
            assertEquals(List.of("finance"), handles("x".repeat(1980) + " @finance"));
        }

        @Test
        @DisplayName("text and HTML that say different things: no mentions at all")
        void divergentAlternatives() {
            assertEquals(List.of(), handles("@finance please", Set.of(MailAnomaly.ALTERNATIVES_DIVERGE)));
            assertEquals(List.of("finance"), handles("@finance please", Set.of(MailAnomaly.HTML_TRUNCATED)));
        }

        @Test
        @DisplayName("a huge mail is read only as far as the head region: linear and bounded")
        void hugeInput() {
            String huge = "From: ".repeat(200_000) + "\n" + "@ ".repeat(500_000);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> handles(huge));
            String longLabel = "Subject" + " ".repeat(1500) + ":" + "1".repeat(400);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> handles(longLabel + "\n@finance"));
        }
    }

    @Test
    @DisplayName("handles: 1-40 letters, digits, '_' or '-', starting with a letter or digit, stored lower-case")
    void normalizeHandle() {
        assertEquals("finance-team_2", MentionExtractor.normalizeHandle(" Finance-Team_2 "));
        assertNull(MentionExtractor.normalizeHandle("@finance"));
        assertNull(MentionExtractor.normalizeHandle("-finance"));
        assertNull(MentionExtractor.normalizeHandle("fin ance"));
        assertNull(MentionExtractor.normalizeHandle("a".repeat(41)));
        assertNull(MentionExtractor.normalizeHandle(""));
        assertNull(MentionExtractor.normalizeHandle(null));
        assertEquals(List.of(), MentionExtractor.extract(null, Set.of(), KNOWN));
        assertEquals(List.of(), MentionExtractor.extract("@finance", null, Map.of()));
    }
}
