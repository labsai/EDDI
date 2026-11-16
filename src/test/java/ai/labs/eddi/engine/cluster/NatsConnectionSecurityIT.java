/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1/C2 against a real NATS server: a {@code tls://} URL is verified for the
 * host name WITHOUT {@code eddi.nats.tls.enabled}, a cert-manager-style PEM CA
 * is a valid trust store, and a host-name mismatch or a wrong password makes
 * the node not ready (instead of degraded and green).
 * <p>
 * The CA and the server certificate (SAN {@code dns:localhost} only) are made
 * with the JDK's keytool, so the test needs nothing but Docker.
 */
@DisplayName("NATS connection security (real server)")
class NatsConnectionSecurityIT {

    private static final String IMAGE = "nats:2.11.17-alpine@sha256:e4bf19f15fd3218814a4e3c9e0064e1334bd8aa20d5984b9f1a0afd084f8cc00";
    private static final String PASSWORD = "it-" + UUID.randomUUID();

    private static Path dir;
    private static GenericContainer<?> tlsNats;
    private static GenericContainer<?> authNats;
    private final List<NatsConnectionManager> managers = new ArrayList<>();

    @BeforeAll
    static void startServers() throws Exception {
        dir = Files.createTempDirectory("nats-tls-it");
        makeCertificates(dir);
        tlsNats = new GenericContainer<>(IMAGE).withExposedPorts(4222)
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("server.pem"), 0644), "/certs/server.pem")
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("server-key.pem"), 0644), "/certs/server-key.pem")
                .withCommand("--tls", "--tlscert=/certs/server.pem", "--tlskey=/certs/server-key.pem")
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        authNats = new GenericContainer<>(IMAGE).withExposedPorts(4222).withCommand("--user", "eddi", "--pass", PASSWORD)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        tlsNats.start();
        authNats.start();
    }

    @AfterAll
    static void stopServers() {
        if (tlsNats != null) {
            tlsNats.stop();
        }
        if (authNats != null) {
            authNats.stop();
        }
    }

    @AfterEach
    void shutdown() {
        managers.forEach(NatsConnectionManager::shutdown);
    }

    private NatsConnectionManager start(ClusterConfig config) {
        NatsConnectionManager manager = new NatsConnectionManager(config, new NodeIdentity("it", "b1"), new SimpleMeterRegistry());
        managers.add(manager);
        manager.start();
        return manager;
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    private static ClusterConfig tls(String host) {
        return ClusterConfig.defaults().withMessagingType("nats").withNatsUrl("tls://" + host + ":" + tlsNats.getMappedPort(4222))
                // tls.enabled stays FALSE: the tls:// scheme alone must bring EDDI's verifying
                // context
                .withTls(false, true, dir.resolve("ca.crt").toString(), null);
    }

    @Test
    @DisplayName("tls:// with a PEM CA and a matching host name connects")
    void matchingHostConnects() throws Exception {
        NatsConnectionManager manager = start(tls("localhost"));
        assertTrue(eventually(manager::isConnected),
                "tls://localhost with the PEM CA must connect: " + manager.readinessProblem() + " / last error: " + manager.lastError());
        assertTrue(manager.readinessProblem().isEmpty());
    }

    @Test
    @DisplayName("tls:// to a host the certificate does not name is refused, and the node is not ready")
    void mismatchedHostIsRefused() throws Exception {
        // The certificate names dns:localhost only; 127.0.0.1 is the same server under
        // another name — what a man in the middle with any trusted certificate is.
        NatsConnectionManager manager = start(tls("127.0.0.1").withReadiness(false, true));
        assertTrue(eventually(() -> manager.readinessProblem().map(p -> p.contains("tls")).orElse(false)),
                "a host-name mismatch must surface as a TLS misconfiguration, got " + manager.readinessProblem());
        assertFalse(manager.isConnected());
    }

    @Test
    @DisplayName("a wrong password makes the node not ready instead of degraded and green")
    void wrongPasswordFailsReadiness() throws Exception {
        ClusterConfig config = ClusterConfig.defaults().withMessagingType("nats")
                .withNatsUrl("nats://localhost:" + authNats.getMappedPort(4222)).withCredentials("eddi", "wrong")
                .withReadiness(false, true);
        NatsConnectionManager manager = start(config);
        assertTrue(eventually(() -> manager.readinessProblem().map(p -> p.contains("auth")).orElse(false)),
                "an authorization violation must surface as an auth misconfiguration, got " + manager.readinessProblem());

        NatsConnectionManager good = start(ClusterConfig.defaults().withMessagingType("nats")
                .withNatsUrl("nats://localhost:" + authNats.getMappedPort(4222)).withCredentials("eddi", PASSWORD));
        assertTrue(eventually(good::isConnected), "the right password connects");
        assertTrue(good.readinessProblem().isEmpty());
    }

    // ---- certificates, with keytool only ----

    private static void makeCertificates(Path dir) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        String ca = dir.resolve("ca.p12").toString();
        String server = dir.resolve("server.p12").toString();
        String csr = dir.resolve("server.csr").toString();
        String cer = dir.resolve("server.pem").toString();
        run(keytool, "-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=EDDI IT CA", "-ext",
                "bc:c", "-validity", "2", "-keystore", ca, "-storetype", "PKCS12", "-storepass", "changeit");
        run(keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-validity", "2", "-keystore", server, "-storetype", "PKCS12", "-storepass", "changeit");
        run(keytool, "-certreq", "-alias", "server", "-keystore", server, "-storepass", "changeit", "-file", csr);
        run(keytool, "-gencert", "-alias", "ca", "-keystore", ca, "-storepass", "changeit", "-infile", csr, "-outfile", cer, "-ext",
                "SAN=dns:localhost", "-validity", "2", "-rfc");

        KeyStore caStore = load(ca);
        Files.writeString(dir.resolve("ca.crt"), pem("CERTIFICATE", caStore.getCertificate("ca").getEncoded()), StandardCharsets.US_ASCII);
        KeyStore serverStore = load(server);
        Files.writeString(dir.resolve("server-key.pem"),
                pem("PRIVATE KEY", serverStore.getKey("server", "changeit".toCharArray()).getEncoded()), StandardCharsets.US_ASCII);
    }

    private static KeyStore load(String path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, "changeit".toCharArray());
        }
        return store;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
        }
    }
}
