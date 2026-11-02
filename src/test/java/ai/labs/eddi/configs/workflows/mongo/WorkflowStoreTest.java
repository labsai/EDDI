/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.mongo;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.configs.descriptors.ResourceUtilities;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;

import java.util.ArrayList;
import java.util.List;

import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class WorkflowStoreTest {

    private IResourceStorage<WorkflowConfiguration> resourceStorage;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IResourceClientLibrary resourceClientLibrary;
    private WorkflowStore store;

    @BeforeEach
    void setUp() {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        resourceStorage = mock(IResourceStorage.class);

        when(storageFactory.create(eq("workflows"), eq(documentBuilder), eq(WorkflowConfiguration.class),
                eq("workflowSteps.config.uri"), eq("workflowSteps.extensions.dictionaries.config.uri")))
                .thenReturn(resourceStorage);

        resourceClientLibrary = mock(IResourceClientLibrary.class);
        store = new WorkflowStore(storageFactory, documentBuilder, documentDescriptorStore, () -> resourceClientLibrary);
    }

    // ==================== persisted field paths ====================

    @Test
    @DisplayName("query paths match the field name WorkflowConfiguration actually serializes")
    void queryPathsMatchPersistedFieldName() throws Exception {
        WorkflowConfiguration config = new WorkflowConfiguration();
        config.getWorkflowSteps().add(new WorkflowConfiguration.WorkflowStep());
        String persisted = new ObjectMapper().writeValueAsString(config);

        // The paths were spelled "WorkflowSteps" while the document says
        // "workflowSteps". Both MongoDB paths and PostgreSQL JSON keys are
        // case-sensitive, so every reverse lookup silently matched nothing — which
        // is what turned the cascade-delete reference guard into a no-op.
        assertTrue(persisted.contains("\"workflowSteps\""), "unexpected persisted shape: " + persisted);
        assertTrue(persisted.contains("\"" + WorkflowStore.WORKFLOW_EXTENSIONS_FIELD + "\""),
                "WORKFLOW_EXTENSIONS_FIELD does not name a persisted field");

        String root = WorkflowStore.WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD.substring(0,
                WorkflowStore.WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD.indexOf('.'));
        assertTrue(persisted.contains("\"" + root + "\""), "config-uri query path does not start at a persisted field: " + root);
        assertEquals(root, WorkflowStore.WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD.substring(0,
                WorkflowStore.WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD.indexOf('.')));
    }

    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource — queries the persisted, case-correct paths")
    void queriesPersistedPaths() throws Exception {
        when(resourceStorage.findResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());

        store.getWorkflowDescriptorsContainingResource("eddi://ai.labs.output/outputstore/outputsets/out1?version=1", false);

        var pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceStorage, atLeastOnce()).findResourceIdsContaining(pathCaptor.capture(), anyString());
        assertEquals(List.of("workflowSteps.config.uri", "workflowSteps.extensions.dictionaries.config.uri"), pathCaptor.getAllValues());
    }

    // ==================== create ====================

    @Test
    @DisplayName("create — validates and delegates to parent")
    void create() throws Exception {
        WorkflowConfiguration config = new WorkflowConfiguration();
        config.setWorkflowSteps(new ArrayList<>());

        IResourceStorage.IResource<WorkflowConfiguration> resource = mock(IResourceStorage.IResource.class);
        when(resource.getId()).thenReturn("wf-1");
        when(resource.getVersion()).thenReturn(1);
        when(resourceStorage.newResource(config)).thenReturn(resource);
        when(resourceStorage.getCurrentVersion("wf-1")).thenReturn(1);

        IResourceStore.IResourceId result = store.create(config);
        assertNotNull(result);
    }

    // ==================== read ====================

    @Test
    @DisplayName("read — returns workflow config when found")
    void readFound() throws Exception {
        WorkflowConfiguration config = new WorkflowConfiguration();
        IResourceStorage.IResource<WorkflowConfiguration> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn(config);
        when(resourceStorage.read("wf-1", 1)).thenReturn(resource);
        when(resourceStorage.getCurrentVersion("wf-1")).thenReturn(1);

        WorkflowConfiguration result = store.read("wf-1", 1);
        assertSame(config, result);
    }

    @Test
    @DisplayName("read — throws ResourceNotFoundException when not found")
    void readNotFound() {
        when(resourceStorage.read("missing", 1)).thenReturn(null);
        when(resourceStorage.getCurrentVersion("missing")).thenReturn(-1);

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> store.read("missing", 1));
    }

    // ==================== getWorkflowDescriptorsContainingResource
    // ====================

    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource — finds descriptors")
    void getWorkflowDescriptorsContainingResource() throws Exception {
        String resourceURI = "eddi://ai.labs.behavior/behaviorId?version=1";

        IResourceStore.IResourceId workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("111111111111111111111111");
        when(workflowId.getVersion()).thenReturn(1);

        when(resourceStorage.findResourceIdsContaining(anyString(), anyString()))
                .thenReturn(List.of(workflowId));
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString()))
                .thenReturn(List.of());

        when(resourceStorage.getCurrentVersion("111111111111111111111111")).thenReturn(1);

        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/111111111111111111111111?version=1"));
        when(documentDescriptorStore.readDescriptor("111111111111111111111111", 1)).thenReturn(descriptor);

        List<DocumentDescriptor> result = store.getWorkflowDescriptorsContainingResource(resourceURI, false);
        assertEquals(1, result.size());
    }

    private static final String OUTPUT_ID = "aabbccddeeff112233445566";
    private static final String OUTPUT_URI = "eddi://ai.labs.output/outputstore/outputsets/" + OUTPUT_ID;

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        IResourceStore.IResourceId resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getId()).thenReturn(id);
        when(resourceId.getVersion()).thenReturn(version);
        return resourceId;
    }

    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource: a huge caller-supplied version walks only the versions that exist")
    void clampsVersionWalkToCurrentResourceVersion() throws Exception {
        IResourceStore.IResourceId current = resourceId(OUTPUT_ID, 2);
        when(resourceClientLibrary.getCurrentResourceId(URI.create(OUTPUT_URI + "?version=2000000000"))).thenReturn(current);
        when(resourceStorage.findResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());

        store.getWorkflowDescriptorsContainingResource(OUTPUT_URI + "?version=2000000000", true);

        // two config-uri paths per version, two versions
        verify(resourceStorage, times(4)).findResourceIdsContaining(anyString(), anyString());
    }

    /**
     * Descriptors advance only on an HTTP PUT/PATCH, so an in-process update leaves
     * the descriptor at v1 while the resource is at v2. The cascade delete and the
     * orphan purge ask at the live version (v2); clamping to the descriptor's v1
     * dropped every workflow that references v2 and reported the resource
     * unreferenced.
     */
    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource: the walk starts at the resource's live version, not a stale descriptor's")
    void clampUsesLiveVersionNotStaleDescriptor() throws Exception {
        IResourceStore.IResourceId staleDescriptor = resourceId(OUTPUT_ID, 1);
        IResourceStore.IResourceId live = resourceId(OUTPUT_ID, 2);
        lenient().when(documentDescriptorStore.getCurrentResourceId(OUTPUT_ID)).thenReturn(staleDescriptor);
        when(resourceClientLibrary.getCurrentResourceId(URI.create(OUTPUT_URI + "?version=2"))).thenReturn(live);
        IResourceStore.IResourceId referrer = resourceId("111111111111111111111111", 1);
        when(resourceStorage.findResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());
        when(resourceStorage.findResourceIdsContaining(WorkflowStore.WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD, OUTPUT_URI + "?version=2"))
                .thenReturn(List.of(referrer));
        when(resourceStorage.getCurrentVersion("111111111111111111111111")).thenReturn(1);
        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/111111111111111111111111?version=1"));
        when(documentDescriptorStore.readDescriptor("111111111111111111111111", 1)).thenReturn(descriptor);

        List<DocumentDescriptor> result = store.getWorkflowDescriptorsContainingResource(OUTPUT_URI + "?version=2", true);

        assertEquals(1, result.size(), "the workflow referencing the live version must be found");
    }

    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource: an exact lookup queries the version it was asked about, unclamped")
    void exactLookupIsNotClamped() throws Exception {
        when(resourceStorage.findResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());
        IResourceStore.IResourceId live = resourceId(OUTPUT_ID, 2);
        lenient().when(resourceClientLibrary.getCurrentResourceId(any())).thenReturn(live);
        lenient().when(documentDescriptorStore.getCurrentResourceId(OUTPUT_ID)).thenReturn(live);

        store.getWorkflowDescriptorsContainingResource(OUTPUT_URI + "?version=5", false);

        verify(resourceStorage).findResourceIdsContaining(WorkflowStore.WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD, OUTPUT_URI + "?version=5");
        verify(resourceStorage, times(2)).findResourceIdsContaining(anyString(), anyString());
    }

    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource: an unknown live version refuses a walk above the ceiling instead of truncating it")
    void unknownLiveVersionRefusesWalkAboveCeiling() throws Exception {
        when(resourceClientLibrary.getCurrentResourceId(any())).thenReturn(null);

        assertThrows(IResourceStore.ResourceStoreException.class, () -> store.getWorkflowDescriptorsContainingResource(
                OUTPUT_URI + "?version=" + (ResourceUtilities.MAX_REVERSE_LOOKUP_VERSIONS + 1), true));
        verifyNoInteractions(resourceStorage);
    }

    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource: an unknown live version still walks a version within the ceiling in full")
    void unknownLiveVersionWalksWithinCeiling() throws Exception {
        when(resourceClientLibrary.getCurrentResourceId(any())).thenReturn(null);
        when(resourceStorage.findResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());

        store.getWorkflowDescriptorsContainingResource(OUTPUT_URI + "?version=3", true);

        verify(resourceStorage, times(6)).findResourceIdsContaining(anyString(), anyString());
    }

    @Test
    @DisplayName("getWorkflowVersionsContainingResource: keeps superseded workflow versions an agent may still pin")
    void workflowVersionsIncludeSupersededVersions() throws Exception {
        IResourceStore.IResourceId live = resourceId(OUTPUT_ID, 1);
        when(resourceClientLibrary.getCurrentResourceId(any())).thenReturn(live);
        IResourceStore.IResourceId superseded = resourceId("111111111111111111111111", 1);
        when(resourceStorage.findResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString())).thenReturn(List.of());
        when(resourceStorage.findHistoryResourceIdsContaining(WorkflowStore.WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD, OUTPUT_URI + "?version=1"))
                .thenReturn(List.of(superseded));
        lenient().when(resourceStorage.getCurrentVersion("111111111111111111111111")).thenReturn(2);

        List<IResourceStore.IResourceId> versions = store.getWorkflowVersionsContainingResource(OUTPUT_URI + "?version=1");

        assertEquals(1, versions.size());
        assertEquals("111111111111111111111111", versions.get(0).getId());
        assertEquals(1, versions.get(0).getVersion());
    }

    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource — returns empty when no matches")
    void getWorkflowDescriptorsContainingResourceEmpty() throws Exception {
        when(resourceStorage.findResourceIdsContaining(anyString(), anyString()))
                .thenReturn(List.of());
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString()))
                .thenReturn(List.of());

        List<DocumentDescriptor> result = store.getWorkflowDescriptorsContainingResource(
                "eddi://ai.labs.behavior/behaviorId?version=1", false);
        assertTrue(result.isEmpty());
    }

    /**
     * A soft-deleted workflow keeps a history row that still contains the resource
     * URI, so the history search returns it — but it has no current row, and
     * {@code getCurrentVersion} answers -1, which made {@code getCurrentResourceId}
     * throw. The throw was unguarded, so one soft-deleted workflow made
     * {@code deleteResourceSafely} fail closed on EVERY resource from then on:
     * cascade cleanup became a permanent no-op with an ERROR per attempt.
     */
    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource — a soft-deleted workflow in the history hits is skipped, not fatal")
    void skipsHistoryOnlyWorkflowsWithNoCurrentVersion() throws Exception {
        String resourceURI = "eddi://ai.labs.output/outputstore/outputsets/out1?version=1";

        IResourceStore.IResourceId deletedWorkflow = mock(IResourceStore.IResourceId.class);
        when(deletedWorkflow.getId()).thenReturn("222222222222222222222222");
        when(deletedWorkflow.getVersion()).thenReturn(1);
        IResourceStore.IResourceId liveWorkflow = mock(IResourceStore.IResourceId.class);
        when(liveWorkflow.getId()).thenReturn("111111111111111111111111");
        when(liveWorkflow.getVersion()).thenReturn(1);

        when(resourceStorage.findResourceIdsContaining(anyString(), anyString())).thenReturn(List.of(liveWorkflow));
        when(resourceStorage.findHistoryResourceIdsContaining(anyString(), anyString())).thenReturn(List.of(deletedWorkflow));
        when(resourceStorage.getCurrentVersion("222222222222222222222222")).thenReturn(-1);
        when(resourceStorage.getCurrentVersion("111111111111111111111111")).thenReturn(1);

        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/111111111111111111111111?version=1"));
        when(documentDescriptorStore.readDescriptor("111111111111111111111111", 1)).thenReturn(descriptor);

        List<DocumentDescriptor> result = store.getWorkflowDescriptorsContainingResource(resourceURI, false);

        assertEquals(1, result.size());
        verify(documentDescriptorStore, never()).readDescriptor(eq("222222222222222222222222"), anyInt());
    }

    /**
     * The version used to be scraped as "everything after the last '='", which
     * threw an undeclared {@link NumberFormatException} for a URI with no version
     * query or with a second query parameter. Callers feed stored
     * {@code config.uri} values in verbatim, and the caller treats any throw as
     * "reference check failed — NOT cascade-deleting", so one unversioned reference
     * disabled cascade cleanup entirely.
     */
    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource — a URI without a usable version is a declared ResourceStoreException")
    void rejectsUnversionedResourceUri() {
        for (String uri : List.of("eddi://ai.labs.output/outputstore/outputsets/out1",
                "eddi://ai.labs.output/outputstore/outputsets/out1?other=2",
                "eddi://ai.labs.output/outputstore/outputsets/out1?version=abc",
                "eddi://ai.labs.output/outputstore/outputsets/out1?version=0")) {
            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> store.getWorkflowDescriptorsContainingResource(uri, false), "must refuse: " + uri);
        }
        verifyNoInteractions(resourceStorage);
    }

    /**
     * The inputs that make {@code URI.create} itself blow up, rather than merely
     * parsing to something unusable. This method is fed step {@code config.uri}
     * values verbatim by the cascade, and an unchecked
     * {@code IllegalArgumentException} is not in its {@code throws} clause: it
     * escapes the caller's {@code catch (ResourceStoreException)} and takes the
     * whole delete with it. Null, blank and syntactically illegal all have to come
     * back as the same declared refusal.
     */
    @Test
    @DisplayName("getWorkflowDescriptorsContainingResource — null, blank and unparsable URIs are the same declared refusal")
    void rejectsUnparsableResourceUri() {
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.getWorkflowDescriptorsContainingResource(null, false), "null must be refused, not NPE");
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.getWorkflowDescriptorsContainingResource("   ", false), "blank must be refused");
        // A space in the authority is a syntax error for java.net.URI.
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.getWorkflowDescriptorsContainingResource("eddi://ai labs/store/x?version=1", false),
                "an unparsable URI must be refused, not raise IllegalArgumentException");
        verifyNoInteractions(resourceStorage);
    }

    // ==================== deleteAllPermanently ====================

    @Test
    @DisplayName("deleteAllPermanently — removes all versions")
    void deleteAllPermanently() {
        store.deleteAllPermanently("wf-1");
        verify(resourceStorage).removeAllPermanently("wf-1");
    }
}
