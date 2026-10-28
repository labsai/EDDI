/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.enterprise.inject.Instance;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The system mailer against real SMTP servers on loopback: GreenMail for
 * implicit TLS, plain SMTP and AUTH, and {@link StartTlsSmtpServer} for the
 * STARTTLS upgrade GreenMail does not speak. Loopback is reachable because the
 * relay is operator configuration
 * ({@code SafeSocketConnector.Policy.OPERATOR}); certificate and host-name
 * checks stay on, against a throwaway certificate the test trusts
 * ({@link TestTls}).
 */
class SystemMailerTest {

    private static final MailAddress FROM = new MailAddress("eddi@example.com", "EDDI");
    private static final MailAddress TO = new MailAddress("approver@example.com", null);

    private static GreenMail greenMail;

    @BeforeAll
    static void startGreenMail() throws Exception {
        System.setProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_FILE_PROPERTY, TestTls.keystore().toString());
        System.setProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_PASSWORD_PROPERTY, TestTls.PASSWORD);
        greenMail = new GreenMail(new ServerSetup[]{new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP),
                new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS)});
        greenMail.setUser("eddi@example.com", "eddi", "right-password");
        greenMail.start();
    }

    @AfterAll
    static void stopGreenMail() {
        greenMail.stop();
        System.clearProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_FILE_PROPERTY);
        System.clearProperty(DummySSLServerSocketFactory.GREENMAIL_KEYSTORE_PASSWORD_PROPERTY);
    }

    @AfterEach
    void purge() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
    }

    private static SafeSocketConnector connector() {
        return new SafeSocketConnector(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private static Instance<ConnectionResolver> noConnections() {
        Instance<ConnectionResolver> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    private static SystemMailer mailer(int port, String tls, boolean allowPlaintext, String username, String password) {
        return new SystemMailer(connector(), noConnections(),
                SystemMailer.parse("localhost", port, tls, allowPlaintext, FROM.address(), "EDDI", null, username, password));
    }

    private static PlainTextMailRenderer.RenderedMail notification() {
        return PlainTextMailRenderer.render(new OutboundMail(FROM, List.of(TO), null, "[EDDI] Approval needed: Billing agent",
                "A conversation is waiting for your decision.", null, null, null, OutboundMail.Kind.AUTO_GENERATED, null));
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("no host means disabled, and send refuses permanently")
        void disabledWithoutHost() {
            var mailer = new SystemMailer(connector(), noConnections(), Optional.empty(), 587, Optional.empty(), false,
                    Optional.of("eddi@example.com"), "EDDI", Optional.empty(), Optional.empty(), Optional.empty());

            assertFalse(mailer.isEnabled());
            var error = assertThrows(MailSendException.class, () -> mailer.send(notification(), List.of(TO)));
            assertEquals(MailSendException.Category.NOT_CONFIGURED, error.getCategory());
            assertTrue(error.isPermanent());
        }

        @Test
        @DisplayName("TLS defaults: implicit on 465, STARTTLS elsewhere")
        void tlsDefaults() {
            assertEquals(SystemMailer.Tls.IMPLICIT, SystemMailer.parse("relay", 465, null, false, "a@example.com", null, null, null, null).tls());
            assertEquals(SystemMailer.Tls.STARTTLS, SystemMailer.parse("relay", 587, null, false, "a@example.com", null, null, null, null).tls());
            assertEquals(SystemMailer.Tls.STARTTLS, SystemMailer.parse("relay", 25, " ", false, "a@example.com", null, null, null, null).tls());
        }

        @Test
        @DisplayName("plaintext needs the explicit opt-out, and never carries a credential")
        void plaintextRules() {
            assertThrows(IllegalArgumentException.class,
                    () -> SystemMailer.parse("relay", 25, "none", false, "a@example.com", null, null, null, null));
            assertEquals(SystemMailer.Tls.NONE, SystemMailer.parse("relay", 25, "none", true, "a@example.com", null, null, null, null).tls());
            assertThrows(IllegalArgumentException.class,
                    () -> SystemMailer.parse("relay", 25, "none", true, "a@example.com", null, null, "user", "pw"));
            assertThrows(IllegalArgumentException.class,
                    () -> SystemMailer.parse("relay", 25, "none", true, "a@example.com", null, "smtp", null, null));
        }

        @Test
        @DisplayName("one credential source, complete, and a valid sender")
        void credentialAndSender() {
            assertThrows(IllegalArgumentException.class,
                    () -> SystemMailer.parse("relay", 587, null, false, "a@example.com", null, "smtp", "user", "pw"));
            assertThrows(IllegalArgumentException.class,
                    () -> SystemMailer.parse("relay", 587, null, false, "a@example.com", null, null, "user", null));
            assertThrows(IllegalArgumentException.class, () -> SystemMailer.parse("relay", 587, null, false, null, null, null, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> SystemMailer.parse("relay", 587, null, false, "not-an-address", null, null, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> SystemMailer.parse("relay", 587, "tls13", false, "a@example.com", null, null, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> SystemMailer.parse("relay", 587, null, false, "a@example.com", "EDDI\r\nBcc: x@example.com", null, null, null));
        }

        @Test
        @DisplayName("a connection name no stored connection could carry is refused at settings load")
        void unusableConnectionNameIsRefused() {
            var refused = assertThrows(IllegalArgumentException.class,
                    () -> SystemMailer.parse("relay", 587, null, false, "a@example.com", null, "my mail", null, null));
            assertTrue(refused.getMessage().contains("eddi.mail.smtp.connection"), refused.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> SystemMailer.parse("relay", 587, null, false, "a@example.com", null, "smtp}", null, null));
            assertEquals("exo-smtp.v2",
                    SystemMailer.parse("relay", 587, null, false, "a@example.com", null, " exo-smtp.v2 ", null, null).connection());
        }

        @Test
        @DisplayName("the settings never print the password")
        void noPasswordInToString() {
            var settings = SystemMailer.parse("relay", 587, null, false, "a@example.com", null, null, "user", "s3cret-value");
            assertFalse(settings.toString().contains("s3cret-value"));
        }
    }

    @Nested
    @DisplayName("against GreenMail")
    class GreenMailServer {

        @Test
        @DisplayName("implicit TLS with AUTH delivers the message as rendered, Message-ID and Auto-Submitted intact")
        void implicitTlsWithAuth() throws Exception {
            var mailer = mailer(greenMail.getSmtps().getPort(), "implicit", false, "eddi", "right-password");
            var rendered = notification();

            var result = TestTls.trustingDefault(() -> mailer.send(rendered, List.of(TO)));

            assertTrue(result.serverResponse().startsWith("250"), result.serverResponse());
            MimeMessage[] received = greenMail.getReceivedMessages();
            assertEquals(1, received.length);
            assertEquals(rendered.messageId(), received[0].getMessageID());
            assertEquals("auto-generated", received[0].getHeader("Auto-Submitted")[0]);
            assertEquals("approver@example.com", received[0].getAllRecipients()[0].toString());
        }

        @Test
        @DisplayName("a wrong password is an authentication failure the caller may retry, and nothing is delivered")
        void wrongPassword() throws Exception {
            var mailer = mailer(greenMail.getSmtps().getPort(), "implicit", false, "eddi", "wrong-password");

            var error = assertThrows(MailSendException.class, () -> TestTls.trustingDefault(() -> mailer.send(notification(), List.of(TO))));

            assertEquals(MailSendException.Category.AUTHENTICATION, error.getCategory());
            assertFalse(error.isPermanent());
            assertEquals(0, greenMail.getReceivedMessages().length);
        }

        @Test
        @DisplayName("an untrusted certificate fails TLS — the default trust is never relaxed")
        void untrustedCertificate() {
            var mailer = mailer(greenMail.getSmtps().getPort(), "implicit", false, "eddi", "right-password");

            var error = assertThrows(MailSendException.class, () -> mailer.send(notification(), List.of(TO)));

            assertEquals(MailSendException.Category.TLS, error.getCategory());
            assertEquals(0, greenMail.getReceivedMessages().length);
        }

        @Test
        @DisplayName("STARTTLS is required: a relay that does not offer it gets no message and no credential")
        void startTlsRequired() {
            var mailer = mailer(greenMail.getSmtp().getPort(), "starttls", false, "eddi", "right-password");

            var error = assertThrows(MailSendException.class, () -> TestTls.trustingDefault(() -> mailer.send(notification(), List.of(TO))));

            assertEquals(MailSendException.Category.TLS, error.getCategory());
            assertFalse(error.isPermanent());
            assertEquals(0, greenMail.getReceivedMessages().length);
        }

        @Test
        @DisplayName("the plaintext opt-out reaches a relay without TLS or AUTH")
        void plaintextOptOut() throws Exception {
            var mailer = mailer(greenMail.getSmtp().getPort(), "none", true, null, null);

            mailer.send(notification(), List.of(TO));

            assertEquals(1, greenMail.getReceivedMessages().length);
        }
    }

    @Nested
    @DisplayName("against a STARTTLS relay")
    class StartTls {

        @Test
        @DisplayName("upgrades before AUTH: every credential crosses the wire encrypted")
        void authOnlyAfterUpgrade() throws Exception {
            try (var server = new StartTlsSmtpServer(TestTls.serverContext(), "250 2.1.5 ok")) {
                var mailer = mailer(server.port(), "starttls", false, "eddi", "right-password");

                var result = TestTls.trustingDefault(() -> mailer.send(notification(), List.of(TO)));

                assertEquals("250 2.0.0 Ok: queued as TEST123", result.serverResponse());
                assertTrue(server.commands.stream().anyMatch(c -> c.line().startsWith("STARTTLS")), server.commands.toString());
                assertTrue(server.commands.stream().filter(c -> c.line().startsWith("AUTH")).allMatch(StartTlsSmtpServer.Command::overTls),
                        server.commands.toString());
                assertTrue(server.commands.stream().anyMatch(c -> c.line().startsWith("AUTH")), "AUTH was sent");
                assertTrue(server.data.toString().contains("A conversation is waiting"), server.data.toString());
            }
        }

        @Test
        @DisplayName("a 550 for the recipient is a permanent rejection")
        void recipientRejected() throws Exception {
            try (var server = new StartTlsSmtpServer(TestTls.serverContext(), "550 5.1.1 no such user")) {
                var mailer = mailer(server.port(), "starttls", false, null, null);

                var error = assertThrows(MailSendException.class, () -> TestTls.trustingDefault(() -> mailer.send(notification(), List.of(TO))));

                assertEquals(MailSendException.Category.REJECTED, error.getCategory());
                assertTrue(error.isPermanent());
            }
        }

        @Test
        @DisplayName("a 451 for the recipient is a deferral the caller retries")
        void recipientDeferred() throws Exception {
            try (var server = new StartTlsSmtpServer(TestTls.serverContext(), "451 4.3.0 try later")) {
                var mailer = mailer(server.port(), "starttls", false, null, null);

                var error = assertThrows(MailSendException.class, () -> TestTls.trustingDefault(() -> mailer.send(notification(), List.of(TO))));

                assertEquals(MailSendException.Category.REJECTED, error.getCategory());
                assertFalse(error.isPermanent());
            }
        }

        @Test
        @DisplayName("an OAuth connection authenticates with XOAUTH2, the token checked against the relay's smtp:// origin")
        void xoauth2() throws Exception {
            try (var server = new StartTlsSmtpServer(TestTls.serverContext(), "250 2.1.5 ok")) {
                ConnectionResolver resolver = mock(ConnectionResolver.class);
                when(resolver.authTypeOf("${connection:exo-smtp}")).thenReturn(Optional.of(AuthType.OAUTH2_CLIENT_CREDENTIALS));
                when(resolver.resolveAccessToken(eq("${connection:exo-smtp}"), any(URI.class), isNull())).thenReturn("eyJ.token");
                @SuppressWarnings("unchecked")
                Instance<ConnectionResolver> instance = mock(Instance.class);
                when(instance.isResolvable()).thenReturn(true);
                when(instance.get()).thenReturn(resolver);
                var mailer = new SystemMailer(connector(), instance,
                        SystemMailer.parse("localhost", server.port(), "starttls", false, FROM.address(), "EDDI", "exo-smtp", null, null));

                TestTls.trustingDefault(() -> mailer.send(notification(), List.of(TO)));

                verify(resolver).resolveAccessToken("${connection:exo-smtp}", URI.create("smtp://localhost:" + server.port()), null);
                var auth = server.commands.stream().filter(c -> c.line().startsWith("AUTH")).findFirst().orElseThrow();
                assertTrue(auth.overTls());
                String[] parts = auth.line().split(" ");
                assertEquals("XOAUTH2", parts[1]);
                String decoded = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                assertEquals("user=eddi@example.com\u0001auth=Bearer eyJ.token\u0001\u0001", decoded);
            }
        }
    }

    @Test
    @DisplayName("an unreachable relay is a connect failure, not permanent")
    void unreachable() throws Exception {
        int closedPort;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = unused.getLocalPort();
        }
        var mailer = mailer(closedPort, "starttls", false, null, null);

        var error = assertThrows(MailSendException.class, () -> mailer.send(notification(), List.of(TO)));

        assertEquals(MailSendException.Category.CONNECT, error.getCategory());
        assertFalse(error.isPermanent());
    }
}
