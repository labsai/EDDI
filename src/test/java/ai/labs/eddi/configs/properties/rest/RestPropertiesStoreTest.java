/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.rest;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that {@link RestPropertiesStore} correctly delegates flat property
 * operations to {@link IUserMemoryStore} after the v6 consolidation.
 */
class RestPropertiesStoreTest {

    private IUserMemoryStore userMemoryStore;
    private RestPropertiesStore restPropertiesStore;

    @BeforeEach
    void setUp() {
        userMemoryStore = mock(IUserMemoryStore.class);
        restPropertiesStore = new RestPropertiesStore(userMemoryStore);
    }

    // === readProperties ===

    @Test
    void readProperties_shouldDelegateToUserMemoryStore() throws Exception {
        Properties props = new Properties();
        props.put("color", "blue");
        when(userMemoryStore.readProperties("user-1")).thenReturn(props);

        Properties result = restPropertiesStore.readProperties("user-1");

        assertEquals("blue", result.get("color"));
        verify(userMemoryStore).readProperties("user-1");
    }

    @Test
    void readProperties_shouldReturnNullWhenNoEntries() throws Exception {
        when(userMemoryStore.readProperties("user-1")).thenReturn(null);

        Properties result = restPropertiesStore.readProperties("user-1");

        assertNull(result);
    }

    @Test
    void readProperties_shouldThrowOnStoreException() throws Exception {
        when(userMemoryStore.readProperties("user-1")).thenThrow(new IResourceStore.ResourceStoreException("DB down"));

        assertThrows(RuntimeException.class, () -> restPropertiesStore.readProperties("user-1"));
    }

    // === mergeProperties ===

    @Test
    void mergeProperties_shouldDelegateToUserMemoryStore() throws Exception {
        Properties props = new Properties();
        props.put("name", "Alice");

        Response response = restPropertiesStore.mergeProperties("user-1", props);

        assertEquals(200, response.getStatus());
        verify(userMemoryStore).mergeProperties("user-1", props);
    }

    @Test
    void mergeProperties_shouldThrowOnStoreException() throws Exception {
        Properties props = new Properties();
        props.put("k", "v");
        doThrow(new IResourceStore.ResourceStoreException("fail")).when(userMemoryStore).mergeProperties("user-1", props);

        assertThrows(RuntimeException.class, () -> restPropertiesStore.mergeProperties("user-1", props));
    }

    // === deleteProperties ===

    @Test
    void deleteProperties_shouldDelegateToUserMemoryStore() throws Exception {
        Response response = restPropertiesStore.deleteProperties("user-1");

        assertEquals(200, response.getStatus());
        verify(userMemoryStore).deleteProperties("user-1");
    }

    @Test
    void deleteProperties_shouldThrowOnStoreException() throws Exception {
        doThrow(new IResourceStore.ResourceStoreException("fail")).when(userMemoryStore).deleteProperties("user-1");

        assertThrows(RuntimeException.class, () -> restPropertiesStore.deleteProperties("user-1"));
    }
}
