/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.net;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The socket guard, with no network: every name resolves through an injected
 * resolver, and nothing connects.
 */
class SafeSocketConnectorTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A resolver answering from a fixed table. */
    private static UrlValidationUtils.HostResolver table(Map<String, String> answers) {
        return host -> {
            String answer = answers.get(host);
            if (answer == null) {
                throw new UnknownHostException(host);
            }
            String[] parts = answer.split(",");
            InetAddress[] addresses = new InetAddress[parts.length];
            for (int i = 0; i < parts.length; i++) {
                addresses[i] = ip(parts[i]);
            }
            return addresses;
        };
    }

    private static SafeSocketConnector connector(List<String> allowedHosts, UrlValidationUtils.HostResolver resolver) throws Exception {
        return new SafeSocketConnector(allowedHosts, resolver, UrlValidationUtils::isPrivateAddress, SSLContext.getDefault(), Duration.ofSeconds(2),
                Duration.ofSeconds(2));
    }

    private static SafeSocketConnector connector(Map<String, String> answers) throws Exception {
        return connector(List.of(), table(answers));
    }

    private static SocketPolicyException.Reason refusal(SafeSocketConnector connector, String host, int port,
                                                        SafeSocketConnector.Protocol protocol) {
        return assertThrows(SocketPolicyException.class, () -> connector.resolve(host, port, protocol)).getReason();
    }

    @Nested
    @DisplayName("addresses")
    class Addresses {

        @ParameterizedTest
        @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "100.64.0.1", "169.254.169.254", "168.63.129.16",
                "0.0.0.0", "224.0.0.1", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe",
                "2002:7f00:0001::", "fd00:ec2::254"})
        @DisplayName("a name resolving to a private, loopback, link-local, metadata or embedded-IPv4 address is refused")
        void privateAddressRefused(String address) throws Exception {
            var connector = connector(Map.of("imap.attacker.example", address));

            assertEquals(SocketPolicyException.Reason.PRIVATE_ADDRESS, refusal(connector, "imap.attacker.example", 993,
                    SafeSocketConnector.Protocol.IMAP));
        }

        @Test
        @DisplayName("one private address among public ones is enough to refuse")
        void mixedAnswerRefused() throws Exception {
            var connector = connector(Map.of("imap.attacker.example", "93.184.216.34,10.0.0.5"));

            assertEquals(SocketPolicyException.Reason.PRIVATE_ADDRESS, refusal(connector, "imap.attacker.example", 993,
                    SafeSocketConnector.Protocol.IMAP));
        }

        @Test
        @DisplayName("a public name resolves and pins its first address")
        void publicAllowed() throws Exception {
            var connector = connector(Map.of("outlook.office365.example", "93.184.216.34,93.184.216.35"));

            var endpoint = connector.resolve("Outlook.Office365.Example.", 993, SafeSocketConnector.Protocol.IMAP);

            assertEquals("outlook.office365.example", endpoint.host());
            assertEquals(ip("93.184.216.34"), endpoint.address());
            assertEquals(SafeSocketConnector.TlsMode.IMPLICIT, endpoint.tlsMode());
            assertTrue(endpoint.toString().contains("outlook.office365.example"));
            assertTrue(!endpoint.toString().contains("93.184"), "the resolved address stays out of logs");
        }

        @Test
        @DisplayName("an unresolvable name is refused")
        void unresolvable() throws Exception {
            assertEquals(SocketPolicyException.Reason.UNRESOLVABLE, refusal(connector(Map.of()), "nowhere.example", 993,
                    SafeSocketConnector.Protocol.IMAP));
        }
    }

    @Nested
    @DisplayName("host names")
    class HostNames {

        @ParameterizedTest
        @ValueSource(strings = {"127.0.0.1", "2130706433", "0x7f000001", "0177.0.0.1", "0x7f.0.0.1", "127.1", "[::1]", "::1", "localhost",
                "imap", "", " ", "imap_server.example.com", "-bad.example.com", "imap.example.com:993", "http://imap.example.com",
                "imap..example.com"})
        @DisplayName("IP literals in every spelling, single labels and junk are refused before any lookup")
        void invalidHosts(String host) throws Exception {
            AtomicInteger lookups = new AtomicInteger();
            var connector = connector(List.of(), name -> {
                lookups.incrementAndGet();
                return new InetAddress[]{ip("93.184.216.34")};
            });

            assertEquals(SocketPolicyException.Reason.INVALID_HOST, refusal(connector, host, 993, SafeSocketConnector.Protocol.IMAP), host);
            assertEquals(0, lookups.get(), "no DNS query for a refused name");
        }

        @ParameterizedTest
        @ValueSource(strings = {"metadata.google.internal", "mail.corp.internal", "printer.local"})
        @DisplayName("known internal names are refused before any lookup")
        void blockedNames(String host) throws Exception {
            var connector = connector(Map.of(host, "93.184.216.34"));

            assertEquals(SocketPolicyException.Reason.PRIVATE_ADDRESS, refusal(connector, host, 993, SafeSocketConnector.Protocol.IMAP));
        }

        @Test
        @DisplayName("an IDN host is converted to ASCII")
        void idn() throws Exception {
            var connector = connector(Map.of("imap.xn--bcher-kva.example", "93.184.216.34"));

            assertEquals("imap.xn--bcher-kva.example", connector.resolve("imap.bücher.example", 993, SafeSocketConnector.Protocol.IMAP).host());
        }
    }

    @Nested
    @DisplayName("ports")
    class Ports {

        @Test
        @DisplayName("IMAP: 993 implicit TLS, 143 STARTTLS; nothing else")
        void imapPorts() throws Exception {
            var connector = connector(Map.of("imap.example.com", "93.184.216.34"));

            assertEquals(SafeSocketConnector.TlsMode.IMPLICIT,
                    connector.resolve("imap.example.com", 993, SafeSocketConnector.Protocol.IMAP).tlsMode());
            assertEquals(SafeSocketConnector.TlsMode.STARTTLS,
                    connector.resolve("imap.example.com", 143, SafeSocketConnector.Protocol.IMAP).tlsMode());
            for (int port : new int[]{25, 80, 443, 465, 587, 6379, 0, -1, 65536}) {
                assertEquals(SocketPolicyException.Reason.PORT_NOT_ALLOWED, refusal(connector, "imap.example.com", port,
                        SafeSocketConnector.Protocol.IMAP), "port " + port);
            }
        }

        @Test
        @DisplayName("SMTP: 465 implicit TLS, 587 STARTTLS; no port 25")
        void smtpPorts() throws Exception {
            var connector = connector(Map.of("smtp.example.com", "93.184.216.34"));

            assertEquals(SafeSocketConnector.TlsMode.IMPLICIT,
                    connector.resolve("smtp.example.com", 465, SafeSocketConnector.Protocol.SMTP).tlsMode());
            assertEquals(SafeSocketConnector.TlsMode.STARTTLS,
                    connector.resolve("smtp.example.com", 587, SafeSocketConnector.Protocol.SMTP).tlsMode());
            for (int port : new int[]{25, 993, 143, 2525}) {
                assertEquals(SocketPolicyException.Reason.PORT_NOT_ALLOWED, refusal(connector, "smtp.example.com", port,
                        SafeSocketConnector.Protocol.SMTP), "port " + port);
            }
        }
    }

    @Nested
    @DisplayName("eddi.email.allowed-hosts")
    class Allowlist {

        @Test
        @DisplayName("empty allows any public host")
        void emptyAllowsAny() throws Exception {
            var connector = new SafeSocketConnector(Optional.empty());

            // The production constructor: a name refused on syntax proves the policy
            // is wired without needing DNS.
            assertEquals(SocketPolicyException.Reason.INVALID_HOST, refusal(connector, "10.0.0.1", 993, SafeSocketConnector.Protocol.IMAP));
        }

        @Test
        @DisplayName("exact entries and *.domain patterns; the apex is not matched by a wildcard")
        void entries() throws Exception {
            var resolver = table(Map.of("outlook.office365.com", "93.184.216.34", "imap.mail.example.net", "93.184.216.35", "example.net",
                    "93.184.216.36", "imap.attacker.example", "93.184.216.37"));
            var connector = connector(List.of(" Outlook.Office365.com ", "*.mail.example.net", ""), resolver);

            connector.resolve("outlook.office365.com", 993, SafeSocketConnector.Protocol.IMAP);
            connector.resolve("imap.mail.example.net", 993, SafeSocketConnector.Protocol.IMAP);
            assertEquals(SocketPolicyException.Reason.HOST_NOT_ALLOWED, refusal(connector, "imap.attacker.example", 993,
                    SafeSocketConnector.Protocol.IMAP));
            assertEquals(SocketPolicyException.Reason.HOST_NOT_ALLOWED, refusal(connector, "mail.example.net", 993,
                    SafeSocketConnector.Protocol.IMAP));
            assertEquals(SocketPolicyException.Reason.HOST_NOT_ALLOWED, refusal(connector, "evilmail.example.net", 993,
                    SafeSocketConnector.Protocol.IMAP));
        }

        @Test
        @DisplayName("an allowlisted host that resolves privately is still refused")
        void allowlistDoesNotExemptPrivate() throws Exception {
            var connector = connector(List.of("mail.corp.example"), table(Map.of("mail.corp.example", "10.0.0.5")));

            assertEquals(SocketPolicyException.Reason.PRIVATE_ADDRESS, refusal(connector, "mail.corp.example", 993,
                    SafeSocketConnector.Protocol.IMAP));
        }

        @Test
        @DisplayName("an invalid entry fails loudly instead of widening or emptying the policy")
        void invalidEntry() {
            assertThrows(IllegalArgumentException.class, () -> new SafeSocketConnector(Optional.of(List.of("10.0.0.1"))));
            assertThrows(IllegalArgumentException.class, () -> new SafeSocketConnector(Optional.of(List.of("*.*.example.com"))));
        }
    }

    @Nested
    @DisplayName("pinning")
    class Pinning {

        @Test
        @DisplayName("DNS rebinding: the socket goes to the first answer, and DNS is asked once")
        void rebinding() throws Exception {
            AtomicInteger lookups = new AtomicInteger();
            var connector = connector(List.of(), host -> lookups.incrementAndGet() == 1
                    ? new InetAddress[]{ip("93.184.216.34")}
                    : new InetAddress[]{ip("127.0.0.1")});

            var endpoint = connector.resolve("imap.rebind.example", 993, SafeSocketConnector.Protocol.IMAP);
            // What Angus Mail passes to connect(): the host name again, which it has
            // just re-resolved — here an unresolved address, as with createUnresolved.
            InetSocketAddress target = PinnedSocket.connectTarget(endpoint, InetSocketAddress.createUnresolved("imap.rebind.example", 993));
            InetSocketAddress resolvedAgain = PinnedSocket.connectTarget(endpoint,
                    new InetSocketAddress(InetAddress.getByAddress("imap.rebind.example", new byte[]{127, 0, 0, 1}), 993));

            assertEquals(ip("93.184.216.34"), target.getAddress());
            assertEquals(ip("93.184.216.34"), resolvedAgain.getAddress(), "the second answer is ignored");
            assertEquals(1, lookups.get());
        }

        @Test
        @DisplayName("a socket asked to go to another host or port refuses")
        void mismatch() throws Exception {
            var endpoint = connector(Map.of("imap.example.com", "93.184.216.34")).resolve("imap.example.com", 993, SafeSocketConnector.Protocol.IMAP);

            for (InetSocketAddress other : List.of(InetSocketAddress.createUnresolved("imap.example.com", 143),
                    InetSocketAddress.createUnresolved("internal.example.com", 993), new InetSocketAddress(ip("10.0.0.1"), 993))) {
                var error = assertThrows(SocketPolicyException.class, () -> PinnedSocket.connectTarget(endpoint, other));
                assertEquals(SocketPolicyException.Reason.ENDPOINT_MISMATCH, error.getReason());
            }
            assertEquals(ip("93.184.216.34"), PinnedSocket.connectTarget(endpoint, new InetSocketAddress(ip("93.184.216.34"), 993)).getAddress());
        }

        @Test
        @DisplayName("the factory refuses local binds, and its sockets are pinned")
        void factory() throws Exception {
            var connector = connector(Map.of("imap.example.com", "93.184.216.34"));
            var endpoint = connector.resolve("imap.example.com", 993, SafeSocketConnector.Protocol.IMAP);
            var factory = connector.socketFactory(endpoint);

            assertTrue(factory.createSocket() instanceof PinnedSocket);
            assertThrows(SocketPolicyException.class, () -> factory.createSocket("imap.example.com", 993, ip("10.0.0.1"), 0));
            assertThrows(SocketPolicyException.class, () -> factory.createSocket("other.example.com", 993));
        }
    }

    @Nested
    @DisplayName("Angus Mail properties")
    class AngusProperties {

        @Test
        @DisplayName("implicit TLS: the pinned factory for both keys, no fallback, identity checked, timeouts")
        void implicit() throws Exception {
            var connector = connector(Map.of("imap.example.com", "93.184.216.34"));
            var endpoint = connector.resolve("imap.example.com", 993, SafeSocketConnector.Protocol.IMAP);

            Properties properties = connector.angusProperties(endpoint);

            assertEquals("imap.example.com", properties.getProperty("mail.imap.host"));
            assertEquals("993", properties.getProperty("mail.imap.port"));
            assertTrue(properties.get("mail.imap.socketFactory") instanceof SocketFactory);
            assertSame(properties.get("mail.imap.socketFactory"), properties.get("mail.imap.ssl.socketFactory"));
            assertTrue(!(properties.get("mail.imap.ssl.socketFactory") instanceof SSLSocketFactory),
                    "an SSLSocketFactory would make Angus open its own, unpinned socket");
            assertEquals("false", properties.getProperty("mail.imap.socketFactory.fallback"));
            assertEquals("true", properties.getProperty("mail.imap.ssl.enable"));
            assertEquals("true", properties.getProperty("mail.imap.ssl.checkserveridentity"));
            assertEquals(null, properties.getProperty("mail.imap.ssl.trust"));
            assertEquals("2000", properties.getProperty("mail.imap.connectiontimeout"));
            assertEquals("2000", properties.getProperty("mail.imap.timeout"));
        }

        @Test
        @DisplayName("STARTTLS: required, not merely enabled")
        void startTls() throws Exception {
            var connector = connector(Map.of("smtp.example.com", "93.184.216.34"));
            var endpoint = connector.resolve("smtp.example.com", 587, SafeSocketConnector.Protocol.SMTP);

            Properties properties = connector.angusProperties(endpoint);

            assertEquals("false", properties.getProperty("mail.smtp.ssl.enable"));
            assertEquals("true", properties.getProperty("mail.smtp.starttls.enable"));
            assertEquals("true", properties.getProperty("mail.smtp.starttls.required"));
            assertEquals("false", properties.getProperty("mail.smtp.socketFactory.fallback"));
        }
    }

    @Nested
    @DisplayName("operator policy (the system mailer's own relay)")
    class Operator {

        @Test
        @DisplayName("a private relay on port 25 is refused for an author and allowed for the operator, pinned")
        void privateRelay() throws Exception {
            var connector = connector(Map.of("relay.corp.example", "10.0.0.25"));

            assertEquals(SocketPolicyException.Reason.PORT_NOT_ALLOWED,
                    refusal(connector, "relay.corp.example", 25, SafeSocketConnector.Protocol.SMTP));
            assertEquals(SocketPolicyException.Reason.PRIVATE_ADDRESS,
                    refusal(connector, "relay.corp.example", 587, SafeSocketConnector.Protocol.SMTP));

            var endpoint = connector.resolveOperator("relay.corp.example", 25, SafeSocketConnector.Protocol.SMTP,
                    SafeSocketConnector.TlsMode.STARTTLS);
            assertEquals(SafeSocketConnector.Policy.OPERATOR, endpoint.policy());
            assertEquals(ip("10.0.0.25"), endpoint.address());
            assertEquals(new InetSocketAddress(ip("10.0.0.25"), 25),
                    PinnedSocket.connectTarget(endpoint, InetSocketAddress.createUnresolved("relay.corp.example", 25)));
        }

        @Test
        @DisplayName("IP literals and single-label names are accepted, literals without any lookup")
        void literalsAndShortNames() throws Exception {
            AtomicInteger lookups = new AtomicInteger();
            var connector = connector(List.of(), host -> {
                lookups.incrementAndGet();
                if (!"localhost".equals(host)) {
                    throw new UnknownHostException(host);
                }
                return new InetAddress[]{ip("127.0.0.1")};
            });

            assertEquals(ip("10.1.2.3"), connector.resolveOperator("10.1.2.3", 2525, SafeSocketConnector.Protocol.SMTP,
                    SafeSocketConnector.TlsMode.IMPLICIT).address());
            assertEquals(ip("::1"), connector.resolveOperator("[::1]", 465, SafeSocketConnector.Protocol.SMTP,
                    SafeSocketConnector.TlsMode.IMPLICIT).address());
            assertEquals(0, lookups.get(), "a literal must not be resolved");
            assertEquals(ip("127.0.0.1"), connector.resolveOperator("localhost", 25, SafeSocketConnector.Protocol.SMTP,
                    SafeSocketConnector.TlsMode.PLAINTEXT).address());
        }

        @Test
        @DisplayName("cloud metadata and link-local addresses stay refused, as literal or by resolution")
        void metadataRefused() throws Exception {
            var connector = connector(Map.of("sneaky.example.com", "169.254.169.254"));

            assertEquals(SocketPolicyException.Reason.PRIVATE_ADDRESS, assertThrows(SocketPolicyException.class,
                    () -> connector.resolveOperator("169.254.169.254", 25, SafeSocketConnector.Protocol.SMTP,
                            SafeSocketConnector.TlsMode.STARTTLS))
                    .getReason());
            assertEquals(SocketPolicyException.Reason.PRIVATE_ADDRESS, assertThrows(SocketPolicyException.class,
                    () -> connector.resolveOperator("sneaky.example.com", 25, SafeSocketConnector.Protocol.SMTP,
                            SafeSocketConnector.TlsMode.STARTTLS))
                    .getReason());
        }

        @Test
        @DisplayName("eddi.email.allowed-hosts confines authors, not the operator")
        void allowlistNotApplied() throws Exception {
            var connector = connector(List.of("imap.example.com"), table(Map.of("relay.corp.example", "10.0.0.25")));

            assertEquals(SafeSocketConnector.Policy.OPERATOR, connector.resolveOperator("relay.corp.example", 587,
                    SafeSocketConnector.Protocol.SMTP, SafeSocketConnector.TlsMode.STARTTLS).policy());
        }

        @Test
        @DisplayName("a port outside 1..65535 and a missing TLS mode are refused")
        void badInput() throws Exception {
            var connector = connector(Map.of("relay.corp.example", "10.0.0.25"));

            assertEquals(SocketPolicyException.Reason.PORT_NOT_ALLOWED, assertThrows(SocketPolicyException.class,
                    () -> connector.resolveOperator("relay.corp.example", 0, SafeSocketConnector.Protocol.SMTP,
                            SafeSocketConnector.TlsMode.STARTTLS))
                    .getReason());
            assertThrows(IllegalArgumentException.class,
                    () -> connector.resolveOperator("relay.corp.example", 25, SafeSocketConnector.Protocol.SMTP, null));
        }

        @Test
        @DisplayName("plaintext exists only for an operator endpoint, and then turns both TLS switches off")
        void plaintextOnlyForOperator() throws Exception {
            assertThrows(IllegalArgumentException.class, () -> new SafeSocketConnector.Endpoint("smtp.example.com", 587,
                    SafeSocketConnector.Protocol.SMTP, SafeSocketConnector.TlsMode.PLAINTEXT, ip("93.184.216.34")));

            var connector = connector(Map.of("relay.corp.example", "10.0.0.25"));
            var endpoint = connector.resolveOperator("relay.corp.example", 25, SafeSocketConnector.Protocol.SMTP,
                    SafeSocketConnector.TlsMode.PLAINTEXT);
            Properties properties = connector.angusProperties(endpoint);

            assertEquals("false", properties.getProperty("mail.smtp.ssl.enable"));
            assertEquals("false", properties.getProperty("mail.smtp.starttls.enable"));
            assertEquals("false", properties.getProperty("mail.smtp.socketFactory.fallback"));
        }
    }
}
