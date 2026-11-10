/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C1: the NATS certificate must name a configured host, whichever address jnats
 * dialled.
 */
@DisplayName("ServerNameTrustManager")
class ServerNameTrustManagerTest {

    private static X509Certificate cert(List<List<?>> sans) throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(sans);
        when(certificate.getSubjectX500Principal()).thenReturn(new X500Principal("CN=test"));
        return certificate;
    }

    @Test
    @DisplayName("DNS names match exactly or through one leading wildcard label")
    void dnsMatching() throws Exception {
        X509Certificate exact = cert(List.of(List.of(2, "nats.example.com")));
        assertTrue(ServerNameTrustManager.matches(exact, "NATS.example.com"));
        assertFalse(ServerNameTrustManager.matches(exact, "evil.example.com"));

        X509Certificate wildcard = cert(List.of(List.of(2, "*.eddi-nats")));
        assertTrue(ServerNameTrustManager.matches(wildcard, "eddi-nats-0.eddi-nats"));
        assertFalse(ServerNameTrustManager.matches(wildcard, "a.b.eddi-nats"), "a wildcard is one label");
        assertFalse(ServerNameTrustManager.matches(wildcard, "eddi-nats"));
        assertFalse(ServerNameTrustManager.matches(cert(null), "nats.example.com"), "no SAN, no match (CN is not used)");
    }

    @Test
    @DisplayName("an IP address needs an IP SAN")
    void ipMatching() throws Exception {
        X509Certificate ip = cert(List.of(List.of(7, "10.0.0.5"), List.of(2, "10.0.0.6")));
        assertTrue(ServerNameTrustManager.matches(ip, "10.0.0.5"));
        assertFalse(ServerNameTrustManager.matches(ip, "10.0.0.6"), "a DNS SAN does not name an IP");
        assertTrue(ServerNameTrustManager.matches(cert(List.of(List.of(7, "0:0:0:0:0:0:0:1"))), "::1"));
    }

    @Test
    @DisplayName("hosts are taken from every server URL")
    void hostsOfUrls() {
        assertEquals(List.of("a.example", "10.0.0.1", "b", "::1"),
                ServerNameTrustManager.hostsOf(List.of("tls://a.example:4222", "nats://10.0.0.1:4222", "b:4222", "tls://[::1]:4222")));
    }

    @Test
    @DisplayName("a chain the trust store accepts is still refused when it names another host; the trust store still decides first")
    void checksBothChainAndName() throws Exception {
        X509ExtendedTrustManager delegate = mock(X509ExtendedTrustManager.class);
        var manager = new ServerNameTrustManager(delegate, List.of("nats.example.com"));
        X509Certificate good = cert(List.of(List.of(2, "nats.example.com")));
        X509Certificate other = cert(List.of(List.of(2, "attacker.example.org")));

        assertDoesNotThrow(() -> manager.checkServerTrusted(new X509Certificate[]{good}, "ECDHE", (Socket) null));
        var refused = assertThrows(CertificateException.class,
                () -> manager.checkServerTrusted(new X509Certificate[]{other}, "ECDHE", (Socket) null));
        assertTrue(refused.getMessage().contains("nats.example.com"));

        doThrow(new CertificateException("untrusted")).when(delegate).checkServerTrusted(any(), anyString(), any(Socket.class));
        assertThrows(CertificateException.class, () -> manager.checkServerTrusted(new X509Certificate[]{good}, "ECDHE", new Socket()));
    }
}
