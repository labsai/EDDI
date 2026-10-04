/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.nats.client.Connection;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueOperation;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NatsSharedStateFactory leader-only reads")
class NatsSharedStateFactoryLeaderReadsTest {

    private static KeyValueConfiguration bucket() {
        return KeyValueConfiguration.builder().name("T_LEASES").ttl(Duration.ofSeconds(20)).storageType(StorageType.File).replicas(3)
                .maxHistoryPerKey(1).build();
    }

    @Test
    @DisplayName("jnats makes a bucket direct-get capable by default — which lets a lagging follower answer a read")
    void jnatsDefaultIsDirect() {
        assertTrue(bucket().getBackingConfig().getAllowDirect());
    }

    @Test
    @DisplayName("the backing stream of every bucket has direct get off and keeps the rest of the bucket's configuration")
    void leaderReadsTurnsDirectOff() {
        StreamConfiguration config = NatsSharedStateFactory.leaderReads(bucket());
        assertFalse(config.getAllowDirect());
        assertEquals("KV_T_LEASES", config.getName());
        assertEquals(3, config.getReplicas());
        assertEquals(Duration.ofSeconds(20), config.getMaxAge());
        assertEquals(1, config.getMaxMsgsPerSubject());
    }
    @Test
    @DisplayName("a KV handle that stops answering is reopened on the next call — a handle opened before direct get was turned off recovers at once")
    void failedHandleIsReopened() throws Exception {
        NatsConnectionManager connections = mock(NatsConnectionManager.class);
        when(connections.requireConnected()).thenReturn(mock(Connection.class));
        KeyValue opened = mock(KeyValue.class);
        when(opened.get("k")).thenThrow(new IOException("no responders"));
        KeyValue reopened = mock(KeyValue.class);
        KeyValueEntry entry = mock(KeyValueEntry.class);
        when(entry.getOperation()).thenReturn(KeyValueOperation.PUT);
        when(entry.getValue()).thenReturn("v".getBytes());
        when(entry.getRevision()).thenReturn(4L);
        when(reopened.get("k")).thenReturn(entry);
        when(connections.keyValue("T_LEASES")).thenReturn(opened, reopened);
        var kv = new NatsSharedKv(connections, "T_LEASES", null);

        assertThrows(ClusterUnavailableException.class, () -> kv.get("k"));
        assertEquals(4L, kv.get("k").orElseThrow().revision(), "the next call opens a new handle instead of reusing the failed one");
        verify(connections, times(2)).keyValue("T_LEASES");
    }
}
