/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Nats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real TLS handshake between jnats, configured by
 * {@link NatsConnectionManager#buildOptions()}, and an in-process stand-in for
 * a NATS server: it sends {@code INFO} with {@code tls_required}, upgrades the
 * socket to TLS like nats-server does, and answers the client's {@code PING}.
 * The certificates are generated per run with the JDK's keytool, so no key
 * material is committed.
 */
class NatsTlsHandshakeTest {

    private static final String STORE_PASSWORD = "changeit";

    @TempDir
    static Path dir;

    private static Path goodServer;
    private static Path wrongHostServer;
    private static Path client;
    private static Path clientTrust;
    private static Path serverTrust;

    private final List<FakeNatsServer> servers = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void certificates() throws Exception {
        goodServer = keyPair("good", "CN=localhost", "SAN=dns:localhost");
        wrongHostServer = keyPair("wrong", "CN=other.example", "SAN=dns:other.example");
        client = keyPair("client", "CN=eddi-node", null);
        // The client trusts BOTH server certificates: the wrong-host one is trusted,
        // only its name does not match — exactly the case host-name checking is for.
        clientTrust = trustStore("client-trust", goodServer, wrongHostServer);
        serverTrust = trustStore("server-trust", client);
    }

    @AfterEach
    void stopServers() {
        servers.forEach(FakeNatsServer::close);
    }

    @Test
    void acceptsACertificateWhoseOnlySanIsTheConfiguredHostName() throws Exception {
        FakeNatsServer server = start(goodServer, false);

        try (Connection connection = connect(server, false)) {
            assertEquals(Connection.Status.CONNECTED, connection.getStatus());
        }
    }

    @Test
    void refusesATrustedCertificateIssuedForAnotherHost() throws Exception {
        FakeNatsServer server = start(wrongHostServer, false);

        assertThrows(Exception.class, () -> connect(server, false).close());
        assertTrue(server.handshakes.isEmpty(), "no TLS session may be established with the impersonating broker");
    }

    @Test
    void presentsTheClientCertificateToABrokerThatRequiresMutualTls() throws Exception {
        FakeNatsServer server = start(goodServer, true);

        try (Connection connection = connect(server, true)) {
            assertEquals(Connection.Status.CONNECTED, connection.getStatus());
        }
        assertEquals(1, server.handshakes.size());
        assertTrue(server.handshakes.getFirst().contains("CN=eddi-node"), server.handshakes.toString());
    }

    private Connection connect(FakeNatsServer server, boolean withClientCertificate) throws Exception {
        ClusterConfig config = ClusterConfig.defaults().withNatsUrl("tls://localhost:" + server.port());
        config.natsTlsTruststorePath = Optional.of(clientTrust.toString());
        config.natsTlsTruststorePassword = Optional.of(STORE_PASSWORD);
        if (withClientCertificate) {
            config.natsTlsKeystorePath = Optional.of(client.toString());
            config.natsTlsKeystorePassword = Optional.of(STORE_PASSWORD);
        }
        config.natsConnectionTimeout = Duration.ofSeconds(3);
        NatsConnectionManager manager = new NatsConnectionManager(config, new NodeIdentity("node-a", "boot0001"), new SimpleMeterRegistry());
        try {
            return Nats.connect(manager.buildOptions());
        } finally {
            manager.shutdown();
        }
    }

    private FakeNatsServer start(Path serverKeys, boolean requireClientCertificate) throws Exception {
        FakeNatsServer server = new FakeNatsServer(serverContext(serverKeys), requireClientCertificate);
        servers.add(server);
        return server;
    }

    private static SSLContext serverContext(Path serverKeys) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(load(serverKeys), STORE_PASSWORD.toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(load(serverTrust));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    private static Path keyPair(String name, String dname, String san) throws Exception {
        Path store = dir.resolve(name + ".p12");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair",
                "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", dname, "-validity", "2", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD));
        if (san != null) {
            command.addAll(List.of("-ext", san));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "keytool timed out");
        assertEquals(0, process.exitValue(), output);
        return store;
    }

    private static Path trustStore(String name, Path... trusted) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        for (Path keys : trusted) {
            KeyStore source = load(keys);
            String alias = source.aliases().nextElement();
            Certificate certificate = source.getCertificate(alias);
            trust.setCertificateEntry(alias, certificate);
        }
        Path store = dir.resolve(name + ".p12");
        try (OutputStream out = Files.newOutputStream(store)) {
            trust.store(out, STORE_PASSWORD.toCharArray());
        }
        return store;
    }

    private static KeyStore load(Path path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, STORE_PASSWORD.toCharArray());
        }
        return store;
    }

    /**
     * INFO in plain text, then TLS, then PONG for every PING — what jnats needs to
     * connect.
     */
    private static final class FakeNatsServer implements AutoCloseable {
        private final ServerSocket socket;
        private final SSLContext context;
        private final boolean requireClientCertificate;
        final List<String> handshakes = new CopyOnWriteArrayList<>();
        private final List<Socket> open = new CopyOnWriteArrayList<>();

        FakeNatsServer(SSLContext context, boolean requireClientCertificate) throws Exception {
            this.context = context;
            this.requireClientCertificate = requireClientCertificate;
            this.socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(this::acceptLoop, "fake-nats");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void acceptLoop() {
            while (!socket.isClosed()) {
                try {
                    Socket plain = socket.accept();
                    open.add(plain);
                    Thread session = new Thread(() -> serve(plain), "fake-nats-session");
                    session.setDaemon(true);
                    session.start();
                } catch (Exception e) {
                    return;
                }
            }
        }

        private void serve(Socket plain) {
            try {
                plain.setSoTimeout(10_000);
                OutputStream plainOut = plain.getOutputStream();
                plainOut.write(("INFO {\"server_id\":\"fake\",\"version\":\"2.11.0\",\"proto\":1,\"max_payload\":1048576,"
                        + "\"headers\":true,\"tls_required\":true}\r\n").getBytes(StandardCharsets.US_ASCII));
                plainOut.flush();
                SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(plain, null, true);
                tls.setUseClientMode(false);
                tls.setNeedClientAuth(requireClientCertificate);
                tls.startHandshake();
                handshakes.add(requireClientCertificate
                        ? String.valueOf(((X509Certificate) tls.getSession().getPeerCertificates()[0]).getSubjectX500Principal())
                        : "ok");
                BufferedReader in = new BufferedReader(new InputStreamReader(tls.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = tls.getOutputStream();
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("PING")) {
                        out.write("PONG\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                }
            } catch (Exception e) {
                // A refused handshake ends the session; the test asserts on the client side.
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
                // closing anyway
            }
            for (Socket s : open) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // closing anyway
                }
            }
        }
    }
}
