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
import java.io.IOException;
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
import io.nats.client.api.ObjectStoreConfiguration;
import io.nats.client.api.ObjectStoreStatus;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import io.nats.client.api.ApiResponse;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.support.JsonParser;
import org.mockito.ArgumentCaptor;

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

    /**
     * C9: the archive bucket shares JetStream storage with the lease bucket; an
     * unbounded one let large exports fill the store and block lease creates.
     */
    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a new archive bucket is created with the configured size cap")
    void newBucketIsCapped(@TempDir Path dir) throws Exception {
        ClusterConfig config = ClusterConfig.defaults().withMessagingType("nats");
        Connection connection = mock(Connection.class);
        ObjectStoreManagement management = mock(ObjectStoreManagement.class);
        when(connection.objectStoreManagement()).thenReturn(management);
        String json = "{\"type\":\"io.nats.jetstream.api.v1.stream_info_response\",\"error\":{\"code\":404,\"err_code\":10059,"
                + "\"description\":\"stream not found\"}}";
        when(management.getStatus("EDDI_ARCHIVES")).thenThrow(new JetStreamApiException(new ApiResponse<Object>(JsonParser.parse(json)) {
        }));
        when(connection.objectStore("EDDI_ARCHIVES")).thenReturn(mock(ObjectStore.class));
        NatsConnectionManager manager = mock(NatsConnectionManager.class);
        when(manager.requireConnected()).thenReturn(connection);
        Instance<NatsConnectionManager> connections = mock(Instance.class);
        when(connections.get()).thenReturn(manager);

        new ClusterArchiveStore(config, connections).publish("a.zip", Files.writeString(dir.resolve("a.zip"), "zip"), Duration.ofMinutes(5));

        ArgumentCaptor<ObjectStoreConfiguration> created = ArgumentCaptor.forClass(ObjectStoreConfiguration.class);
        verify(management).create(created.capture());
        assertEquals(1L << 30, created.getValue().getMaxBucketSize());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("an existing unbounded archive bucket is given the cap")
    void existingBucketIsCapped(@TempDir Path dir) throws Exception {
        ClusterConfig config = ClusterConfig.defaults().withMessagingType("nats");
        Connection connection = mock(Connection.class);
        ObjectStoreManagement management = mock(ObjectStoreManagement.class);
        when(connection.objectStoreManagement()).thenReturn(management);
        ObjectStoreStatus status = mock(ObjectStoreStatus.class);
        when(status.getMaxBucketSize()).thenReturn(-1L);
        when(management.getStatus("EDDI_ARCHIVES")).thenReturn(status);
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(connection.jetStreamManagement()).thenReturn(jsm);
        StreamInfo info = mock(StreamInfo.class);
        when(info.getConfiguration()).thenReturn(StreamConfiguration.builder().name("OBJ_EDDI_ARCHIVES").build());
        when(jsm.getStreamInfo("OBJ_EDDI_ARCHIVES")).thenReturn(info);
        when(connection.objectStore("EDDI_ARCHIVES")).thenReturn(mock(ObjectStore.class));
        NatsConnectionManager manager = mock(NatsConnectionManager.class);
        when(manager.requireConnected()).thenReturn(connection);
        Instance<NatsConnectionManager> connections = mock(Instance.class);
        when(connections.get()).thenReturn(manager);

        new ClusterArchiveStore(config, connections).publish("a.zip", Files.writeString(dir.resolve("a.zip"), "zip"), Duration.ofMinutes(5));

        ArgumentCaptor<StreamConfiguration> updated = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm).updateStream(updated.capture());
        assertEquals(1L << 30, updated.getValue().getMaxBytes());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a cap that failed is tried again on the next use instead of being given up")
    void failedCapIsRetried(@TempDir Path dir) throws Exception {
        ClusterConfig config = ClusterConfig.defaults().withMessagingType("nats");
        Connection connection = mock(Connection.class);
        ObjectStoreManagement management = mock(ObjectStoreManagement.class);
        when(connection.objectStoreManagement()).thenReturn(management);
        ObjectStoreStatus status = mock(ObjectStoreStatus.class);
        when(status.getMaxBucketSize()).thenReturn(-1L);
        when(management.getStatus("EDDI_ARCHIVES")).thenReturn(status);
        JetStreamManagement jsm = mock(JetStreamManagement.class);
        when(connection.jetStreamManagement()).thenReturn(jsm);
        StreamInfo info = mock(StreamInfo.class);
        when(info.getConfiguration()).thenReturn(StreamConfiguration.builder().name("OBJ_EDDI_ARCHIVES").build());
        when(jsm.getStreamInfo("OBJ_EDDI_ARCHIVES")).thenReturn(info);
        when(jsm.updateStream(any())).thenThrow(new IOException("JetStream request timed out")).thenReturn(info);
        when(connection.objectStore("EDDI_ARCHIVES")).thenReturn(mock(ObjectStore.class));
        NatsConnectionManager manager = mock(NatsConnectionManager.class);
        when(manager.requireConnected()).thenReturn(connection);
        Instance<NatsConnectionManager> connections = mock(Instance.class);
        when(connections.get()).thenReturn(manager);
        ClusterArchiveStore store = new ClusterArchiveStore(config, connections);
        Path file = Files.writeString(dir.resolve("a.zip"), "zip");

        store.publish("a.zip", file, Duration.ofMinutes(5));
        store.publish("b.zip", file, Duration.ofMinutes(5));
        store.publish("c.zip", file, Duration.ofMinutes(5));

        verify(jsm, times(2)).updateStream(any());
        verify(management, times(2)).getStatus("EDDI_ARCHIVES");
    }
}
