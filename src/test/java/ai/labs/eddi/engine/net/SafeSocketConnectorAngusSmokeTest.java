/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.net;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves Angus Mail can be wired to {@link SafeSocketConnector} — no IMAP
 * feature code, only that the properties do what they claim: Angus connects
 * through the pinned factory (the host name used here does not resolve at all),
 * STARTTLS is required before any credential is sent, and a refusal inside the
 * factory is not followed by a fallback socket.
 * <p>
 * A loopback server stands in for the mail host, so this test needs a local
 * socket but no network. Loopback is only reachable because the test builds the
 * connector with a permissive address rule and the endpoint directly —
 * {@link SafeSocketConnector#resolve} would refuse both.
 */
class SafeSocketConnectorAngusSmokeTest {

    private static final String HOST = "imap.smoke-test.invalid";

    private ServerSocket server;
    private Thread serverThread;
    private final AtomicInteger connections = new AtomicInteger();
    private final List<String> commands = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        server.setSoTimeout(5000);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.close();
        if (serverThread != null) {
            serverThread.join(5000);
        }
    }

    private void serve(String capabilities) {
        serverThread = new Thread(() -> {
            try (Socket client = server.accept();
                    OutputStream out = client.getOutputStream();
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))) {
                connections.incrementAndGet();
                client.setSoTimeout(5000);
                out.write(("* OK [CAPABILITY " + capabilities + "] smoke test ready\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String line;
                while ((line = in.readLine()) != null) {
                    commands.add(line);
                    String[] parts = line.split(" ", 3);
                    String tag = parts[0];
                    String command = parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "";
                    switch (command) {
                        case "CAPABILITY" ->
                            out.write(("* CAPABILITY " + capabilities + "\r\n" + tag + " OK done\r\n").getBytes(StandardCharsets.US_ASCII));
                        case "STARTTLS" -> {
                            out.write((tag + " OK begin TLS\r\n").getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            // Not a TLS server: whatever comes next is a ClientHello,
                            // answered with plaintext, so the handshake must fail.
                            out.write("* garbage that is not TLS\r\n".getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            return;
                        }
                        case "LOGOUT" -> {
                            out.write(("* BYE\r\n" + tag + " OK bye\r\n").getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            return;
                        }
                        default -> out.write((tag + " BAD unexpected\r\n").getBytes(StandardCharsets.US_ASCII));
                    }
                    out.flush();
                }
            } catch (SocketTimeoutException e) {
                // Nobody connected — the case the fallback test wants.
            } catch (IOException e) {
                // The client hung up; nothing to assert here.
            }
        }, "imap-smoke-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private SafeSocketConnector.Endpoint loopbackEndpoint() {
        return new SafeSocketConnector.Endpoint(HOST, server.getLocalPort(), SafeSocketConnector.Protocol.IMAP, SafeSocketConnector.TlsMode.STARTTLS,
                InetAddress.getLoopbackAddress());
    }

    private SafeSocketConnector connector() throws Exception {
        return new SafeSocketConnector(List.of(), host -> {
            throw new AssertionError("Angus must not get a second resolution through the connector");
        }, address -> false, SSLContext.getDefault(), Duration.ofSeconds(3), Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("Angus connects through the pinned factory, and refuses to log in without STARTTLS")
    void startTlsRequired() throws Exception {
        serve("IMAP4rev1 AUTH=PLAIN");
        SafeSocketConnector connector = connector();
        Session session = Session.getInstance(connector.angusProperties(loopbackEndpoint()));
        Store store = session.getStore("imap");

        MessagingException error = assertThrows(MessagingException.class, () -> store.connect("user@example.com", "fixturefixture"));

        assertEquals(1, connections.get(), "the unresolvable host reached the pinned loopback address");
        assertTrue(String.valueOf(error.getMessage()).toUpperCase(Locale.ROOT).contains("STARTTLS"), error.getMessage());
        assertFalse(
                commands.stream().anyMatch(c -> c.toUpperCase(Locale.ROOT).contains("LOGIN") || c.toUpperCase(Locale.ROOT).contains("AUTHENTICATE")),
                "no credential before TLS: " + commands);
    }

    @Test
    @DisplayName("STARTTLS goes through a verifying TLS layer: a server that is not TLS fails the handshake, no credential sent")
    void startTlsVerified() throws Exception {
        serve("IMAP4rev1 STARTTLS LOGINDISABLED");
        SafeSocketConnector connector = connector();
        Session session = Session.getInstance(connector.angusProperties(loopbackEndpoint()));
        Store store = session.getStore("imap");

        assertThrows(MessagingException.class, () -> store.connect("user@example.com", "fixturefixture"));

        assertTrue(commands.stream().anyMatch(c -> c.toUpperCase(Locale.ROOT).contains("STARTTLS")), commands.toString());
        assertFalse(commands.stream().anyMatch(c -> c.toUpperCase(Locale.ROOT).contains("LOGIN ")), commands.toString());
    }

    @Test
    @DisplayName("a refusal inside the factory is final: no fallback socket reaches the server")
    void noFallback() throws Exception {
        serve("IMAP4rev1 AUTH=PLAIN");
        int closedPort;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = unused.getLocalPort();
        }
        // The endpoint was vetted for another port. Asked for the server's port, the
        // pinned socket refuses; a fallback socket of Angus's own would connect to
        // the server, which is what this test watches for.
        var endpoint = new SafeSocketConnector.Endpoint(HOST, closedPort, SafeSocketConnector.Protocol.IMAP, SafeSocketConnector.TlsMode.STARTTLS,
                InetAddress.getLoopbackAddress());
        Session session = Session.getInstance(connector().angusProperties(endpoint));
        Store store = session.getStore("imap");

        assertThrows(MessagingException.class, () -> store.connect("localhost", server.getLocalPort(), "user@example.com", "fixturefixture"));
        serverThread.join(7000);

        assertEquals(0, connections.get(), "Angus fell back to an unguarded socket");
    }
}
