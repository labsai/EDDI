/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I13 review round 2 — {@link GroupWorkspaceStore}: the revision-checked write
 * and the duplicate-insert convergence guard (no unique constraint exists in
 * the storage abstraction, so the store must converge racers itself).
 */
class GroupWorkspaceStoreTest {

    private static final String GROUP_ID = "group-1";

    private IResourceStorage<GroupWorkspace> storage;
    private GroupWorkspaceStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        storage = mock(IResourceStorage.class);
        var factory = mock(IResourceStorageFactory.class);
        when(factory.create(anyString(), any(), eq(GroupWorkspace.class), any())).thenReturn(storage);
        store = new GroupWorkspaceStore(factory, mock(IDocumentBuilder.class));
    }

    private IResourceStore.IResourceId resourceId(String id) {
        var resourceId = mock(IResourceStore.IResourceId.class);
        lenient().when(resourceId.getId()).thenReturn(id);
        lenient().when(resourceId.getVersion()).thenReturn(1);
        return resourceId;
    }

    @SuppressWarnings("unchecked")
    private IResourceStorage.IResource<GroupWorkspace> resource(GroupWorkspace workspace, String id) throws Exception {
        IResourceStorage.IResource<GroupWorkspace> resource = mock(IResourceStorage.IResource.class);
        lenient().when(resource.getData()).thenReturn(workspace);
        lenient().when(resource.getId()).thenReturn(id);
        return resource;
    }

    // =================================================================
    // casRevision
    // =================================================================

    @Test
    @DisplayName("a matching revision writes conditionally and bumps the stamp")
    void casRevision_match_bumpsAndStoresConditionally() throws Exception {
        var workspace = new GroupWorkspace();
        workspace.setId("ws-1");
        workspace.setGroupId(GROUP_ID);
        workspace.setRevision("4");
        var res = resource(workspace, "ws-1");
        when(storage.newResource(eq("ws-1"), anyInt(), eq(workspace))).thenReturn(res);

        assertTrue(store.casRevision(workspace));

        assertEquals("5", workspace.getRevision());
        verify(storage).storeIfFieldEquals(res, "revision", "4");
        verify(storage, never()).store(any(IResourceStorage.IResource.class));
    }

    @Test
    @DisplayName("a lost revision race returns false and restores the caller's stamp for the re-read")
    void casRevision_mismatch_returnsFalseAndRestores() throws Exception {
        var workspace = new GroupWorkspace();
        workspace.setId("ws-1");
        workspace.setGroupId(GROUP_ID);
        workspace.setRevision("4");
        var res = resource(workspace, "ws-1");
        when(storage.newResource(eq("ws-1"), anyInt(), eq(workspace))).thenReturn(res);
        doThrow(new IResourceStore.ResourceModifiedException("raced"))
                .when(storage).storeIfFieldEquals(any(), eq("revision"), eq("4"));

        assertFalse(store.casRevision(workspace));

        assertEquals("4", workspace.getRevision(), "a failed CAS must not leave a phantom bump on the instance");
    }

    @Test
    @DisplayName("a pre-revision document is stamped with one plain write, then CAS'd forever after")
    void casRevision_legacyNullRevision_stampsWithPlainWrite() throws Exception {
        var workspace = new GroupWorkspace();
        workspace.setId("ws-1");
        workspace.setGroupId(GROUP_ID);
        workspace.setRevision(null);
        var res = resource(workspace, "ws-1");
        when(storage.newResource(eq("ws-1"), anyInt(), eq(workspace))).thenReturn(res);

        assertTrue(store.casRevision(workspace));

        assertEquals("1", workspace.getRevision());
        verify(storage).store(any(IResourceStorage.IResource.class));
        verify(storage, never()).storeIfFieldEquals(any(), anyString(), anyString());
    }

    // =================================================================
    // readOrCreate duplicate convergence
    // =================================================================

    @Test
    @DisplayName("a raced readOrCreate deletes its own duplicate and adopts the deterministic survivor")
    void readOrCreate_race_convergesOnSurvivor() throws Exception {
        // First find(): nothing yet (both racers miss). After OUR insert (id
        // "bbb"), the re-query sees TWO documents; the survivor is "aaa".
        var idBbb = resourceId("bbb");
        var idAaa = resourceId("aaa");
        var inserted = resource(new GroupWorkspace(), "bbb");
        var survivor = new GroupWorkspace();
        survivor.setGroupId(GROUP_ID);
        var survivorResource = resource(survivor, "aaa");
        when(storage.findResources(any(), eq("lastModified"), eq(0), eq(2)))
                .thenReturn(List.of())
                .thenReturn(List.of(idBbb, idAaa))
                .thenReturn(List.of(idAaa));
        when(storage.newResource(any(GroupWorkspace.class))).thenReturn(inserted);
        when(storage.read("aaa", 1)).thenReturn(survivorResource);

        GroupWorkspace result = store.readOrCreate(GROUP_ID);

        verify(storage).removeAllPermanently("bbb");
        assertEquals("aaa", result.getId(), "the loser adopts the earlier insert");
    }

    @Test
    @DisplayName("find() with lingering duplicates always reads the same survivor")
    void find_duplicates_readsDeterministicSurvivor() throws Exception {
        var idBbb = resourceId("bbb");
        var idAaa = resourceId("aaa");
        var survivor = new GroupWorkspace();
        survivor.setGroupId(GROUP_ID);
        var survivorResource = resource(survivor, "aaa");
        when(storage.findResources(any(), eq("lastModified"), eq(0), eq(2)))
                .thenReturn(List.of(idBbb, idAaa));
        when(storage.read("aaa", 1)).thenReturn(survivorResource);

        GroupWorkspace result = store.find(GROUP_ID);

        assertEquals("aaa", result.getId(),
                "sorting by id, not lastModified — every reader and both racers must agree");
    }
}
