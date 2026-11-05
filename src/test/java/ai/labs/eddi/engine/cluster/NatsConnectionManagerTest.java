/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.cluster.NatsConnectionManager.ErrorCause;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.AuthenticationException;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NatsConnectionManager")
class NatsConnectionManagerTest {

    private final List<NatsConnectionManager> managers = new ArrayList<>();

    private NatsConnectionManager manager(ClusterConfig config) {
        NatsConnectionManager manager = new NatsConnectionManager(config, new NodeIdentity("n1", "b1"), new SimpleMeterRegistry());
        managers.add(manager);
        return manager;
    }

    @AfterEach
    void shutDown() {
        managers.forEach(NatsConnectionManager::shutdown);
    }

    private static ClusterConfig nats(String url) {
        return ClusterConfig.defaults().withMessagingType("nats").withNatsUrl(url);
    }

    @Nested
    @DisplayName("TLS (C1)")
    class Tls {

        @Test
        @DisplayName("a tls:// server gets EDDI's hostname-verifying context even without eddi.nats.tls.enabled")
        void tlsUrlAloneVerifiesTheHostName() throws Exception {
            NatsConnectionManager manager = manager(nats("tls://nats.example.com:4222"));
            assertNotNull(manager.buildOptions().getSslContext(), "EDDI's own context, not jnats' JVM-default fallback");
            assertVerifies(manager, "nats.example.com");
        }

        @Test
        @DisplayName("a wss:// server too")
        void wssUrlVerifiesTheHostName() throws Exception {
            NatsConnectionManager manager = manager(nats("wss://nats.example.com:443"));
            assertNotNull(manager.buildOptions().getSslContext());
            assertVerifies(manager, "nats.example.com");
        }

        @Test
        @DisplayName("one tls:// server in a list is enough")
        void mixedListUsesTls() throws Exception {
            NatsConnectionManager manager = manager(nats("nats://a:4222,tls://b:4222"));
            assertNotNull(manager.buildOptions().getSslContext());
            assertVerifies(manager, "a", "b");
        }

        @Test
        @DisplayName("plain nats:// without tls.enabled stays plain")
        void plainStaysPlain() throws Exception {
            assertNull(manager(nats("nats://nats.example.com:4222")).buildOptions().getSslContext());
        }

        @Test
        @DisplayName("verify-hostname=false is the explicit opt-out (and is warned about)")
        void optOut() throws Exception {
            ClusterConfig config = nats("tls://nats.example.com:4222").withTls(false, false, null, null);
            NatsConnectionManager manager = manager(config);
            assertNotNull(manager.buildOptions().getSslContext());
            assertFalse(Arrays.stream(manager.trustManagers()).anyMatch(ServerNameTrustManager.class::isInstance));
            assertTrue(config.validationWarnings().stream().anyMatch(w -> w.contains("verify-hostname=false")));
        }

