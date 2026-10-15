/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.cluster.events.RecordingEventBus;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.runtime.client.workflows.IWorkflowStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link WorkflowFactory} covering caching, concurrent access, and
 * the inner WorkflowId equals/hashCode contract.
 */
@DisplayName("WorkflowFactory Tests")
class WorkflowFactoryTest {

    private IWorkflowStoreClientLibrary clientLibrary;
    private WorkflowFactory factory;

    @BeforeEach
    void setUp() {
        clientLibrary = mock(IWorkflowStoreClientLibrary.class);
        factory = new WorkflowFactory(clientLibrary);
    }

    @Test
    @DisplayName("should create and cache executable workflow")
    void createsAndCachesWorkflow() throws Exception {
        var workflow = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-1", 1)).thenReturn(workflow);

        IExecutableWorkflow result = factory.getExecutableWorkflow("wf-1", 1);

        assertSame(workflow, result);
        verify(clientLibrary, times(1)).getExecutableWorkflow("wf-1", 1);
    }

    @Test
    @DisplayName("should return cached workflow on second call")
    void returnsCachedWorkflow() throws Exception {
        var workflow = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-1", 1)).thenReturn(workflow);

        IExecutableWorkflow first = factory.getExecutableWorkflow("wf-1", 1);
        IExecutableWorkflow second = factory.getExecutableWorkflow("wf-1", 1);

        assertSame(first, second);
        // Library should only be called once
        verify(clientLibrary, times(1)).getExecutableWorkflow("wf-1", 1);
    }

    @Test
    @DisplayName("an Error while building leaves no half-built entry behind: the next call builds again instead of blocking for ever")
    void anErrorDuringTheBuildDoesNotPoisonTheKey() throws Exception {
        var workflow = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-err", 1)).thenThrow(new NoClassDefFoundError("missing")).thenReturn(workflow);

        assertThrows(NoClassDefFoundError.class, () -> factory.getExecutableWorkflow("wf-err", 1));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<IExecutableWorkflow> second = executor.submit(() -> factory.getExecutableWorkflow("wf-err", 1));
            assertSame(workflow, second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("should create separate entries for different workflow IDs")
    void separateWorkflows() throws Exception {
        var wf1 = mock(IExecutableWorkflow.class);
        var wf2 = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-1", 1)).thenReturn(wf1);
        when(clientLibrary.getExecutableWorkflow("wf-2", 1)).thenReturn(wf2);

        IExecutableWorkflow result1 = factory.getExecutableWorkflow("wf-1", 1);
        IExecutableWorkflow result2 = factory.getExecutableWorkflow("wf-2", 1);

        assertNotSame(result1, result2);
    }

    @Test
    @DisplayName("should create separate entries for different versions")
    void differentVersions() throws Exception {
        var v1 = mock(IExecutableWorkflow.class);
        var v2 = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-1", 1)).thenReturn(v1);
        when(clientLibrary.getExecutableWorkflow("wf-1", 2)).thenReturn(v2);

        IExecutableWorkflow result1 = factory.getExecutableWorkflow("wf-1", 1);
        IExecutableWorkflow result2 = factory.getExecutableWorkflow("wf-1", 2);

        assertNotSame(result1, result2);
    }

    @Test
    @DisplayName("should propagate ServiceException from library")
    void propagatesException() throws Exception {
        when(clientLibrary.getExecutableWorkflow("bad", 1))
                .thenThrow(new ServiceException("Not found"));

        assertThrows(ServiceException.class, () -> factory.getExecutableWorkflow("bad", 1));
    }

    @Test
    @DisplayName("concurrent first requests build a workflow exactly once")
    void concurrentFirstRequestsBuildOnce() throws Exception {
        var workflow = mock(IExecutableWorkflow.class);
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(clientLibrary.getExecutableWorkflow("wf-race", 1)).thenAnswer(inv -> {
            building.countDown();
            release.await(5, TimeUnit.SECONDS);
            return workflow;
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<IExecutableWorkflow>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> factory.getExecutableWorkflow("wf-race", 1)));
        }
        assertTrue(building.await(5, TimeUnit.SECONDS));
        release.countDown();
        for (Future<IExecutableWorkflow> result : results) {
            assertSame(workflow, result.get(5, TimeUnit.SECONDS));
        }
        pool.shutdown();
        verify(clientLibrary, times(1)).getExecutableWorkflow("wf-race", 1);
    }

    @Test
    @DisplayName("a null version is a valid key (the old key class threw from equals)")
    void nullVersionKey() throws Exception {
        var workflow = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-null", null)).thenReturn(workflow);
        assertSame(workflow, factory.getExecutableWorkflow("wf-null", null));
        assertSame(workflow, factory.getExecutableWorkflow("wf-null", null));
        when(clientLibrary.getExecutableWorkflow("wf-null", 1)).thenReturn(mock(IExecutableWorkflow.class));
        assertNotSame(workflow, factory.getExecutableWorkflow("wf-null", 1));
    }

    @Test
    @DisplayName("a failed build is not cached")
    void failureNotCached() throws Exception {
        var workflow = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-fail", 1)).thenThrow(new ServiceException("db down")).thenReturn(workflow);
        assertThrows(ServiceException.class, () -> factory.getExecutableWorkflow("wf-fail", 1));
        assertSame(workflow, factory.getExecutableWorkflow("wf-fail", 1));
    }

    @Test
    @DisplayName("evicting a deleted version drops it here and announces it to the cluster")
    void evictDropsAndAnnounces() throws Exception {
        var bus = new RecordingEventBus();
        factory.clusterEvents = bus;
        var components = mock(IComponentCache.class);
        factory.componentCache = components;
        factory.subscribeToCluster();
        when(clientLibrary.getExecutableWorkflow("wf-1", 1)).thenReturn(mock(IExecutableWorkflow.class));
        factory.getExecutableWorkflow("wf-1", 1);

        factory.evict("wf-1", 1);
        assertEquals(0, factory.size());
        verify(components).evictWorkflow("wf-1", 1);
        assertEquals(1, bus.ofType(ClusterEvent.CONFIG_DELETED).size());

        factory.getExecutableWorkflow("wf-1", 1);
        bus.deliver(ClusterEvent.CONFIG_DELETED, Map.of("type", "workflow", "id", "wf-1", "version", 1));
        assertEquals(0, factory.size(), "a deletion on another node evicts here too");
        assertEquals(1, bus.ofType(ClusterEvent.CONFIG_DELETED).size(), "and is not re-published");
    }
}
