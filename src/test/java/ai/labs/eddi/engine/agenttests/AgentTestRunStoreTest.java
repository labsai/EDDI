/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("AgentTestRunStore")
class AgentTestRunStoreTest {

    private static final String AGENT_ID = "0123456789abcdef01234567";
    private static final String RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

    private IResourceStorage<AgentTestRun> storage;
    private AgentTestRunStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        IResourceStorageFactory factory = mock(IResourceStorageFactory.class);
        storage = mock(IResourceStorage.class);
        when(factory.create(eq(AgentTestRunStore.COLLECTION_NAME), any(), eq(AgentTestRun.class), any(String[].class))).thenReturn(storage);
        store = new AgentTestRunStore(factory, mock(IDocumentBuilder.class));
    }

    private static AgentTestRun run(String id) {
        return new AgentTestRun(id, "bbbbbbbbbbbbbbbbbbbbbbbb", 1, "suite", AGENT_ID, 2, AgentTestRun.Status.RUNNING, "alice", 1000L, null, null,
                null, 1.0, null, null, null, null, null, null, false, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static IResourceStorage.IResource<AgentTestRun> resource(String id, AgentTestRun data) throws Exception {
        IResourceStorage.IResource<AgentTestRun> resource = mock(IResourceStorage.IResource.class);
        when(resource.getId()).thenReturn(id);
        when(resource.getVersion()).thenReturn(1);
        when(resource.getData()).thenReturn(data);
        return resource;
    }

    @Test
    void createStoresTheRunWithItsOwnId() throws Exception {
        var generated = resource(RUN_ID, run(null));
        when(storage.newResource(any(AgentTestRun.class))).thenReturn(generated);
        var captor = ArgumentCaptor.forClass(AgentTestRun.class);
        var stored = resource(RUN_ID, null);
        when(storage.newResource(eq(RUN_ID), eq(1), captor.capture())).thenReturn(stored);

        assertEquals(RUN_ID, store.create(run(null)));

        assertEquals(RUN_ID, captor.getValue().id());
        var order = inOrder(storage, generated);
        order.verify(storage).store(generated);
        order.verify(generated).getId();
        verify(storage).store(stored);
    }

    /**
     * The heartbeat chain and the orphan abort rely on this being one atomic CAS.
     */
    @Test
    void updateIfHeartbeatIsACompareAndSwapOnHeartbeatAt() throws Exception {
        var stored = resource(RUN_ID, null);
        when(storage.newResource(eq(RUN_ID), eq(1), any(AgentTestRun.class))).thenReturn(stored);

        store.updateIfHeartbeat(run(RUN_ID), 42L);

        verify(storage).storeIfFieldEquals(stored, "heartbeatAt", 42L);
        verify(storage, never()).store(any(IResourceStorage.IResource.class));
    }

    @Test
    void readOfUnknownRunIsNotFound() {
        when(storage.read(RUN_ID, 1)).thenReturn(null);
        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.read(RUN_ID));
    }

    @Test
    @DisplayName("an id that cannot exist never reaches the backend")
    void malformedIdIsNotFound() {
        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.read("../$where"));
        verify(storage, never()).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("listing filters on the exact agent id, newest first")
    void listByAgentFiltersExactlyAndSortsByStart() throws Exception {
        var id = mock(IResourceStore.IResourceId.class);
        when(id.getId()).thenReturn(RUN_ID);
        var filters = ArgumentCaptor.forClass(IResourceFilter.QueryFilters[].class);
        when(storage.findResources(filters.capture(), eq("startedAt"), eq(0), eq(20))).thenReturn(List.of(id));
        var data = run(null);
        IResourceStorage.IResource<AgentTestRun> res = resource(RUN_ID, data);
        when(storage.read(RUN_ID, 1)).thenReturn(res);

        var runs = store.listByAgent(AGENT_ID, 0, 20);

        assertEquals(1, runs.size());
        assertEquals(RUN_ID, runs.getFirst().id());
        var filter = filters.getValue()[0].getQueryFilters().getFirst();
        assertEquals("agentId", filter.getField());
        assertTrue(filter.isExact(), "an agent id must not be used as a regex");
    }

    @Test
    void listForMalformedAgentIdIsEmpty() throws Exception {
        assertTrue(store.listByAgent("(.*)", 0, 20).isEmpty());
        verify(storage, never()).findResources(any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("pruning keeps the newest N and deletes the rest")
    void pruneDeletesBeyondTheNewest() throws Exception {
        var old1 = mock(IResourceStore.IResourceId.class);
        when(old1.getId()).thenReturn("cccccccccccccccccccccccc");
        var old2 = mock(IResourceStore.IResourceId.class);
        when(old2.getId()).thenReturn("dddddddddddddddddddddddd");
        when(storage.findResources(any(), eq("startedAt"), eq(5), anyInt())).thenReturn(List.of(old1, old2));

        assertEquals(2, store.pruneOlderRuns(AGENT_ID, 5));

        verify(storage).removeAllPermanently("cccccccccccccccccccccccc");
        verify(storage).removeAllPermanently("dddddddddddddddddddddddd");
    }
}