        @Test
        @DisplayName("a PEM CA file (a cert-manager ca.crt) is a valid trust store")
        void pemTrustStore(@TempDir Path dir) throws Exception {
            X509Certificate ca = anyTrustedCertificate();
            Path pem = dir.resolve("ca.crt");
            Files.writeString(pem, "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(ca.getEncoded()) + "\n-----END CERTIFICATE-----\n", StandardCharsets.US_ASCII);

            KeyStore store = NatsConnectionManager.loadTrustStore(pem.toString(), "");
            assertEquals(1, store.size());
            assertEquals(ca, store.getCertificate(store.aliases().nextElement()));

            NatsConnectionManager manager = manager(nats("tls://nats.example.com:4222").withTls(true, true, pem.toString(), null));
            assertNotNull(manager.buildOptions().getSslContext());
            assertVerifies(manager, "nats.example.com");
        }

        @Test
        @DisplayName("a PEM file without the .crt suffix is recognised by its content")
        void pemByContent(@TempDir Path dir) throws Exception {
            X509Certificate ca = anyTrustedCertificate();
            Path pem = dir.resolve("ca-bundle");
            Files.writeString(pem, "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(ca.getEncoded())
                    + "\n-----END CERTIFICATE-----\n", StandardCharsets.US_ASCII);
            assertEquals(1, NatsConnectionManager.loadTrustStore(pem.toString(), "").size());
        }

        @Test
        @DisplayName("an empty PEM file is refused, not read as 'trust nothing'")
        void emptyPemRefused(@TempDir Path dir) throws Exception {
            Path pem = dir.resolve("ca.pem");
            Files.writeString(pem, "", StandardCharsets.US_ASCII);
            assertThrows(CertificateException.class,
                    () -> NatsConnectionManager.loadTrustStore(pem.toString(), ""));
        }

        private void assertVerifies(NatsConnectionManager manager, String... hosts) throws Exception {
            var verifying = Arrays.stream(manager.trustManagers()).filter(ServerNameTrustManager.class::isInstance).toList();
            assertEquals(1, verifying.size(), "the X.509 trust manager must check the server's name");
            assertEquals(List.of(hosts), ServerNameTrustManager.hostsOf(manager.config().natsServers()));
        }

        private X509Certificate anyTrustedCertificate() throws Exception {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (var tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager x509 && x509.getAcceptedIssuers().length > 0) {
                    return x509.getAcceptedIssuers()[0];
                }
            }
            throw new IllegalStateException("the JVM trusts no CA");
        }
    }

    @Nested
    @DisplayName("connection options (C5, C11)")
    class ConnectionOptions {

        @Test
        @DisplayName("pings every 5 s and gives up after 2 missed pings by default")
        void pingDefaults() throws Exception {
            Options options = manager(nats("nats://localhost:4222")).buildOptions();
            assertEquals(Duration.ofSeconds(5), options.getPingInterval());
            assertEquals(2, options.getMaxPingsOut());
        }

        @Test
        @DisplayName("the ping settings and the inbox prefix are configurable")
        void configurable() throws Exception {
            Options options = manager(nats("nats://localhost:4222").withPing(Duration.ofSeconds(3), 3).withInboxPrefix("_INBOX_ACME"))
                    .buildOptions();
            assertEquals(Duration.ofSeconds(3), options.getPingInterval());
            assertEquals(3, options.getMaxPingsOut());
            assertEquals("_INBOX_ACME.", options.getInboxPrefix());
        }

        @Test
        @DisplayName("the default inbox prefix is jnats' own, so existing subject permissions keep working")
        void defaultInbox() throws Exception {
            assertEquals("_INBOX.", manager(nats("nats://localhost:4222")).buildOptions().getInboxPrefix());
        }
    }

    @Nested
    @DisplayName("failing loudly (C2)")
    class FailLoudly {

        @Test
        @DisplayName("a node that never connected since boot is not ready")
        void neverConnectedIsNotReady() {
            NatsConnectionManager manager = manager(nats("nats://localhost:4222"));
            assertTrue(manager.readinessProblem().isPresent());
            assertFalse(manager.hasEverConnected());
        }

        @Test
        @DisplayName("require-first-connect=false restores serving degraded from boot")
        void firstConnectOptOut() {
            NatsConnectionManager manager = manager(nats("nats://localhost:4222").withReadiness(false, true));
            assertTrue(manager.readinessProblem().isEmpty());
        }

        @Test
        @DisplayName("an auth error makes the node not ready and is counted by cause")
        void authErrorFailsReadiness() {
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            NatsConnectionManager manager = new NatsConnectionManager(nats("nats://localhost:4222").withReadiness(false, true),
                    new NodeIdentity("n1", "b1"), meters);
            managers.add(manager);
            manager.registerMeters();
            assertEquals(0.0, meters.get("eddi.cluster.nats.errors").tag("cause", "auth").counter().count(),
                    "registered eagerly, at 0, so increase() sees the first error");

            manager.recordError(ErrorCause.AUTH, "Authorization Violation", null);

            assertEquals(1.0, meters.get("eddi.cluster.nats.errors").tag("cause", "auth").counter().count());
            assertEquals(1.0, meters.get("eddi.cluster.nats.misconfigured").gauge().value());
            assertTrue(manager.readinessProblem().orElseThrow().contains("auth"));
        }

