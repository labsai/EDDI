/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.admin;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.model.ClusterAdminModels.ActivityEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ClusterActivityLog")
class ClusterActivityLogTest {

    private ClusterActivityLog log;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Instance<NatsConnectionManager> none = mock(Instance.class);
        log = new ClusterActivityLog(ClusterConfig.defaults(), new NodeIdentity("n1", "b1"), none, Duration.ofHours(24));
    }

    @Test
    @DisplayName("an observation with the same id is kept once — several nodes report the same lost node")
    void deduplicatesById() {
        log.record("node.lost", ClusterActivityLog.ERROR, Map.of("nodeId", "n3"), "node.lost:n3:b3");
        log.record("node.lost", ClusterActivityLog.ERROR, Map.of("nodeId", "n3"), "node.lost:n3:b3");
        log.receive("{\"id\":\"node.lost:n3:b3\",\"type\":\"node.lost\",\"severity\":\"error\",\"node\":\"n2\",\"ts\":1,\"payload\":{}}"
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(1, log.recent(100, List.of()).size());
    }

    @Test
    @DisplayName("entries read back from the stream join the ring, oldest first")
    void receivesFromStream() {
        log.record("admin.caches.resync", ClusterActivityLog.INFO, Map.of(), null);
        log.receive("{\"id\":\"x\",\"type\":\"node.joined\",\"severity\":\"info\",\"node\":\"n2\",\"ts\":5,\"payload\":{\"nodeId\":\"n2\"}}"
                .getBytes(StandardCharsets.UTF_8));
        List<ActivityEvent> recent = log.recent(10, List.of());
        assertEquals("node.joined", recent.get(0).type());
        assertEquals("n2", recent.get(0).node());
        assertEquals("n2", recent.get(0).payload().get("nodeId"));
    }

    @Test
    @DisplayName("the ring keeps the newest 500 and filters by type prefix")
    void boundedAndFiltered() {
        for (int i = 0; i < 600; i++) {
            log.record(i % 2 == 0 ? "node.joined" : "lease.takeover", ClusterActivityLog.INFO, Map.of("i", i), null);
        }
        List<ActivityEvent> all = log.recent(1000, List.of());
        assertEquals(ClusterActivityLog.RING_SIZE, all.size());
        assertEquals(599, all.get(all.size() - 1).payload().get("i"));
        assertTrue(log.recent(1000, List.of("lease.")).stream().allMatch(e -> e.type().startsWith("lease.")));
        assertEquals(5, log.recent(5, List.of()).size());
    }

    @Test
    @DisplayName("live listeners get every new entry, and at most 16 may subscribe per node")
    void listenersAreBounded() {
        List<ActivityEvent> received = new ArrayList<>();
        Consumer<ActivityEvent> first = received::add;
        assertTrue(log.addListener(first, true));
        for (int i = 1; i < ClusterActivityLog.MAX_LISTENERS; i++) {
            assertTrue(log.addListener(e -> {
            }, true));
        }
        assertFalse(log.addListener(e -> {
        }, true), "the 17th subscriber must be refused");
        log.record("node.joined", ClusterActivityLog.INFO, Map.of(), null);
        assertEquals(1, received.size());
        log.removeListener(first);
        assertTrue(log.addListener(e -> {
        }, true));
        log.record("node.joined", ClusterActivityLog.INFO, Map.of(), null);
        assertEquals(1, received.size());
    }

    @Test
    @DisplayName("viewers cannot take every slot: four stay reserved for administrators")
    void slotsReservedForAdmins() {
        for (int i = 0; i < ClusterActivityLog.MAX_VIEWER_LISTENERS; i++) {
            assertTrue(log.addListener(e -> {
            }, false));
        }
        assertFalse(log.addListener(e -> {
        }, false), "a 13th viewer is refused");
        for (int i = ClusterActivityLog.MAX_VIEWER_LISTENERS; i < ClusterActivityLog.MAX_LISTENERS; i++) {
            assertTrue(log.addListener(e -> {
            }, true), "an administrator still gets a slot");
        }
        assertFalse(log.addListener(e -> {
        }, true));
    }

    @Test
    @DisplayName("a failing listener does not stop the others")
    void failingListener() {
        List<ActivityEvent> received = new ArrayList<>();
        log.addListener(e -> {
            throw new IllegalStateException("closed");
        }, true);
        log.addListener(received::add, true);
        log.record("node.joined", ClusterActivityLog.INFO, Map.of(), null);
        assertEquals(1, received.size());
    }

    @Test
    @DisplayName("an unreadable stream entry is ignored")
    void unreadable() {
        log.receive("not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(log.recent(10, List.of()).isEmpty());
    }
}
