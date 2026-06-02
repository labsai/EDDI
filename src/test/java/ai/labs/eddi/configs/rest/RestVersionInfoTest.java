/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for BUG-7: RestVersionInfo.getCurrentResourceId() override.
 * <p>
 * Before the fix, {@code RestVersionInfo} inherited the default
 * {@code getCurrentResourceId()} from {@code IRestVersionInfo}, which threw
 * {@code IllegalStateException}. The fix adds an override that delegates to the
 * underlying {@code resourceStore.getCurrentResourceId()}.
 */
class RestVersionInfoTest {

    private RestVersionInfo<Object> restVersionInfo;
    private IResourceStore<Object> resourceStore;
    private IDocumentDescriptorStore documentDescriptorStore;

    private static final String RESOURCE_URI = "eddi://ai.labs.test/teststore/tests/";
    private static final String TEST_ID = "test-resource-id";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        resourceStore = mock(IResourceStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        restVersionInfo = new RestVersionInfo<>(RESOURCE_URI, resourceStore, documentDescriptorStore);
    }

    /**
     * BUG-7: Verify that getCurrentResourceId() now delegates to the resourceStore
     * instead of throwing IllegalStateException.
     */
    @Test
    void getCurrentResourceId_delegatesToResourceStore() throws Exception {
        // Arrange
        IResourceId expectedResourceId = mock(IResourceId.class);
        when(expectedResourceId.getId()).thenReturn(TEST_ID);
        when(expectedResourceId.getVersion()).thenReturn(3);
        when(resourceStore.getCurrentResourceId(TEST_ID)).thenReturn(expectedResourceId);

        // Act
        IResourceId result = restVersionInfo.getCurrentResourceId(TEST_ID);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_ID, result.getId());
        assertEquals(3, result.getVersion());
        verify(resourceStore).getCurrentResourceId(TEST_ID);
    }

    @Test
    void getCurrentResourceId_notFound_throwsResourceNotFoundException() throws Exception {
        // Arrange
        when(resourceStore.getCurrentResourceId(TEST_ID))
                .thenThrow(new ResourceNotFoundException("Resource not found: " + TEST_ID));

        // Act & Assert
        assertThrows(ResourceNotFoundException.class,
                () -> restVersionInfo.getCurrentResourceId(TEST_ID));
    }

    /**
     * When version=0 is passed to validateParameters, it should resolve to the
     * current version via getCurrentVersion(), which internally calls
     * getCurrentResourceId(). This test verifies the full delegation chain.
     */
    @Test
    void validateParameters_versionZero_resolvesFromStore() throws Exception {
        // Arrange
        IResourceId currentResourceId = mock(IResourceId.class);
        when(currentResourceId.getVersion()).thenReturn(5);
        when(resourceStore.getCurrentResourceId(TEST_ID)).thenReturn(currentResourceId);

        // Act — version 0 triggers getCurrentVersion → getCurrentResourceId
        Integer resolvedVersion = restVersionInfo.validateParameters(TEST_ID, 0);

        // Assert — should resolve to the current version from the store
        assertEquals(5, resolvedVersion);
        verify(resourceStore).getCurrentResourceId(TEST_ID);
    }

    @Test
    void validateParameters_positiveVersion_returnsAsIs() {
        // Act — non-zero version should pass through unchanged
        Integer resolvedVersion = restVersionInfo.validateParameters(TEST_ID, 3);

        // Assert
        assertEquals(3, resolvedVersion);
        verifyNoInteractions(resourceStore);
    }
}
