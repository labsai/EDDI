/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Configuration checks at boot (C1, C15). */
@DisplayName("ClusterConfig validation")
class ClusterConfigValidationTest {

    private static ClusterConfig nats() {
        return ClusterConfig.defaults().withMessagingType("nats");
    }

    @Test
    @DisplayName("the documented defaults validate and warn about nothing")
    void defaultsAreValid() {
        assertDoesNotThrow(() -> nats().validate());
        assertTrue(nats().validationWarnings().isEmpty(), nats().validationWarnings().toString());
    }

    @Test
    @DisplayName("opentls:// (trust any certificate) is refused")
    void opentlsRefused() {
        var e = assertThrows(IllegalStateException.class, () -> nats().withNatsUrl("tls://a:4222, OpenTLS://b:4222").validate());
        assertTrue(e.getMessage().contains("opentls://"));
    }

    @Test
    @DisplayName("zero or negative durations are refused")
    void nonPositiveDurations() {
        assertThrows(IllegalStateException.class,
                () -> nats().withLeaseTimings(Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(45)).validate());
        assertThrows(IllegalStateException.class,
                () -> nats().withLeaseTimings(Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(-1)).validate());
        assertThrows(IllegalStateException.class, () -> nats().withRequestTimeout(Duration.ZERO).validate());
        assertThrows(IllegalStateException.class, () -> nats().withPing(Duration.ZERO, 2).validate());
        assertThrows(IllegalStateException.class, () -> nats().withPing(Duration.ofSeconds(5), 0).validate());
        assertThrows(IllegalStateException.class, () -> nats().withDegradedGrace(Duration.ofSeconds(-1)).validate());
        assertDoesNotThrow(() -> nats().withDegradedGrace(Duration.ZERO).validate());
    }

    @Test
    @DisplayName("two authentication methods at once are refused")
    void twoAuthMethods() {
        var e = assertThrows(IllegalStateException.class, () -> nats().withCredentials("eddi", "pw").withToken("t").validate());
        assertTrue(e.getMessage().contains("eddi.nats.username") && e.getMessage().contains("eddi.nats.token"));
    }

    @Test
    @DisplayName("an inbox prefix must be one subject token")
    void inboxPrefixIsOneToken() {
        assertThrows(IllegalStateException.class, () -> nats().withInboxPrefix("_INBOX.acme").validate());
        assertThrows(IllegalStateException.class, () -> nats().withInboxPrefix("*").validate());
        assertDoesNotThrow(() -> nats().withInboxPrefix("_INBOX_acme-1").validate());
    }

    @Test
    @DisplayName("in-memory mode checks none of the cluster settings")
    void inMemoryIgnoresClusterSettings() {
        assertDoesNotThrow(() -> ClusterConfig.defaults().withNatsUrl("opentls://x").withPing(Duration.ZERO, 0).validate());
        assertTrue(ClusterConfig.defaults().withNatsUrl("opentls://x").validationWarnings().isEmpty());
    }

    @Test
    @DisplayName("an acquire timeout shorter than the lease TTL is warned about")
    void acquireShorterThanTtl() {
        ClusterConfig config = nats().withLeaseTimings(Duration.ofSeconds(20), Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertDoesNotThrow(config::validate);
        assertTrue(config.validationWarnings().stream().anyMatch(w -> w.contains("acquire-timeout")));
    }

    @Test
    @DisplayName("credentials over a plain nats:// connection are warned about")
    void plaintextCredentials() {
        assertTrue(nats().withCredentials("eddi", "pw").validationWarnings().stream().anyMatch(w -> w.contains("clear text")));
        assertFalse(nats().withNatsUrl("tls://a:4222").withCredentials("eddi", "pw").validationWarnings().stream()
                .anyMatch(w -> w.contains("clear text")));
    }

    @Test
    @DisplayName("tls:// or tls.enabled both require TLS")
    void tlsRequired() {
        assertFalse(nats().natsTlsRequired());
        assertTrue(nats().withNatsUrl("TLS://a:4222").natsTlsRequired());
        assertTrue(nats().withNatsUrl("wss://a:443").natsTlsRequired());
        assertTrue(nats().withTls(true, true, null, null).natsTlsRequired());
    }

    @Test
    @DisplayName("an acquire timeout at or above the agent timeout is warned about at boot")
    void acquireVersusAgentTimeout() {
        Config raw = mock(Config.class);
        when(raw.getOptionalValue("systemRuntime.agentTimeoutInSeconds", Integer.class)).thenReturn(Optional.of(30));
        var bootstrap = new ClusterBootstrap(nats(), raw, null, null, null, null);
        assertTrue(bootstrap.configurationWarnings().stream().anyMatch(w -> w.contains("systemRuntime.agentTimeoutInSeconds")));

        when(raw.getOptionalValue("systemRuntime.agentTimeoutInSeconds", Integer.class)).thenReturn(Optional.of(60));
        assertTrue(bootstrap.configurationWarnings().isEmpty());
    }
}
