/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static ai.labs.eddi.engine.mail.MailFixtures.message;
import static ai.labs.eddi.engine.mail.MailFixtures.parse;
import static ai.labs.eddi.engine.mail.MailFixtures.parser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoReplyDetectorTest {

    private static AutoReplyDetector.Verdict detect(String headers) throws MailParseException {
        return AutoReplyDetector.detect(parser().parse(message("From: Person <person@example.com>\nMessage-ID: <m@example.com>\n" + headers
                + "\n\nbody\n"), MailLimits.defaults(), true));
    }

    @Test
    @DisplayName("an ordinary reply is not automated")
    void ordinary() throws Exception {
        var verdict = AutoReplyDetector.detect(parse("gmail-reply.eml"));

        assertFalse(verdict.automated());
        assertEquals(AutoReplyDetector.Reason.NONE, verdict.reason());
        assertFalse(verdict.isBounce());
    }

    @Test
    @DisplayName("an out-of-office reply")
    void outOfOffice() throws Exception {
        var verdict = AutoReplyDetector.detect(parse("ooo-auto-reply.eml"));

        assertTrue(verdict.automated());
        assertEquals(AutoReplyDetector.Reason.AUTO_SUBMITTED, verdict.reason());
        assertEquals("auto-replied", verdict.detail());
    }

    @Test
    @DisplayName("a DSN is a report, carries its parsed delivery status, and wins over its other signals")
    void bounce() throws Exception {
        var verdict = AutoReplyDetector.detect(parse("dsn-bounce.eml"));

        assertTrue(verdict.automated());
        assertEquals(AutoReplyDetector.Reason.REPORT, verdict.reason());
        assertTrue(verdict.isBounce());
        assertTrue(verdict.deliveryReport().recipients().get(0).isPermanentFailure());
    }

    @Test
    @DisplayName("a mailing list")
    void mailingList() throws Exception {
        var verdict = AutoReplyDetector.detect(parse("mailing-list.eml"));

        assertTrue(verdict.automated());
        assertEquals(AutoReplyDetector.Reason.PRECEDENCE, verdict.reason());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Auto-Submitted: auto-generated|AUTO_SUBMITTED",
            "Auto-Submitted: auto-replied; owner-email=\"x@example.com\"|AUTO_SUBMITTED",
            "X-Autoreply: yes|X_AUTOREPLY",
            "X-Autorespond: Out of office|X_AUTORESPOND",
            "Precedence: bulk|PRECEDENCE",
            "Precedence: junk|PRECEDENCE",
            "Precedence: auto_reply|PRECEDENCE",
            "List-Id: <users.lists.example.org>|LIST_ID",
            "List-Unsubscribe: <https://example.org/u>|LIST_UNSUBSCRIBE",
            "Return-Path: <>|NULL_RETURN_PATH",
            "Return-Path: <MAILER-DAEMON@mx.example.org>|MAILER_DAEMON",
            "Return-Path: <postmaster@example.org>|MAILER_DAEMON"})
    @DisplayName("every loop header, one at a time")
    void eachSignal(String header, AutoReplyDetector.Reason expected) throws Exception {
        var verdict = detect(header);

        assertTrue(verdict.automated(), header);
        assertEquals(expected, verdict.reason());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {"Auto-Submitted: no", "X-Autoreply: no", "Precedence: first-class", "Return-Path: <person@example.com>"})
    @DisplayName("the explicit 'no' values and ordinary values are not signals")
    void notSignals(String header) throws Exception {
        assertFalse(detect(header).automated(), header);
    }

    @Test
    @DisplayName("a From of MAILER-DAEMON without a Return-Path")
    void mailerDaemonFrom() throws Exception {
        var verdict = AutoReplyDetector.detect(parser().parse(message("From: MAILER-DAEMON@mx.example.org\nMessage-ID: <d@example.org>\n\nx\n"),
                MailLimits.defaults(), true));

        assertEquals(AutoReplyDetector.Reason.MAILER_DAEMON, verdict.reason());
    }

    @Test
    @DisplayName("a read receipt (multipart/report, not a DSN) is still a report")
    void readReceipt() throws Exception {
        var mail = parser().parse(message("""
                From: someone@example.com
                Message-ID: <mdn@example.com>
                Content-Type: multipart/report; report-type=disposition-notification; boundary="r"

                --r
                Content-Type: text/plain

                Your message was read.
                --r
                Content-Type: message/disposition-notification

                Disposition: manual-action/MDN-sent-manually; displayed
                --r--
                """), MailLimits.defaults(), true);

        var verdict = AutoReplyDetector.detect(mail);
        assertEquals(AutoReplyDetector.Reason.REPORT, verdict.reason());
        assertEquals("disposition-notification", verdict.detail());
        assertFalse(verdict.isBounce());
    }
}
