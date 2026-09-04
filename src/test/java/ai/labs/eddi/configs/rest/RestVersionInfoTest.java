/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private ResourceAccessGuard accessGuard;

    private static final String RESOURCE_URI = "eddi://ai.labs.test/teststore/tests/";
    private static final String TEST_ID = "test-resource-id";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        resourceStore = mock(IResourceStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        restVersionInfo = new RestVersionInfo<>(RESOURCE_URI, resourceStore, documentDescriptorStore, accessGuard);
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

    /**
     * {@code read} took the version literally while {@code update} and
     * {@code delete} both routed it through {@code validateParameters}, so
     * {@code GET ?version=0} was a 404 while {@code PUT ?version=0} and
     * {@code DELETE ?version=0} acted on the current version — no single convention
     * worked across the three verbs.
     */
    @Test
    void read_versionZero_readsTheCurrentVersion() throws Exception {
        IResourceId currentResourceId = mock(IResourceId.class);
        when(currentResourceId.getVersion()).thenReturn(5);
        when(resourceStore.getCurrentResourceId(TEST_ID)).thenReturn(currentResourceId);
        Object document = new Object();
        when(resourceStore.read(TEST_ID, 5)).thenReturn(document);

        assertSame(document, restVersionInfo.read(TEST_ID, 0));
        verify(resourceStore).read(TEST_ID, 5);
    }

    /**
     * Only {@code DocumentDescriptorFilter} — a JAX-RS response filter — used to
     * flag a descriptor deleted, so it ran for an HTTP DELETE and for nothing else.
     * Every in-process delete (the agent cascade, the workflow cascade, the orphan
     * purge) left {@code deleted=false}, so the resource stayed in every listing,
     * answered 404 when opened, and kept being re-reported by the orphan scan.
     */
    @Test
    void delete_marksTheDescriptorDeleted() throws Exception {
        var descriptor = new DocumentDescriptor();
        when(documentDescriptorStore.readDescriptor(TEST_ID, 2)).thenReturn(descriptor);

        restVersionInfo.delete(TEST_ID, 2, false);

        assertTrue(descriptor.isDeleted());
        verify(documentDescriptorStore).setDescriptor(TEST_ID, 2, descriptor);
    }

    /**
     * Four classes used to assert in comments that {@code Response.getLocation()}
     * returns null for {@code eddi://} scheme URIs, and a parallel creation API
     * plus a three-strategy URI extractor were built around that belief. It is
     * false, and this pins it against the real JAX-RS {@code RuntimeDelegate} on
     * the Response {@code create} actually builds — so the next author does not
     * reintroduce the workaround.
     */
    @Test
    void create_locationHeaderCarriesTheEddiUri() throws Exception {
        IResourceId created = mock(IResourceId.class);
        when(created.getId()).thenReturn(TEST_ID);
        when(created.getVersion()).thenReturn(1);
        when(resourceStore.create(any())).thenReturn(created);

        var response = restVersionInfo.create(new Object());

        assertEquals(201, response.getStatus());
        assertEquals(URI.create(RESOURCE_URI + TEST_ID + "?version=1"), response.getLocation());
    }

    /**
     * Even a permanent delete only FLAGS the descriptor. Erasing the row would be
     * tidier, but on the HTTP path {@code DocumentDescriptorFilter} runs after this
     * and reads the descriptor back — a missing row there becomes a 404 answer to a
     * delete that in fact succeeded.
     */
    @Test
    void delete_permanent_flagsTheDescriptorRatherThanErasingIt() throws Exception {
        var descriptor = new DocumentDescriptor();
        when(documentDescriptorStore.readDescriptor(TEST_ID, 2)).thenReturn(descriptor);

        restVersionInfo.delete(TEST_ID, 2, true);

        assertTrue(descriptor.isDeleted());
        verify(documentDescriptorStore).setDescriptor(TEST_ID, 2, descriptor);
        verify(documentDescriptorStore, never()).deleteAllDescriptor(anyString());
    }

    @Test
    void delete_descriptorFailureDoesNotFailTheDelete() throws Exception {
        // The resource IS gone by then — a descriptor that cannot be updated must not
        // turn a completed delete into an error response.
        when(documentDescriptorStore.readDescriptor(TEST_ID, 2))
                .thenThrow(new ResourceNotFoundException("no descriptor"));

        assertEquals(200, restVersionInfo.delete(TEST_ID, 2, false).getStatus());
        verify(resourceStore).delete(TEST_ID, 2);
    }
}
