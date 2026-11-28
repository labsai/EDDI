/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.integrations.email.EmailSmimeSigning;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code sign: required} at the transport: when the message cannot be signed,
 * no relay is ever contacted — not even resolved.
 */
class ImapMailTransportSigningTest {

    static EmailChannelSettings settings(String mode, EmailControl.SignMode sign) {
        Map<String, String> pc = new HashMap<>(Map.of("mode", mode, "transport", "imap", "connection", "mailbox", "host", "imap.contoso.com",
                "address", "invoices@contoso.com", "smtpHost", "smtp.contoso.com"));
        var configuration = new ChannelIntegrationConfiguration();
        configuration.setChannelType("email");
        configuration.setPlatformConfig(pc);
        configuration.setEmailControl(new EmailControl(new EmailControl.Smime(sign, "cert")));
        return EmailChannelSettings.from(configuration);
    }

    @SuppressWarnings("unchecked")
    static ImapMailTransport transport(AtomicInteger relayLookups, EmailSmimeSigning signing) {
        var transport = new ImapMailTransport(mock(SafeSocketConnector.class), (host, port) -> {
            throw new AssertionError("no IMAP");
        }, (host, port) -> {
            relayLookups.incrementAndGet();
            throw new AssertionError("the relay must not be reached");
        }, mock(Instance.class));
        Instance<EmailSmimeSigning> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(signing != null);
        when(instance.get()).thenReturn(signing);
        transport.smimeSigning = instance;
        return transport;
    }

    @Test
    @DisplayName("a reply that cannot be signed under 'required' never reaches the relay")
    void replyHeld() throws Exception {
        EmailSmimeSigning signing = mock(EmailSmimeSigning.class);
        when(signing.prepare(any(), any(), any()))
                .thenThrow(new MailSendException(MailSendException.Category.SIGNING, false, "smime_unavailable: expired", null));
        AtomicInteger lookups = new AtomicInteger();
        var e = assertThrows(MailSendException.class,
                () -> transport(lookups, signing).sendMail("i", settings("conversation", EmailControl.SignMode.REQUIRED), new byte[]{1}, "a@b.com"));
        assertEquals(MailSendException.Category.SIGNING, e.getCategory());
        assertEquals(0, lookups.get());
    }

    @Test
    @DisplayName("without the signing bean, 'required' fails closed — the self-test too")
    void noSigningBean() {
        AtomicInteger lookups = new AtomicInteger();
        var transport = transport(lookups, null);
        var reply = assertThrows(MailSendException.class,
                () -> transport.sendMail("i", settings("conversation", EmailControl.SignMode.REQUIRED), new byte[]{1}, "a@b.com"));
        assertEquals(MailSendException.Category.SIGNING, reply.getCategory());
        assertFalse(reply.isPermanent(), "held, not failed");
        var selfTest = assertThrows(MailSendException.class,
                () -> transport.sendSelfTest("i", settings("trigger", EmailControl.SignMode.REQUIRED), new byte[]{1}));
        assertEquals(MailSendException.Category.SIGNING, selfTest.getCategory());
        assertEquals(0, lookups.get());
    }

    @Test
    @DisplayName("a transport that cannot sign sends nothing under 'required' (the MailTransport default), and sends unsigned otherwise")
    void defaultSendMailHonoursRequired() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        MailTransport unsigning = new MailTransport() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public MailboxSession open(String integrationId, EmailChannelSettings settings) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String send(String integrationId, EmailChannelSettings settings, byte[] mime, String recipient) {
                sends.incrementAndGet();
                return "250 ok";
            }
        };
        var e = assertThrows(MailSendException.class,
                () -> unsigning.sendMail("i", settings("conversation", EmailControl.SignMode.REQUIRED), new byte[]{1}, "a@b.com"));
        assertEquals(MailSendException.Category.SIGNING, e.getCategory());
        assertFalse(e.isPermanent());
        assertEquals(0, sends.get(), "nothing left unsigned");

        var sent = unsigning.sendMail("i", settings("conversation", EmailControl.SignMode.TRUE), new byte[]{1}, "a@b.com");
        assertFalse(sent.signed());
        assertEquals(1, sends.get());
    }

    @Test
    @DisplayName("a trigger-mode integration still never replies, though it may send its self-test")
    void triggerModeNeverReplies() {
        AtomicInteger lookups = new AtomicInteger();
        var e = assertThrows(MailSendException.class,
                () -> transport(lookups, null).sendMail("i", settings("trigger", EmailControl.SignMode.FALSE), new byte[]{1}, "a@b.com"));
        assertEquals(MailSendException.Category.NOT_CONFIGURED, e.getCategory());
        assertEquals(0, lookups.get());
    }
}
