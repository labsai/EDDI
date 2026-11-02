/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.integrations.email.transport.ImapMailTransport;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Trigger mode sends no mail to a sender, and conversation mode sends only its
 * replies, through one path — properties of how the code is built, pinned here
 * by a source scan of {@code integrations/email/} plus the guards that make the
 * one path refuse trigger mode.
 * <ul>
 * <li>{@code SystemMailer} (the operator's relay) appears only in the two
 * notifiers that mail EDDI's own staff — never on the reply path.</li>
 * <li>SMTP itself is spoken in one file, {@link ImapMailTransport}, the channel
 * transport, on author-policy endpoints only.</li>
 * <li>Outbound mail is composed only by the staff notifiers and
 * {@code EmailReplyComposer}.</li>
 * <li>Only {@code EmailReplySender} calls a transport's {@code send}, and it,
 * the recipient rules and the transport each refuse an integration that is not
 * in conversation mode (tested in their own classes and below);
 * {@code EmailConversationModeTest} shows a trigger-mode turn never reaches
 * it.</li>
 * </ul>
 * A change that adds a send path anywhere else fails this test.
 */
class EmailTriggerModeSendsNothingTest {

    private static final Path ROOT = Path.of("src", "main", "java", "ai", "labs", "eddi", "integrations", "email");

    /** The operator relay: staff notices only. */
    private static final Pattern SYSTEM_MAILER = Pattern.compile("SystemMailer");
    private static final Set<String> SYSTEM_MAILER_ALLOWED = Set.of("EmailOwnerNotifier.java", "HitlEmailNotifier.java");

    /** Speaking SMTP. */
    private static final Pattern SMTP = Pattern.compile(
            "jakarta\\.mail\\.Transport\\b|\\bTransport\\.send\\b|getTransport\\s*\\(|SMTPTransport|Protocol\\.SMTP|\\.sendMessage\\s*\\(|SmtpSubmitter");
    /**
     * The channel transport speaks SMTP; the channel support only resolves the
     * relay's host at save time under the author policy and never connects.
     */
    private static final Set<String> SMTP_ALLOWED = Set.of("ImapMailTransport.java", "EmailChannelSupport.java");

    /** Composing outbound mail. */
    private static final Pattern COMPOSING = Pattern.compile("PlainTextMailRenderer|OutboundMail\\b");
    private static final Set<String> COMPOSING_ALLOWED = Set.of("EmailOwnerNotifier.java", "HitlEmailNotifier.java", "EmailReplyComposer.java",
            "EmailReplySender.java");

    /** Handing bytes to a channel transport. */
    private static final Pattern TRANSPORT_SEND = Pattern.compile("transport\\.send\\s*\\(");
    private static final Set<String> TRANSPORT_SEND_ALLOWED = Set.of("EmailReplySender.java");

    private static List<String> offenders(Pattern pattern, Set<String> allowed) throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                if (allowed.contains(file.getFileName().toString())) {
                    continue;
                }
                var matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
                if (matcher.find()) {
                    offenders.add(ROOT.relativize(file) + " (" + matcher.group() + ")");
                }
            }
        }
        assertTrue(scanned > 10, "the scan found the email sources");
        return offenders;
    }

    @Test
    @DisplayName("the operator's system mailer is used only by the staff notifiers, never by the reply path")
    void systemMailerOnlyForStaff() throws IOException {
        assertTrue(Files.isDirectory(ROOT), "run from the project root");
        assertEquals(List.of(), offenders(SYSTEM_MAILER, SYSTEM_MAILER_ALLOWED));
    }

    @Test
    @DisplayName("SMTP is spoken only by the channel transport")
    void smtpOnlyInTheTransport() throws IOException {
        assertEquals(List.of(), offenders(SMTP, SMTP_ALLOWED));
    }

    @Test
    @DisplayName("outbound mail is composed only by the staff notifiers and the reply composer")
    void composingOnlyThere() throws IOException {
        assertEquals(List.of(), offenders(COMPOSING, COMPOSING_ALLOWED));
    }

    @Test
    @DisplayName("only the reply sender hands a message to a transport")
    void onlyTheReplySenderSends() throws IOException {
        assertEquals(List.of(), offenders(TRANSPORT_SEND, TRANSPORT_SEND_ALLOWED));
    }

    @Test
    @DisplayName("the channel transport refuses to send for a trigger-mode integration before opening any socket")
    @SuppressWarnings("unchecked")
    void transportRefusesTriggerMode() throws Exception {
        SafeSocketConnector connector = mock(SafeSocketConnector.class);
        var transport = new ImapMailTransport(connector, mock(Instance.class));
        var trigger = EmailChannelSettings.from(Map.of("mode", "trigger", "transport", "imap", "connection", "mailbox", "host", "imap.contoso.com",
                "address", "invoices@contoso.com", "smtpHost", "smtp.contoso.com"));

        var error = assertThrows(MailSendException.class, () -> transport.send("a1b2", trigger, new byte[]{1}, "alice@supplier.com"));
        assertTrue(error.isPermanent());
        verify(connector, never()).resolve(anyString(), anyInt(), any());
        assertThrows(MailSendException.class, () -> transport.send("a1b2", null, new byte[]{1}, "alice@supplier.com"));
    }

    @Test
    @DisplayName("the patterns recognise what they guard against")
    void patternsAreLive() {
        assertTrue(SYSTEM_MAILER.matcher("mailer.send(rendered, List.of(to)); SystemMailer mailer;").find());
        assertTrue(SMTP.matcher("session.getTransport(\"smtp\")").find());
        assertTrue(SMTP.matcher("SafeSocketConnector.Protocol.SMTP").find());
        assertTrue(SMTP.matcher("SmtpSubmitter.submit(connector, endpoint").find());
        assertFalse(SMTP.matcher("SafeSocketConnector.Protocol.IMAP; MailTransport transport;").find());
        assertTrue(COMPOSING.matcher("new OutboundMail(from").find());
        assertTrue(TRANSPORT_SEND.matcher("transport.send(row.integrationId()").find());
        assertFalse(TRANSPORT_SEND.matcher("mailer.send(rendered").find());
    }
}
