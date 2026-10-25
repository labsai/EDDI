/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trigger mode sends no mail to a sender — a property of how the code is built,
 * so it is pinned here by a source scan: nothing under
 * {@code integrations/email/} may reach a mail-sending API except the two paths
 * that mail EDDI's own staff through the operator's system mailer (the owner
 * notice and the HITL approval notification). A change that adds a send call
 * anywhere else fails this test until conversation mode (Phase 3) deliberately
 * changes it.
 */
class EmailTriggerModeSendsNothingTest {

    private static final Path ROOT = Path.of("src", "main", "java", "ai", "labs", "eddi", "integrations", "email");

    /** The only files allowed to send: both mail staff, never a sender. */
    private static final Set<String> ALLOWED = Set.of("EmailOwnerNotifier.java", "HitlEmailNotifier.java");

    private static final Pattern SENDING = Pattern.compile(
            "SystemMailer|jakarta\\.mail\\.Transport\\b|\\bTransport\\.send\\b|getTransport\\s*\\(|SMTPTransport|Protocol\\.SMTP|\\.sendMessage\\s*\\(|"
                    + "PlainTextMailRenderer|OutboundMail\\b");

    @Test
    @DisplayName("no file under integrations/email/ but the staff notifiers references SystemMailer or an SMTP transport")
    void nothingElseSends() throws IOException {
        assertTrue(Files.isDirectory(ROOT), "run from the project root");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                if (ALLOWED.contains(file.getFileName().toString())) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                var matcher = SENDING.matcher(source);
                if (matcher.find()) {
                    offenders.add(ROOT.relativize(file) + " (" + matcher.group() + ")");
                }
            }
        }
        assertTrue(scanned > 10, "the scan found the email sources");
        assertTrue(offenders.isEmpty(), "Trigger mode must not send mail; these files reference a sending API: " + offenders);
    }

    @Test
    @DisplayName("the pattern recognises what it guards against")
    void patternIsLive() {
        assertTrue(SENDING.matcher("mailer.send(rendered, List.of(to)); SystemMailer mailer;").find());
        assertTrue(SENDING.matcher("session.getTransport(\"smtp\")").find());
        assertTrue(SENDING.matcher("SafeSocketConnector.Protocol.SMTP").find());
        assertFalse(SENDING.matcher("SafeSocketConnector.Protocol.IMAP; MailTransport transport;").find());
    }
}
