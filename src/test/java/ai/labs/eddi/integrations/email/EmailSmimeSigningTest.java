/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.mail.SmimeSigner;
import ai.labs.eddi.engine.mail.SmimeSignerTest;
import ai.labs.eddi.engine.mail.SmimeTestCertificates;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code emailControl.smime.sign} as the transport applies it (v3 plan §6.2):
 * the security property is that {@code required} never lets an unsigned message
 * through — whatever is wrong with the certificate.
 */
class EmailSmimeSigningTest {

    static final String ADDRESS = "invoices@contoso.com";
    static final String REFERENCE = "${connection:cert}";

    ConnectionResolver resolver;
    EmailSmimeSigning signing;
    byte[] mime;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resolver = mock(ConnectionResolver.class);
        Instance<ConnectionResolver> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(resolver);
        signing = new EmailSmimeSigning(instance);
        mime = SmimeSignerTest.rendered("Hello from the mailbox");
    }

    static EmailChannelSettings settings(EmailControl.SignMode sign, String connection) {
        Map<String, String> pc = new HashMap<>();
        pc.put("smtpHost", "smtp.contoso.com");
        var configuration = EmailInboundProcessorTest.integration(pc);
        configuration.setEmailControl(new EmailControl(new EmailControl.Smime(sign, connection)));
        return EmailChannelSettings.from(configuration);
    }

    void certificate(SmimeTestCertificates.Leaf leaf) throws Exception {
        String pem = SmimeTestCertificates.pem(SmimeTestCertificates.issue(leaf));
        when(resolver.resolveSmimeSigning(eq(REFERENCE), any())).thenReturn(new ConnectionResolver.SmimeMaterial("cert", pem, null));
    }

    @Nested
    @DisplayName("sign: required")
    class Required {

        @Test
        @DisplayName("a usable certificate signs")
        void signs() throws Exception {
            certificate(SmimeTestCertificates.Leaf.valid(ADDRESS));
            var prepared = signing.prepare("i", settings(EmailControl.SignMode.REQUIRED, "cert"), mime);
            assertTrue(prepared.signed());
            assertEquals(SmimeSigner.Verification.VERIFIED, SmimeSigner.verify(prepared.mime()).verification());
        }

        @Test
        @DisplayName("a certificate for another address holds the mail (SAN mismatch)")
        void sanMismatch() throws Exception {
            certificate(SmimeTestCertificates.Leaf.valid("ceo@contoso.com"));
            var e = assertThrows(MailSendException.class, () -> signing.prepare("i", settings(EmailControl.SignMode.REQUIRED, "cert"), mime));
            assertEquals(MailSendException.Category.SIGNING, e.getCategory());
            assertTrue(e.getMessage().startsWith("smime_unavailable"), e.getMessage());
        }

        @Test
        @DisplayName("an expired certificate holds the mail")
        void expired() throws Exception {
            certificate(SmimeTestCertificates.Leaf.valid(ADDRESS).expired());
            assertThrows(MailSendException.class, () -> signing.prepare("i", settings(EmailControl.SignMode.REQUIRED, "cert"), mime));
        }

        @Test
        @DisplayName("a connection that cannot be resolved holds the mail")
        void unresolvable() {
            when(resolver.resolveSmimeSigning(eq(REFERENCE), any()))
                    .thenThrow(new ConnectionException(ConnectionException.Reason.NOT_FOUND, "Connection 'cert' does not exist."));
            assertThrows(MailSendException.class, () -> signing.prepare("i", settings(EmailControl.SignMode.REQUIRED, "cert"), mime));
        }

        @Test
        @DisplayName("material that is not a certificate holds the mail")
        void unreadable() {
            when(resolver.resolveSmimeSigning(eq(REFERENCE), any())).thenReturn(new ConnectionResolver.SmimeMaterial("cert", "garbage", null));
            assertThrows(MailSendException.class, () -> signing.prepare("i", settings(EmailControl.SignMode.REQUIRED, "cert"), mime));
        }
    }

    @Nested
    @DisplayName("sign: true (the default)")
    class On {

        @Test
        @DisplayName("signs with a usable certificate")
        void signs() throws Exception {
            certificate(SmimeTestCertificates.Leaf.valid(ADDRESS));
            assertTrue(signing.prepare("i", settings(null, "cert"), mime).signed());
        }

        @Test
        @DisplayName("without a connection the mail goes out unsigned, untouched")
        void noConnection() throws Exception {
            var prepared = signing.prepare("i", settings(null, null), mime);
            assertFalse(prepared.signed());
            assertSame(mime, prepared.mime());
            verifyNoInteractions(resolver);
        }

        @Test
        @DisplayName("an unusable certificate falls back to unsigned")
        void fallback() throws Exception {
            certificate(SmimeTestCertificates.Leaf.valid(ADDRESS).expiringIn(Duration.ofDays(-2)));
            var prepared = signing.prepare("i", settings(EmailControl.SignMode.TRUE, "cert"), mime);
            assertFalse(prepared.signed());
            assertTrue(Arrays.equals(mime, prepared.mime()));
        }
    }

    @Test
    @DisplayName("sign: false never signs, even with a usable certificate")
    void off() throws Exception {
        certificate(SmimeTestCertificates.Leaf.valid(ADDRESS));
        assertFalse(signing.prepare("i", settings(EmailControl.SignMode.FALSE, "cert"), mime).signed());
        verifyNoInteractions(resolver);
    }
}
