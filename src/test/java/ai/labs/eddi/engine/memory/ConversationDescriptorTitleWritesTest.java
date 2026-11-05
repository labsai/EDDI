/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.DescriptorStore;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor.TitleSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The title and pin writes of {@link ConversationDescriptorStore}: which guard
 * each source of a title carries, so that a generated title never replaces one
 * of higher precedence (the store-level half of "a rename is never
 * overwritten"; {@code DescriptorFieldsParityTest} runs the guard against real
 * databases).
 */
@SuppressWarnings("unchecked")
@DisplayName("ConversationDescriptorStore — title and pin")
class ConversationDescriptorTitleWritesTest {

    private ConversationDescriptorStore store;
    private DescriptorStore<ConversationDescriptor> internalStore;

    @BeforeEach
    void setUp() throws Exception {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        IResourceStorage<ConversationDescriptor> storage = mock(IResourceStorage.class);
        doReturn(storage).when(storageFactory).create(anyString(), any(), any());
        store = new ConversationDescriptorStore(storageFactory, mock(IDocumentBuilder.class));
        internalStore = mock(DescriptorStore.class);
        var field = ConversationDescriptorStore.class.getDeclaredField("descriptorStore");
        field.setAccessible(true);
        field.set(store, internalStore);
    }

    @Test
    @DisplayName("AUTO lands only where there is no title source at all")
    void autoGuard() {
        doReturn(true).when(internalStore).setFieldsIf(anyString(), any(), anyString(), any());

        assertTrue(store.writeGeneratedTitle("conv-1", "Weather", TitleSource.AUTO));

        verify(internalStore).setFieldsIf(eq("conv-1"), eq(Map.of("title", "Weather", "titleSource", "AUTO")), eq("titleSource"), eq(List.of()));
    }

    @Test
    @DisplayName("LLM replaces nothing but AUTO — never USER")
    void llmGuard() {
        doReturn(true).when(internalStore).setFieldsIf(anyString(), any(), anyString(), any());

        store.writeGeneratedTitle("conv-1", "Vienna weather", TitleSource.LLM);

        var allowed = ArgumentCaptor.forClass(Collection.class);
        verify(internalStore).setFieldsIf(eq("conv-1"), eq(Map.of("title", "Vienna weather", "titleSource", "LLM")), eq("titleSource"),
                allowed.capture());
        assertEquals(List.of("AUTO"), List.copyOf(allowed.getValue()));
    }

    @Test
    @DisplayName("USER is not a generated source")
    void userIsNotGenerated() {
        assertThrows(IllegalArgumentException.class, () -> store.writeGeneratedTitle("conv-1", "x", TitleSource.USER));
    }

    @Test
    @DisplayName("the owner's edit is unconditional and marks the title USER")
    void ownerEdit() {
        doReturn(true).when(internalStore).setFieldsIf(anyString(), any(), isNull(), any());

        assertTrue(store.writeOwnerEdit("conv-1", "Trip", true));

        verify(internalStore).setFieldsIf(eq("conv-1"), eq(Map.of("title", "Trip", "titleSource", "USER", "pinned", true)), isNull(), eq(List.of()));
    }

    @Test
    @DisplayName("a pin alone leaves the title alone")
    void pinOnly() {
        doReturn(true).when(internalStore).setFieldsIf(anyString(), any(), isNull(), any());

        store.writeOwnerEdit("conv-1", null, false);

        verify(internalStore).setFieldsIf(eq("conv-1"), eq(Map.of("pinned", false)), isNull(), eq(List.of()));
    }

    @Test
    @DisplayName("an edit with nothing in it is refused")
    void emptyEdit() {
        assertThrows(IllegalArgumentException.class, () -> store.writeOwnerEdit("conv-1", null, null));
    }

    @Test
    @DisplayName("fallback rewrite keeps the guard: an LLM title does not replace a USER one")
    void fallbackKeepsGuard() throws Exception {
        doThrow(new UnsupportedOperationException()).when(internalStore).setFieldsIf(anyString(), any(), any(), any());
        IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
        doReturn(0).when(resourceId).getVersion();
        doReturn(resourceId).when(internalStore).getCurrentResourceId("conv-1");
        var descriptor = new ConversationDescriptor();
        descriptor.setTitle("Mine");
        descriptor.setTitleSource(TitleSource.USER);
        doReturn(descriptor).when(internalStore).readDescriptor("conv-1", 0);

        assertFalse(store.writeGeneratedTitle("conv-1", "Generated", TitleSource.LLM));
        verify(internalStore, never()).setDescriptor(anyString(), anyInt(), any());

        assertTrue(store.writeOwnerEdit("conv-1", "Renamed", true));
        verify(internalStore).setDescriptor(eq("conv-1"), eq(0), any());
        assertEquals("Renamed", descriptor.getTitle());
        assertTrue(descriptor.isPinned());
    }
}
