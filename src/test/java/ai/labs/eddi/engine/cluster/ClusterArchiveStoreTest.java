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
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.nats.client.Connection;
import io.nats.client.ObjectStore;
import io.nats.client.ObjectStoreManagement;
import io.nats.client.api.ObjectInfo;
import io.nats.client.api.ApiResponse;
import io.nats.client.JetStreamApiException;
import io.nats.client.support.JsonParser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        try (var files = Files.list(dir)) {
            assertEquals(List.of("a.zip"), files.map(f -> f.getFileName().toString()).toList(), "no partial download is left behind");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("two downloads of one archive that both miss the local file do not write the same partial file")
    void concurrentFetchesDoNotCorruptEachOther(@TempDir Path dir) throws Exception {
        ClusterConfig config = mock(ClusterConfig.class);
        when(config.isNats()).thenReturn(true);
        when(config.natsPrefix()).thenReturn("EDDI");
        Connection connection = mock(Connection.class);
        when(connection.objectStoreManagement()).thenReturn(mock(ObjectStoreManagement.class));
        ObjectStore objects = mock(ObjectStore.class);
        when(connection.objectStore("EDDI_ARCHIVES")).thenReturn(objects);
        ObjectInfo info = mock(ObjectInfo.class);
        when(objects.getInfo("a.zip")).thenReturn(info);
        NatsConnectionManager manager = mock(NatsConnectionManager.class);
        when(manager.requireConnected()).thenReturn(connection);
        Instance<NatsConnectionManager> connections = mock(Instance.class);
        when(connections.get()).thenReturn(manager);

        // own, then writes the second half: with one shared ".part" file they
        // interleave.
        CyclicBarrier bothHaveStarted = new CyclicBarrier(2);
        AtomicInteger downloads = new AtomicInteger();
        when(objects.get(eq("a.zip"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    OutputStream out = invocation.getArgument(1);
                    byte[] mine = String.valueOf((char) ('a' + downloads.getAndIncrement())).repeat(8).getBytes();
                    out.write(mine);
                    out.flush();
                    bothHaveStarted.await(5, TimeUnit.SECONDS);
                    out.write(mine);
                    return null;
                });
        var store = new ClusterArchiveStore(config, connections);
        Path target = dir.resolve("a.zip");

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> store.fetch("a.zip", target, Duration.ofMinutes(5)));
            Future<Boolean> second = executor.submit(() -> store.fetch("a.zip", target, Duration.ofMinutes(5)));
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertTrue(second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        String content = Files.readString(target);
        assertEquals(16, content.length());
        assertEquals(1, content.chars().distinct().count(), "the archive is one download's bytes, not a mix of two: " + content);
    }
    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a bucket deleted after it was checked is checked (and created) again on the next call")
    void missingBucketIsCheckedAgain(@TempDir Path dir) throws Exception {
        ClusterConfig config = mock(ClusterConfig.class);
        when(config.isNats()).thenReturn(true);
        when(config.natsPrefix()).thenReturn("EDDI");
        Connection connection = mock(Connection.class);
        ObjectStoreManagement management = mock(ObjectStoreManagement.class);
        when(connection.objectStoreManagement()).thenReturn(management);
        ObjectStore objects = mock(ObjectStore.class);
        when(connection.objectStore("EDDI_ARCHIVES")).thenReturn(objects);
        String json = "{\"type\":\"io.nats.jetstream.api.v1.stream_msg_get_response\",\"error\":{\"code\":404,\"err_code\":10059,"
                + "\"description\":\"stream not found\"}}";
        when(objects.getInfo("a.zip")).thenThrow(new JetStreamApiException(new ApiResponse<Object>(JsonParser.parse(json)) {
        }));
        NatsConnectionManager manager = mock(NatsConnectionManager.class);
        when(manager.requireConnected()).thenReturn(connection);
        Instance<NatsConnectionManager> connections = mock(Instance.class);
        when(connections.get()).thenReturn(manager);
        var store = new ClusterArchiveStore(config, connections);

        assertFalse(store.fetch("a.zip", dir.resolve("a.zip"), Duration.ofMinutes(5)));
        assertFalse(store.fetch("a.zip", dir.resolve("a.zip"), Duration.ofMinutes(5)));

        verify(management, times(2)).getStatus("EDDI_ARCHIVES");
    }
}
