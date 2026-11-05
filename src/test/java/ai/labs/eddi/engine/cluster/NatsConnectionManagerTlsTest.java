/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Options;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NatsConnectionManagerTlsTest {

    private static NatsConnectionManager managerFor(String url) {
        ClusterConfig config = ClusterConfig.defaults().withNatsUrl(url);
        return new NatsConnectionManager(config, new NodeIdentity("node-a", "boot0001"), new SimpleMeterRegistry());
    }

    private static Options optionsFor(String url) throws Exception {
        NatsConnectionManager manager = managerFor(url);
        try {
            return manager.buildOptions();
        } finally {
            manager.shutdown();
        }
    }

    /** Every X.509 trust manager checks the configured host names. */
    private static void assertVerifiesHostNames(String url) throws Exception {
        NatsConnectionManager manager = managerFor(url);
        try {
            TrustManager[] trustManagers = manager.trustManagers();
            assertTrue(trustManagers.length > 0);
            for (TrustManager trustManager : trustManagers) {
                if (trustManager instanceof X509TrustManager) {
                    assertInstanceOf(ServerNameTrustManager.class, trustManager);
                }
            }
        } finally {
            manager.shutdown();
        }
    }

    /**
     * A tls:// URL enables TLS in jnats on its own, with the JDK default context
     * and so without the host-name check; the flag being off must not skip it.
     */
    @Test
    void aTlsUrlGetsTheHostNameVerifyingContextWithoutTheFlag() throws Exception {
        SSLContext context = optionsFor("tls://nats.example:4222").getSslContext();
        assertNotNull(context);
        assertNotSame(SSLContext.getDefault(), context, "the JVM default context checks no host name");
        assertVerifiesHostNames("tls://nats.example:4222");
    }

    /**
     * Without an EDDI key store and without {@code javax.net.ssl.keyStore} there is
     * no client certificate to present (with it, NatsTlsHandshakeTest shows the
     * JVM's certificate going out).
     */
    @Test
    void withoutAnyKeyStoreNoClientCertificateIsPresented() throws Exception {
        String previous = System.getProperty("javax.net.ssl.keyStore");
        System.clearProperty("javax.net.ssl.keyStore");
        try {
            assertNull(NatsConnectionManager.defaultKeyManagerFactory());
        } finally {
            if (previous != null) {
                System.setProperty("javax.net.ssl.keyStore", previous);
            }
        }
    }

    /**
     * jnats' default resolution dials the resolved IP and the TLS upgrade then
     * names the IP; with host-name verification the node dials the name.
     */
    @Test
    void hostNameVerificationDialsTheHostName() throws Exception {
        assertEquals(Options.HostnameResolveMode.HappyEyeballs, optionsFor("tls://nats.example:4222").hostnameResolveMode());

        ClusterConfig unverified = ClusterConfig.defaults().withNatsUrl("tls://nats.example:4222");
        unverified.natsTlsVerifyHostname = false;
        NatsConnectionManager manager = new NatsConnectionManager(unverified, new NodeIdentity("node-a", "boot0001"), new SimpleMeterRegistry());
        try {
            assertNotEquals(Options.HostnameResolveMode.HappyEyeballs, manager.buildOptions().hostnameResolveMode());
            for (TrustManager trustManager : manager.trustManagers()) {
                assertFalse(trustManager instanceof ServerNameTrustManager, "verify-hostname=false must not check names");
            }
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void aWebsocketTlsUrlGetsItToo() throws Exception {
        assertNotNull(optionsFor("wss://nats.example:443").getSslContext());
        assertVerifiesHostNames("wss://nats.example:443");
    }

    @Test
    void schemesThatRequestTls() {
        assertTrue(ClusterConfig.defaults().withNatsUrl("nats://a:4222,TLS://b:4222").natsTlsRequired());
        assertFalse(ClusterConfig.defaults().withNatsUrl("nats://a:4222").natsTlsRequired());
        // opentls:// (jnats' trust-anything scheme) is refused by
        // ClusterConfig.validate().
        assertFalse(ClusterConfig.defaults().withNatsUrl("opentls://a:4222").natsTlsRequired());
    }
}
