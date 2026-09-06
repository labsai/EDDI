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

import jakarta.ws.rs.WebApplicationException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        IResourceId current = mock(IResourceId.class);
        when(current.getId()).thenReturn(TEST_ID);
        when(current.getVersion()).thenReturn(2);
        when(resourceStore.getCurrentResourceId(TEST_ID)).thenReturn(current);
        when(documentDescriptorStore.getCurrentResourceId(TEST_ID)).thenReturn(current);

        var descriptor = new DocumentDescriptor();
        when(documentDescriptorStore.readDescriptor(TEST_ID, 2)).thenReturn(descriptor);

        restVersionInfo.delete(TEST_ID, 2, true);

        assertTrue(descriptor.isDeleted());
        verify(documentDescriptorStore).setDescriptor(TEST_ID, 2, descriptor);
        verify(documentDescriptorStore, never()).deleteAllDescriptor(anyString());
    }

    /**
     * A permanent delete is ID-scoped — {@code deleteAllPermanently} drops every
     * version and every history row — while {@code version} was accepted and then
     * ignored on that branch, so a stale browser tab issuing
     * {@code DELETE ?version=1&permanent=true} against a resource that is at v2
     * erased all of it with no 409 anywhere. The soft path gets this check for free
     * from {@code HistorizedResourceStore.delete}; the permanent one has to ask.
     */
    @Test
    void delete_permanent_refusesAStaleVersionBeforeErasingAnything() throws Exception {
        IResourceId current = mock(IResourceId.class);
        when(current.getId()).thenReturn(TEST_ID);
        when(current.getVersion()).thenReturn(2);
        when(resourceStore.getCurrentResourceId(TEST_ID)).thenReturn(current);

        var thrown = assertThrows(WebApplicationException.class, () -> restVersionInfo.delete(TEST_ID, 1, true));

        assertEquals(409, thrown.getResponse().getStatus());
        verify(resourceStore, never()).deleteAllPermanently(anyString());
        verify(documentDescriptorStore, never()).setDescriptor(anyString(), any(), any());
    }

    /**
     * The two-step purge: a resource that is already soft-deleted has no current
     * version to be stale against, so purging its remaining history must still
     * work. Refusing here would make the documented flow impossible.
     */
    @Test
    void delete_permanent_allowsAnAlreadySoftDeletedResource() throws Exception {
        when(resourceStore.getCurrentResourceId(TEST_ID)).thenThrow(new ResourceNotFoundException("no current version"));

        assertEquals(200, restVersionInfo.delete(TEST_ID, 1, true).getStatus());
        verify(resourceStore).deleteAllPermanently(TEST_ID);
    }

    /**
     * Some store implementations report "no live version" by answering {@code null}
     * rather than by throwing. That must mean the same thing here — the purge is
     * allowed — and above all must not be dereferenced: an NPE in the middle of a
     * destructive operation leaves the caller with no idea what was erased.
     */
    @Test
    void delete_permanent_treatsANullCurrentIdAsNoLiveVersion() throws Exception {
        when(resourceStore.getCurrentResourceId(TEST_ID)).thenReturn(null);

        assertEquals(200, restVersionInfo.delete(TEST_ID, 1, true).getStatus());

        IResourceId versionless = mock(IResourceId.class);
        when(versionless.getVersion()).thenReturn(null);
        when(resourceStore.getCurrentResourceId(TEST_ID)).thenReturn(versionless);

        assertEquals(200, restVersionInfo.delete(TEST_ID, 1, true).getStatus());
        verify(resourceStore, times(2)).deleteAllPermanently(TEST_ID);
    }

    /**
     * Descriptors outlive the resource — they are flagged, never removed — so
     * flagging the version the request happened to address left the CURRENT
     * descriptor row saying {@code deleted=false}. The listing then kept showing a
     * resource whose every version had just been erased, opening it answered 404,
     * and the orphan scan reported it as a live orphan: the phantom this branch set
     * out to remove, on the path it claimed had converged.
     */
    @Test
    void delete_permanent_flagsTheDescriptorAtItsCurrentVersion() throws Exception {
        IResourceId currentResource = mock(IResourceId.class);
        when(currentResource.getId()).thenReturn(TEST_ID);
        when(currentResource.getVersion()).thenReturn(2);
        when(resourceStore.getCurrentResourceId(TEST_ID)).thenReturn(currentResource);

        // The descriptor has moved on to v4 while the resource is at v2 — the two are
        // versioned independently, so the descriptor's own current version is the one
        // to flag.
        IResourceId currentDescriptor = mock(IResourceId.class);
        when(currentDescriptor.getId()).thenReturn(TEST_ID);
        when(currentDescriptor.getVersion()).thenReturn(4);
        when(documentDescriptorStore.getCurrentResourceId(TEST_ID)).thenReturn(currentDescriptor);

        var descriptor = new DocumentDescriptor();
        when(documentDescriptorStore.readDescriptor(TEST_ID, 4)).thenReturn(descriptor);

        restVersionInfo.delete(TEST_ID, 2, true);

        assertTrue(descriptor.isDeleted());
        verify(documentDescriptorStore).setDescriptor(TEST_ID, 4, descriptor);
        verify(documentDescriptorStore, never()).setDescriptor(eq(TEST_ID), eq(2), any());
    }

    /**
     * CWE-117. The id is a path parameter and the store's message quotes it back,
     * so a newline in either forges a second log record — a fabricated WARN or
     * ERROR line that an operator (or a log-based alert) reads as the server's own.
     * {@code LogSanitizer} is the sanitiser the rest of the codebase already uses;
     * this pins that this site uses it too.
     */
    @Test
    void delete_descriptorFailureLogsTheIdSanitized() throws Exception {
        String poisonedId = "resource-id\nERROR [io.quarkus] forged log line";
        List<String> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(String.valueOf(record.getMessage()));
                if (record.getParameters() != null) {
                    for (Object parameter : record.getParameters()) {
                        captured.add(String.valueOf(parameter));
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        // logging.properties turns the whole ai.labs.eddi namespace OFF for plain
        // unit tests, so this logger has to be opened explicitly to see anything.
        Logger julLogger = Logger.getLogger(RestVersionInfo.class.getName());
        Level previousLevel = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
        try {
            when(documentDescriptorStore.readDescriptor(eq(poisonedId), any()))
                    .thenThrow(new ResourceNotFoundException("no descriptor"));

            restVersionInfo.delete(poisonedId, 2, false);
        } finally {
            julLogger.removeHandler(handler);
            julLogger.setLevel(previousLevel);
        }

        assertTrue(captured.stream().anyMatch(value -> value.contains("resource-id_ERROR")),
                "the WARN must have been captured with the id sanitized in place; captured: " + captured);
        assertTrue(captured.stream().noneMatch(value -> value.contains("\n")),
                "a newline reached the log, so a caller can forge log records; captured: " + captured);
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

    /**
     * The store's optimistic-lock failure must reach the caller, not be turned into
     * a 200 by the surrounding best-effort descriptor handling. This is the check
     * the cascades rely on to abort before they touch anything a still-live
     * resource references, so swallowing it here would silently re-open every one
     * of them.
     *
     * <p>
     * And the descriptor flagging this branch adds to the soft path has to sit
     * <em>after</em> the store call, not beside it: a live descriptor is supplied
     * below precisely so a flag written before the refused delete would be visible.
     * Mark first and a resource that is still at v2 disappears from every listing
     * ({@code readDescriptors(includeDeleted=false)} drops it) while the delete
     * that was supposed to remove it answered 409 — a resource nobody can find and
     * nobody deleted. The descriptor object is asserted directly rather than only
     * through {@code setDescriptor}, because {@code markDescriptorDeleted} mutates
     * the instance it read before it tries to store it.
     * </p>
     */
    @Test
    void delete_soft_propagatesAConcurrentModificationInsteadOfAnsweringOk() throws Exception {
        var liveDescriptor = new DocumentDescriptor();
        liveDescriptor.setDeleted(false);
        when(documentDescriptorStore.readDescriptor(TEST_ID, 2)).thenReturn(liveDescriptor);
        doThrow(new IResourceStore.ResourceModifiedException("someone else moved it to v3"))
                .when(resourceStore).delete(TEST_ID, 2);

        var thrown = assertThrows(IResourceStore.ResourceModifiedException.class,
                () -> restVersionInfo.delete(TEST_ID, 2, false));

        assertEquals("someone else moved it to v3", thrown.getMessage());
        // Nothing may claim the delete happened — neither in the descriptor store nor
        // on the descriptor itself.
        verify(documentDescriptorStore, never()).setDescriptor(anyString(), any(), any());
        verify(documentDescriptorStore, never()).readDescriptor(anyString(), any());
        assertFalse(liveDescriptor.isDeleted(),
                "a refused delete must leave the descriptor exactly as it found it");
    }
}
