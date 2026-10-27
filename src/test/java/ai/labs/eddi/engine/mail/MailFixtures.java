/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads the hand-written messages under {@code src/test/resources/mail/}. */
final class MailFixtures {

    private MailFixtures() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = MailFixtures.class.getResourceAsStream("/mail/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static MimeMessageParser parser() {
        return new MimeMessageParser(new HtmlToMarkdownConverter());
    }

    static InboundMail parse(String name) throws MailParseException {
        return parser().parse(bytes(name), MailLimits.defaults(), true);
    }

    static InboundMail parseUnstripped(String name) throws MailParseException {
        return parser().parse(bytes(name), MailLimits.defaults(), false);
    }

    /** A message built in the test, with CRLF line endings. */
    static byte[] message(String text) {
        return text.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
