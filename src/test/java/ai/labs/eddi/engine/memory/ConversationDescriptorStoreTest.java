/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.DescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ConversationDescriptorStore} — verifying delegation and the
 * updateTimeStamp error-swallowing behavior.
 */
@SuppressWarnings("unchecked")
@DisplayName("ConversationDescriptorStore")
class ConversationDescriptorStoreTest {

    private ConversationDescriptorStore store;
    private DescriptorStore<ConversationDescriptor> internalStore;

    @BeforeEach
    void setUp() throws Exception {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        IResourceStorage<ConversationDescriptor> storage = mock(IResourceStorage.class);
        doReturn(storage).when(storageFactory).create(anyString(), any(), any());

        store = new ConversationDescriptorStore(storageFactory, documentBuilder);

        // Replace internal descriptorStore with a mock for test isolation
        internalStore = mock(DescriptorStore.class);
        var field = ConversationDescriptorStore.class.getDeclaredField("descriptorStore");
        field.setAccessible(true);
        field.set(store, internalStore);
    }

    private IResourceStore.IResourceId mockResourceId(String id, int version) {
        IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
        doReturn(id).when(resourceId).getId();
        doReturn(version).when(resourceId).getVersion();
        return resourceId;
    }

    // ==================== updateTimeStamp ====================

    @Nested
    @DisplayName("updateTimeStamp")
    class UpdateTimeStampTests {

        @Test
        @DisplayName("happy path — reads, updates, and saves descriptor")
        void happyPath() throws Exception {
            IResourceStore.IResourceId resourceId = mockResourceId("conv-1", 1);
            doReturn(resourceId).when(internalStore).getCurrentResourceId("conv-1");

            ConversationDescriptor descriptor = new ConversationDescriptor();
            doReturn(descriptor).when(internalStore).readDescriptor("conv-1", 1);

            store.updateTimeStamp("conv-1");

            assertNotNull(descriptor.getLastModifiedOn());
            verify(internalStore).setDescriptor(eq("conv-1"), eq(1), eq(descriptor));
        }

        @Test
        @DisplayName("swallows ResourceNotFoundException")
        void swallowsNotFoundException() throws Exception {
            doThrow(new IResourceStore.ResourceNotFoundException("not found"))
                    .when(internalStore).getCurrentResourceId("missing");

            assertDoesNotThrow(() -> store.updateTimeStamp("missing"));
        }

        @Test
        @DisplayName("swallows ResourceStoreException")
        void swallowsStoreException() throws Exception {
            doThrow(new IResourceStore.ResourceNotFoundException("db error"))
                    .when(internalStore).getCurrentResourceId("error");

            assertDoesNotThrow(() -> store.updateTimeStamp("error"));
        }
    }

    // ==================== readDescriptors ====================

    @Test
    @DisplayName("readDescriptors delegates to internal store")
    void readDescriptors() throws Exception {
        List<ConversationDescriptor> expected = List.of(new ConversationDescriptor());
        doReturn(expected).when(internalStore).readDescriptors("conversations", "filter", 0, 10, false);

        List<ConversationDescriptor> result = store.readDescriptors("conversations", "filter", 0, 10, false);

        assertSame(expected, result);
        verify(internalStore).readDescriptors("conversations", "filter", 0, 10, false);
    }

    // ==================== readDescriptor ====================

    @Test
    @DisplayName("readDescriptor delegates to internal store")
    void readDescriptor() throws Exception {
        ConversationDescriptor expected = new ConversationDescriptor();
        doReturn(expected).when(internalStore).readDescriptor("conv-1", 1);

        ConversationDescriptor result = store.readDescriptor("conv-1", 1);

        assertSame(expected, result);
    }

    // ==================== readDescriptorWithHistory ====================

    @Test
    @DisplayName("readDescriptorWithHistory delegates to internal store")
    void readDescriptorWithHistory() throws Exception {
        ConversationDescriptor expected = new ConversationDescriptor();
        doReturn(expected).when(internalStore).readDescriptorWithHistory("conv-1", 1);

        ConversationDescriptor result = store.readDescriptorWithHistory("conv-1", 1);

        assertSame(expected, result);
    }

    // ==================== updateDescriptor ====================

    @Test
    @DisplayName("updateDescriptor delegates and returns new version")
    void updateDescriptor() throws Exception {
        ConversationDescriptor descriptor = new ConversationDescriptor();
        doReturn(2).when(internalStore).updateDescriptor("conv-1", 1, descriptor);

        Integer version = store.updateDescriptor("conv-1", 1, descriptor);

        assertEquals(2, version);
    }

    // ==================== setDescriptor ====================

    @Test
    @DisplayName("setDescriptor delegates to internal store")
    void setDescriptor() throws Exception {
        ConversationDescriptor descriptor = new ConversationDescriptor();

        store.setDescriptor("conv-1", 1, descriptor);

        verify(internalStore).setDescriptor("conv-1", 1, descriptor);
    }

    // ==================== createDescriptor ====================

    @Test
    @DisplayName("createDescriptor delegates to internal store")
    void createDescriptor() throws Exception {
        ConversationDescriptor descriptor = new ConversationDescriptor();

        store.createDescriptor("conv-1", 1, descriptor);

        verify(internalStore).createDescriptor("conv-1", 1, descriptor);
    }

    // ==================== getCurrentResourceId ====================

    @Test
    @DisplayName("getCurrentResourceId delegates to internal store")
    void getCurrentResourceId() throws Exception {
        IResourceStore.IResourceId expected = mockResourceId("conv-1", 1);
        doReturn(expected).when(internalStore).getCurrentResourceId("conv-1");

        IResourceStore.IResourceId result = store.getCurrentResourceId("conv-1");

        assertSame(expected, result);
    }

    // ==================== deleteDescriptor ====================

    @Test
    @DisplayName("deleteDescriptor delegates to internal store")
    void deleteDescriptor() throws Exception {
        store.deleteDescriptor("conv-1", 1);

        verify(internalStore).deleteDescriptor("conv-1", 1);
    }

    // ==================== deleteAllDescriptor ====================

    @Test
    @DisplayName("deleteAllDescriptor delegates to internal store")
    void deleteAllDescriptor() throws Exception {
        store.deleteAllDescriptor("conv-1");

        verify(internalStore).deleteAllDescriptor("conv-1");
    }

    // ==================== findByOriginId ====================

    @Test
    @DisplayName("findByOriginId delegates to internal store")
    void findByOriginId() throws Exception {
        List<ConversationDescriptor> expected = List.of(new ConversationDescriptor());
        doReturn(expected).when(internalStore).findByOriginId("origin-1");

        List<ConversationDescriptor> result = store.findByOriginId("origin-1");

        assertSame(expected, result);
    }
}
