/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The shared archive store is cluster-only: on one node it touches nothing, and
 * in cluster mode an unreachable NATS leaves the archive node-local instead of
 * failing the export. The NATS round trip itself is exercised by the live demo
 * (an archive exported on one node, downloaded through another).
 */
@DisplayName("ClusterArchiveStore")
class ClusterArchiveStoreTest {

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("single node: neither publishes nor fetches, and never touches NATS")
    void singleNodeIsInert(@TempDir Path dir) throws Exception {
        ClusterConfig config = mock(ClusterConfig.class);
        when(config.isNats()).thenReturn(false);
        Instance<NatsConnectionManager> connections = mock(Instance.class);
        var store = new ClusterArchiveStore(config, connections);
        Path file = Files.writeString(dir.resolve("a.zip"), "zip");

        store.publish("a.zip", file, Duration.ofMinutes(5));
        assertFalse(store.fetch("a.zip", dir.resolve("b.zip"), Duration.ofMinutes(5)));
        verifyNoInteractions(connections);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("cluster mode with NATS unreachable: the export is not failed, the download is a plain miss")
    void unreachableNatsDegrades(@TempDir Path dir) throws Exception {
        ClusterConfig config = mock(ClusterConfig.class);
        when(config.isNats()).thenReturn(true);
        when(config.natsPrefix()).thenReturn("EDDI");
        NatsConnectionManager manager = mock(NatsConnectionManager.class);
        when(manager.requireConnected()).thenThrow(new ClusterUnavailableException("NATS is not connected"));
        Instance<NatsConnectionManager> connections = mock(Instance.class);
        when(connections.get()).thenReturn(manager);
        var store = new ClusterArchiveStore(config, connections);
        Path file = Files.writeString(dir.resolve("a.zip"), "zip");

        assertDoesNotThrow(() -> store.publish("a.zip", file, Duration.ofMinutes(5)));
        assertFalse(store.fetch("a.zip", dir.resolve("b.zip"), Duration.ofMinutes(5)));
        assertFalse(Files.exists(dir.resolve("b.zip.part")));
    }
}
