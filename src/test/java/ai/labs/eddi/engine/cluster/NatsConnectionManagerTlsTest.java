/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Options;
import org.junit.jupiter.api.Test;

import java.util.List;

import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NatsConnectionManagerTlsTest {

    private static Options optionsFor(String url) throws Exception {
        ClusterConfig config = ClusterConfig.defaults().withNatsUrl(url);
        NatsConnectionManager manager = new NatsConnectionManager(config, new NodeIdentity("node-a", "boot0001"), new SimpleMeterRegistry());
        try {
            return manager.buildOptions();
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
        Options options = optionsFor("tls://nats.example:4222");
        assertInstanceOf(HostnameVerifyingSslContext.class, options.getSslContext());
    }

    /**
     * Without an EDDI trust or key store the JVM default context is the base, so a
     * javax.net.ssl.keyStore client certificate (mTLS) still goes out.
     */
    @Test
    void withoutEddiTlsMaterialTheJvmDefaultContextIsKept() throws Exception {
        ClusterConfig config = ClusterConfig.defaults().withNatsUrl("tls://nats.example:4222");
        NatsConnectionManager manager = new NatsConnectionManager(config, new NodeIdentity("node-a", "boot0001"), new SimpleMeterRegistry());
        try {
            assertSame(SSLContext.getDefault(), manager.baseSslContext());
        } finally {
            manager.shutdown();
        }
    }

    /**
     * jnats' default resolution dials the resolved IP and the TLS upgrade then
     * names the IP; the host-name check must see the name in the URL.
     */
    @Test
    void hostNameVerificationDialsTheHostName() throws Exception {
        assertEquals(Options.HostnameResolveMode.HappyEyeballs, optionsFor("tls://nats.example:4222").hostnameResolveMode());

        ClusterConfig unverified = ClusterConfig.defaults().withNatsUrl("tls://nats.example:4222");
        unverified.natsTlsVerifyHostname = false;
        NatsConnectionManager manager = new NatsConnectionManager(unverified, new NodeIdentity("node-a", "boot0001"), new SimpleMeterRegistry());
        try {
            assertNotEquals(Options.HostnameResolveMode.HappyEyeballs, manager.buildOptions().hostnameResolveMode());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void aWebsocketTlsUrlGetsItToo() throws Exception {
        assertInstanceOf(HostnameVerifyingSslContext.class, optionsFor("wss://nats.example:443").getSslContext());
    }

    @Test
    void schemesThatRequestTls() {
        assertTrue(NatsConnectionManager.anyUrlRequestsTls(List.of("nats://a:4222", "TLS://b:4222")));
        assertFalse(NatsConnectionManager.anyUrlRequestsTls(List.of("nats://a:4222")));
        assertFalse(NatsConnectionManager.anyUrlRequestsTls(List.of("opentls://a:4222")), "opentls is jnats' explicit trust-any scheme");
    }
}
