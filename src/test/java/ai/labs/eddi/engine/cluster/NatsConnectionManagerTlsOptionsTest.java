/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Options;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which servers get EDDI's own TLS context: jnats builds one for a
 * {@code tls://} URL by itself, with no host name check, unless one is
 * supplied.
 */
class NatsConnectionManagerTlsOptionsTest {

    private Options optionsFor(boolean tlsEnabled, String... servers) throws Exception {
        ClusterConfig config = mock(ClusterConfig.class);
        when(config.natsServers()).thenReturn(List.of(servers));
        when(config.natsTlsEnabled()).thenReturn(tlsEnabled);
        when(config.natsTlsVerifyHostname()).thenReturn(true);
        when(config.natsConnectionTimeout()).thenReturn(Duration.ofSeconds(2));
        when(config.natsReconnectWait()).thenReturn(Duration.ofSeconds(1));
        NodeIdentity node = mock(NodeIdentity.class);
        when(node.nodeId()).thenReturn("node-1");
        return new NatsConnectionManager(config, node, new SimpleMeterRegistry()).buildOptions();
    }

    @Test
    @DisplayName("a tls:// server gets the host-name-verifying context even when eddi.nats.tls.enabled is false")
    void tlsSchemeIsVerifiedWithoutTheFlag() throws Exception {
        assertInstanceOf(HostnameVerifyingSslContext.class, optionsFor(false, "tls://nats-0:4222", "tls://nats-1:4222").getSslContext());
    }

    @Test
    @DisplayName("wss:// is treated like tls://")
    void wssSchemeIsVerifiedToo() throws Exception {
        assertInstanceOf(HostnameVerifyingSslContext.class, optionsFor(false, "wss://nats.example.com:443").getSslContext());
    }

    @Test
    @DisplayName("opentls:// stays the explicit trust-all opt-in, left to jnats")
    void opentlsIsNotTouched() throws Exception {
        // jnats supplies its own trust-all context for opentls://; it must not be
        // replaced
        assertFalse(optionsFor(false, "opentls://nats-0:4222").getSslContext() instanceof HostnameVerifyingSslContext);
    }

    @Test
    @DisplayName("a mix with a plain nats:// server is not forced onto TLS")
    void mixedSchemesAreNotForced() throws Exception {
        assertFalse(optionsFor(false, "tls://nats-0:4222", "nats://nats-1:4222").getSslContext() instanceof HostnameVerifyingSslContext);
    }

    @Test
    @DisplayName("a plain nats:// server without the flag gets no TLS context")
    void plainStaysPlain() throws Exception {
        assertNull(optionsFor(false, "nats://nats-0:4222").getSslContext());
    }

    @Test
    @DisplayName("with the flag on, the context is set whatever the scheme")
    void flagStillTurnsItOn() throws Exception {
        assertInstanceOf(HostnameVerifyingSslContext.class, optionsFor(true, "nats://nats-0:4222").getSslContext());
    }
}
