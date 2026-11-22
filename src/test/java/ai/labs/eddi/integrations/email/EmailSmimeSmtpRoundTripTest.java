/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.mail.SmimeSigner;
import ai.labs.eddi.engine.mail.SmimeSignerTest;
import ai.labs.eddi.engine.mail.SmimeTestCertificates;
import ai.labs.eddi.engine.mail.TestTls;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.engine.net.SafeSocketTestSupport;
import ai.labs.eddi.integrations.email.transport.ImapMailTransport;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.enterprise.inject.Instance;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A signed reply through a real SMTP server (GreenMail, SMTPS on loopback): the
 * bytes the relay <em>received</em> — after {@code SmtpSubmitter} re-parsed and
 * Angus re-serialised them — still carry a signature Bouncy Castle verifies.
 */
class EmailSmimeSmtpRoundTripTest {

    static final String ADDRESS = "invoices@contoso.com";
    static GreenMail greenMail;

    @BeforeAll
    static void start() throws Exception {
        System.setProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_FILE_PROPERTY, TestTls.keystore().toString());
        System.setProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_PASSWORD_PROPERTY, TestTls.PASSWORD);
        greenMail = new GreenMail(new ServerSetup[]{new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS)});
        greenMail.start();
    }

    @AfterAll
    static void stop() {
        greenMail.stop();
        System.clearProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_FILE_PROPERTY);
        System.clearProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_PASSWORD_PROPERTY);
    }

    @Test
    @DisplayName("the message the relay received verifies with Bouncy Castle")
    @SuppressWarnings("unchecked")
    void signedThroughTheRelay() throws Exception {
        String pem = SmimeTestCertificates.pem(SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS)));
        ConnectionResolver resolver = mock(ConnectionResolver.class);
        when(resolver.authTypeOf("${connection:mailbox}")).thenReturn(Optional.of(AuthType.BASIC));
        when(resolver.resolveBasicLogin(eq("${connection:mailbox}"), any())).thenReturn(new ConnectionResolver.BasicLogin(ADDRESS, "fixturefixture"));
        when(resolver.resolveSmimeSigning(eq("${connection:cert}"), any())).thenReturn(new ConnectionResolver.SmimeMaterial("cert", pem, null));
        Instance<ConnectionResolver> resolvers = mock(Instance.class);
        when(resolvers.isResolvable()).thenReturn(true);
        when(resolvers.get()).thenReturn(resolver);
        greenMail.setUser(ADDRESS, ADDRESS, "fixturefixture");

        int port = greenMail.getSmtps().getPort();
        var transport = new ImapMailTransport(new SafeSocketConnector(Optional.empty()), (host, ignored) -> {
            throw new AssertionError("no IMAP");
        }, (host, ignored) -> SafeSocketTestSupport.loopback("localhost", port, SafeSocketConnector.Protocol.SMTP,
                SafeSocketConnector.TlsMode.IMPLICIT), resolvers);
        Instance<EmailSmimeSigning> signing = mock(Instance.class);
        when(signing.isResolvable()).thenReturn(true);
        when(signing.get()).thenReturn(new EmailSmimeSigning(resolvers));
        Field field = ImapMailTransport.class.getDeclaredField("smimeSigning");
        field.setAccessible(true);
        field.set(transport, signing);

        Map<String, String> pc = new HashMap<>(Map.of("mode", "conversation", "smtpHost", "smtp.contoso.com", "smtpPort", "465"));
        var configuration = EmailInboundProcessorTest.integration(pc);
        configuration.setEmailControl(new EmailControl(new EmailControl.Smime(EmailControl.SignMode.REQUIRED, "cert")));
        EmailChannelSettings settings = EmailChannelSettings.from(configuration);

        MailTransport.Sent sent = TestTls.trustingDefault(
                () -> transport.sendMail("i", settings, SmimeSignerTest.rendered("Here is the offer.\nTrailing spaces  \nEnd."),
                        "alice@partner.com"));
        assertTrue(sent.signed());

        assertTrue(greenMail.waitForIncomingEmail(5_000, 1));
        MimeMessage received = greenMail.getReceivedMessages()[0];
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        received.writeTo(raw);
        assertTrue(SmimeSignerTest.bouncyCastleVerifies(raw.toByteArray()), "the received message verifies");
        assertEquals(SmimeSigner.Verification.VERIFIED, SmimeSigner.verify(raw.toByteArray()).verification());
    }
}