        @Test
        @DisplayName("an ordinary network error after the first connect never empties the load balancer")
        void ioErrorKeepsReadiness() {
            NatsConnectionManager manager = manager(nats("nats://localhost:4222").withReadiness(false, true));
            manager.registerMeters();
            manager.recordError(ErrorCause.IO, "Connection refused", null);
            manager.recordError(ErrorCause.TIMEOUT, "timed out", null);
            assertTrue(manager.readinessProblem().isEmpty());
        }

        @Test
        @DisplayName("fail-on-misconfiguration=false is the opt-out")
        void misconfigurationOptOut() {
            NatsConnectionManager manager = manager(nats("nats://localhost:4222").withReadiness(false, false));
            manager.registerMeters();
            manager.recordError(ErrorCause.TLS, "PKIX path building failed", null);
            assertTrue(manager.readinessProblem().isEmpty());
        }

        @Test
        @DisplayName("TLS material that cannot be loaded makes the node not ready instead of silently degraded")
        void unreadableTrustStore(@TempDir Path dir) {
            NatsConnectionManager manager = manager(nats("tls://localhost:4222").withReadiness(false, true)
                    .withTls(true, true, dir.resolve("missing.p12").toString(), "x"));
            manager.start();
            assertTrue(manager.readinessProblem().orElseThrow().contains("config"));
        }

        @Test
        @DisplayName("errors are classified by type, cause chain and server message")
        void classification() {
            assertEquals(ErrorCause.AUTH, NatsConnectionManager.classify(new AuthenticationException("bad")));
            assertEquals(ErrorCause.TLS, NatsConnectionManager.classify(new IOException("wrapped", new SSLHandshakeException("PKIX"))));
            assertEquals(ErrorCause.TLS, NatsConnectionManager.classify(new CertificateException("No subject alternative names")));
            // An SSLException is TLS only when it is about a certificate or the handshake
            // policy; a socket the peer closed or reset (a NATS restart) is ordinary I/O.
            assertEquals(ErrorCause.IO, NatsConnectionManager.classify(new SSLException("Connection reset")));
            assertEquals(ErrorCause.IO, NatsConnectionManager.classify(new SSLHandshakeException("Remote host terminated the handshake")));
            assertEquals(ErrorCause.TLS, NatsConnectionManager.classify(new SSLPeerUnverifiedException("peer not authenticated")));
            assertEquals(ErrorCause.TLS, NatsConnectionManager.classify(
                    new SSLHandshakeException("Received fatal alert: certificate_unknown")));
            assertEquals(ErrorCause.TIMEOUT, NatsConnectionManager.classify(new TimeoutException()));
            assertEquals(ErrorCause.IO, NatsConnectionManager.classify(new ConnectException("Connection refused")));
            assertEquals(ErrorCause.AUTH, NatsConnectionManager.classify(new IOException("Authorization Violation")));
            assertEquals(ErrorCause.OTHER, NatsConnectionManager.classify(new IllegalStateException("?")));
            assertEquals(ErrorCause.AUTH, NatsConnectionManager.classify("'Authorization Violation'"));
            assertEquals(ErrorCause.AUTH, NatsConnectionManager.classify("'User Authentication Expired'"));
            assertEquals(ErrorCause.PERMISSION, NatsConnectionManager.classify("'Permissions Violation for Publish to \"x\"'"));
            assertEquals(ErrorCause.SERVER, NatsConnectionManager.classify("'Maximum Connections Exceeded'"));
            assertTrue(ErrorCause.AUTH.isMisconfiguration() && ErrorCause.TLS.isMisconfiguration() && ErrorCause.CONFIG.isMisconfiguration());
            assertFalse(ErrorCause.IO.isMisconfiguration() || ErrorCause.TIMEOUT.isMisconfiguration());
        }
    }
}
