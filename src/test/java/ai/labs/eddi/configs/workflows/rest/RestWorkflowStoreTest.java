/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.ResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.labs.eddi.utils.LogCaptureSupport.FORGED_RECORD;
import static ai.labs.eddi.utils.LogCaptureSupport.assertNoForgedRecordBoundary;
import static ai.labs.eddi.utils.LogCaptureSupport.captureLogsOf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RestWorkflowStoreTest {

    @Mock
    private IWorkflowStore WorkflowStore;
    @Mock
    private ResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;

    private RestWorkflowStore restWorkflowStore;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        restWorkflowStore = new RestWorkflowStore(WorkflowStore, resourceClientLibrary, documentDescriptorStore, jsonSchemaCreator,
                mock(ResourceAccessGuard.class));
        // Both fixtures are live at v1. A cascade only runs against the CURRENT
        // version: it tears the referenced configs down before the delete — the only
        // place the version used to be checked — has run.
        when(WorkflowStore.getCurrentResourceId("pkg1")).thenReturn(resourceId("pkg1", 1));
        when(WorkflowStore.getCurrentResourceId("wf1")).thenReturn(resourceId("wf1", 1));
        // Default: every referenced resource is live at exactly the version the
        // step pins, i.e. nothing has been edited since. The cascade resolves each
        // reference through this before it decides anything; the case where the two
        // disagree is CascadeVersionGuard.staleReferenceResolvesToTheCurrentVersion.
        when(resourceClientLibrary.getCurrentResourceId(any(URI.class))).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            return resourceId(uri.getPath(), pinnedVersion(uri));
        });
        // The workflow the cascade is deleting stops being a referrer the moment it
        // is deleted — AbstractResourceStore.isStaleReference drops a referrer with
        // no current row. Modelling that is what lets the post-delete re-check in
        // deleteWorkflow mean anything: with a stub frozen at its pre-delete answer,
        // "nobody else references this" and "one other workflow does" look alike.
        doAnswer(invocation -> {
            parentDeleted.set(true);
            return null;
        }).when(WorkflowStore).delete(anyString(), anyInt());
        doAnswer(invocation -> {
            parentDeleted.set(true);
            return null;
        }).when(WorkflowStore).deleteAllPermanently(anyString());
    }

    /**
     * The version a stored reference pins, defaulting to 1.
     *
     * <p>
     * A stored {@code config.uri} is arbitrary text — hand-written, imported, or
     * carrying a second query parameter — so a bare
     * {@code Integer.parseInt(query.substring(...))} threw
     * {@code NumberFormatException} out of the mock for input the production code
     * handles. The default is the same one the missing-query case already used.
     * </p>
     */
    private static int pinnedVersion(URI uri) {
        String query = uri.getQuery();
        if (query == null || !query.startsWith("version=")) {
            return 1;
        }
        try {
            return Integer.parseInt(query.substring("version=".length()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Whether the workflow under test has been deleted yet; see {@link #referrers}.
     */
    private final AtomicBoolean parentDeleted = new AtomicBoolean();

    /**
     * The reverse lookup's answer, as the real store gives it: {@code total} counts
     * the workflow being deleted among the referrers, and that one disappears once
     * it has been deleted.
     */
    private List<DocumentDescriptor> referrers(int total) {
        int remaining = parentDeleted.get() ? total - 1 : total;
        List<DocumentDescriptor> descriptors = new ArrayList<>();
        for (int i = 0; i < remaining; i++) {
            descriptors.add(new DocumentDescriptor());
        }
        return descriptors;
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    /**
     * Helper — by default, resources are only referenced by 1 package (safe to
     * delete)
     */
    private void mockSingleReference() throws Exception {
        when(WorkflowStore.getWorkflowDescriptorsContainingResource(anyString(), eq(true))).thenAnswer(invocation -> referrers(1));
    }

    @Nested
    @DisplayName("deleteWorkflow")
    class DeleteWorkflowTests {

        @Test
        @DisplayName("should delete package without cascade when cascade=false")
        void deleteWorkflow_noCascade() throws Exception {
            restWorkflowStore.deleteWorkflow("pkg1", 1, false, false);

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("should cascade-delete extension resources when cascade=true")
        void deleteWorkflow_cascade_deletesExtensions() throws Exception {
            mockSingleReference();

            WorkflowConfiguration config = new WorkflowConfiguration();

            WorkflowStep behaviorExt = new WorkflowStep();
            behaviorExt.setType(URI.create("eddi://ai.labs.rules"));
            behaviorExt.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1")));
            config.getWorkflowSteps().add(behaviorExt);

            WorkflowStep httpExt = new WorkflowStep();
            httpExt.setType(URI.create("eddi://ai.labs.apicalls"));
            httpExt.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/http1?version=3")));
            config.getWorkflowSteps().add(httpExt);

            WorkflowStep outputExt = new WorkflowStep();
            outputExt.setType(URI.create("eddi://ai.labs.output"));
            outputExt.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1")));
            config.getWorkflowSteps().add(outputExt);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            restWorkflowStore.deleteWorkflow("pkg1", 1, true, true);

            // permanent=false on every cascaded resource, even though the request asked
            // for permanent=true. These three assertions used to read `true` and so
            // pinned the defect: the "is anyone else using this?" guard is version-scoped
            // while deleteAllPermanently is id-scoped and erases every version plus all
            // history, so a workflow pinning a different version lost the config outright.
            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1"), false);
            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.apicalls/apicallstore/apicalls/http1?version=3"), false);
            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=1"), false);
        }

        @Test
        @DisplayName("should cascade-delete parser dictionaries")
        void deleteWorkflow_cascade_deletesParserDictionaries() throws Exception {
            mockSingleReference();

            WorkflowConfiguration config = new WorkflowConfiguration();

            WorkflowStep parserExt = new WorkflowStep();
            parserExt.setType(URI.create("eddi://ai.labs.parser"));

            Map<String, Object> dictEntry = new HashMap<>();
            dictEntry.put("type", "eddi://ai.labs.parser.dictionaries.regular");
            dictEntry.put("config", new HashMap<>(Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/dict1?version=1")));

            List<Map<String, Object>> dictionaries = new ArrayList<>();
            dictionaries.add(dictEntry);
            parserExt.getExtensions().put("dictionaries", dictionaries);

            config.getWorkflowSteps().add(parserExt);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            restWorkflowStore.deleteWorkflow("pkg1", 1, true, true);

            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/dict1?version=1"), false);
        }

        @Test
        @DisplayName("should skip resources shared with other packages")
        void deleteWorkflow_cascade_skipsSharedResources() throws Exception {
            WorkflowConfiguration config = new WorkflowConfiguration();

            // Behavior extension — shared with 2 packages → should be SKIPPED
            WorkflowStep sharedExt = new WorkflowStep();
            sharedExt.setType(URI.create("eddi://ai.labs.rules"));
            sharedExt.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/shared1?version=1")));
            config.getWorkflowSteps().add(sharedExt);

            // Output extension — only in this package → should be deleted
            WorkflowStep uniqueExt = new WorkflowStep();
            uniqueExt.setType(URI.create("eddi://ai.labs.output"));
            uniqueExt.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/unique1?version=1")));
            config.getWorkflowSteps().add(uniqueExt);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);

            // Shared resource referenced by 2 packages
            String sharedUri = "eddi://ai.labs.rules/rulestore/rulesets/shared1?version=1";
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(eq(sharedUri), eq(true))).thenAnswer(invocation -> referrers(2));

            // Unique resource referenced by only 1 package
            String uniqueUri = "eddi://ai.labs.output/outputstore/outputsets/unique1?version=1";
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(eq(uniqueUri), eq(true))).thenAnswer(invocation -> referrers(1));

            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            restWorkflowStore.deleteWorkflow("pkg1", 1, true, true);

            // Only the unique resource should be deleted
            verify(resourceClientLibrary, never()).deleteResource(eq(URI.create(sharedUri)), anyBoolean());
            verify(resourceClientLibrary).deleteResource(URI.create(uniqueUri), false);
        }

        @Test
        @DisplayName("two workflows sharing one output config — deleting one leaves the shared config alive")
        void deleteWorkflow_cascade_sharedOutputConfigSurvives() throws Exception {
            String sharedOutputUri = "eddi://ai.labs.output/outputstore/outputsets/shared-out?version=1";

            WorkflowConfiguration workflowBeingDeleted = new WorkflowConfiguration();
            WorkflowStep outputStep = new WorkflowStep();
            outputStep.setType(URI.create("eddi://ai.labs.output"));
            outputStep.setConfig(new HashMap<>(Map.of("uri", sharedOutputUri)));
            workflowBeingDeleted.getWorkflowSteps().add(outputStep);

            when(WorkflowStore.read("wf1", 1)).thenReturn(workflowBeingDeleted);
            // Both wf1 (the one being deleted) and wf2 reference the same output set.
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(eq(sharedOutputUri), eq(true))).thenAnswer(invocation -> referrers(2));

            restWorkflowStore.deleteWorkflow("wf1", 1, true, true);

            verify(resourceClientLibrary, never()).deleteResource(eq(URI.create(sharedOutputUri)), anyBoolean());
        }

        @Test
        @DisplayName("reference check failure must FAIL CLOSED — never cascade-delete on an unanswered query")
        void deleteWorkflow_cascade_referenceCheckFailsClosed() throws Exception {
            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep outputStep = new WorkflowStep();
            outputStep.setType(URI.create("eddi://ai.labs.output"));
            outputStep.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1")));
            config.getWorkflowSteps().add(outputStep);

            when(WorkflowStore.read("wf1", 1)).thenReturn(config);
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(anyString(), eq(true)))
                    .thenThrow(new IResourceStore.ResourceStoreException("index unavailable"));

            restWorkflowStore.deleteWorkflow("wf1", 1, true, true);

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("should continue when individual resource delete fails")
        void deleteWorkflow_cascade_partialFailure() throws Exception {
            mockSingleReference();

            WorkflowConfiguration config = new WorkflowConfiguration();

            WorkflowStep ext1 = new WorkflowStep();
            ext1.setType(URI.create("eddi://ai.labs.rules"));
            ext1.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1")));
            config.getWorkflowSteps().add(ext1);

            WorkflowStep ext2 = new WorkflowStep();
            ext2.setType(URI.create("eddi://ai.labs.output"));
            ext2.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1")));
            config.getWorkflowSteps().add(ext2);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(URI.create("eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1"), false))
                    .thenThrow(new ServiceException("DB error"));
            when(resourceClientLibrary.deleteResource(URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=1"), false))
                    .thenReturn(Response.ok().build());

            assertDoesNotThrow(() -> restWorkflowStore.deleteWorkflow("pkg1", 1, true, true));

            verify(resourceClientLibrary, times(2)).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("should handle package not found for cascade gracefully")
        void deleteWorkflow_cascade_packageNotFound() throws Exception {
            when(WorkflowStore.read("pkg1", 1)).thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertDoesNotThrow(() -> restWorkflowStore.deleteWorkflow("pkg1", 1, true, true));

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("should skip extensions without config.uri")
        void deleteWorkflow_cascade_noUri() throws Exception {
            WorkflowConfiguration config = new WorkflowConfiguration();

            WorkflowStep ext = new WorkflowStep();
            ext.setType(URI.create("eddi://ai.labs.parser"));
            config.getWorkflowSteps().add(ext);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);

            assertDoesNotThrow(() -> restWorkflowStore.deleteWorkflow("pkg1", 1, true, true));

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("deleteWorkflow — cascade version guard")
    class CascadeVersionGuard {

        /**
         * {@code workflowStore.read} falls back to history for a superseded version,
         * and the version was only checked at the very end, inside
         * {@code restVersionInfo.delete()}. So a cascade addressed at a stale version
         * tore down that version's rule sets, output sets and dictionaries and only
         * then answered 409 — leaving the live workflow pointing at configs that no
         * longer existed.
         */
        @Test
        @DisplayName("refuses a stale version with 409 before deleting any referenced resource")
        void staleVersionRefusedBeforeAnyDeletion() throws Exception {
            when(WorkflowStore.getCurrentResourceId("pkg1")).thenReturn(resourceId("pkg1", 2));

            var thrown = assertThrows(WebApplicationException.class,
                    () -> restWorkflowStore.deleteWorkflow("pkg1", 1, false, true));

            assertEquals(Response.Status.CONFLICT.getStatusCode(), thrown.getResponse().getStatus());
            verify(WorkflowStore, never()).read(anyString(), anyInt());
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
            verify(WorkflowStore, never()).delete(anyString(), anyInt());
        }

        @Test
        @DisplayName("version=0 resolves to the current version and does cascade")
        void versionZeroCascades() throws Exception {
            mockSingleReference();

            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep outputStep = new WorkflowStep();
            outputStep.setType(URI.create("eddi://ai.labs.output"));
            outputStep.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1")));
            config.getWorkflowSteps().add(outputStep);
            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            restWorkflowStore.deleteWorkflow("pkg1", 0, false, true);

            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=1"), false);
            verify(WorkflowStore).delete("pkg1", 1);
        }

        /**
         * The ordinary "purge what I already soft-deleted" flow: a workflow with no
         * current row makes {@code getCurrentResourceId} throw. The contract is to skip
         * the cascade and still purge the remaining history — so the guard has to
         * answer false rather than propagate or dereference the missing current id,
         * which on this path would abort a destructive operation partway through.
         */
        @Test
        @DisplayName("an already soft-deleted workflow skips the cascade and still purges")
        void softDeletedWorkflowSkipsCascadeButStillPurges() throws Exception {
            when(WorkflowStore.getCurrentResourceId("pkg1"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("no current version"));

            assertDoesNotThrow(() -> restWorkflowStore.deleteWorkflow("pkg1", 1, true, true));

            verify(WorkflowStore, never()).read(anyString(), anyInt());
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
            verify(WorkflowStore).deleteAllPermanently("pkg1");
        }
    }

    /**
     * The deep copy walks the same stored shape as the cascade and had the same
     * blind cast. It matters for the same reason: a throw here leaves behind the
     * sub-resources it had already created and persisted.
     */
    @Nested
    @DisplayName("duplicateWorkflow \u2014 malformed stored steps")
    class MalformedStepsOnTheDeepCopy {

        @Test
        @DisplayName("a dictionaries block that is an object, not a list, does not abort the deep copy")
        void nonListDictionariesDoesNotAbortTheDeepCopy() throws Exception {
            WorkflowConfiguration config = new WorkflowConfiguration();

            WorkflowStep parserStep = new WorkflowStep();
            parserStep.setType(URI.create("eddi://ai.labs.parser"));
            parserStep.getExtensions().put("dictionaries", new HashMap<String, Object>());
            config.getWorkflowSteps().add(parserStep);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(WorkflowStore.create(any())).thenReturn(resourceId("copy1", 1));
            when(documentDescriptorStore.readDescriptor("pkg1", 1)).thenReturn(new DocumentDescriptor());

            assertDoesNotThrow(() -> restWorkflowStore.duplicateWorkflow("pkg1", 1, true));
        }

        /**
         * Only {@code ai.labs.parser.dictionaries.regular} entries are separately
         * stored resources with their own id; the other dictionary kinds are inline
         * configuration, and a stored entry may carry no {@code type} at all. Copying
         * either would ask {@code duplicateResource} for a resource that does not exist
         * — and that throws, on a path that has already persisted the sub-resources it
         * created before it.
         */
        @Test
        @DisplayName("a deep copy only duplicates regular dictionaries, and skips entries it cannot classify")
        void deepCopyOnlyDuplicatesRegularDictionaries() throws Exception {
            WorkflowConfiguration config = new WorkflowConfiguration();

            WorkflowStep parserStep = new WorkflowStep();
            parserStep.setType(URI.create("eddi://ai.labs.parser"));

            Map<String, Object> notAnObject = new HashMap<>();
            notAnObject.put("type", "eddi://ai.labs.parser.dictionaries.regular");
            // Present but not a Map, so there is nothing to read a uri out of.
            notAnObject.put("config", "just-a-string");

            Map<String, Object> typeless = new HashMap<>();
            typeless.put("config", new HashMap<>(Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/typeless?version=1")));

            Map<String, Object> otherKind = new HashMap<>();
            otherKind.put("type", "eddi://ai.labs.parser.dictionaries.integer");
            otherKind.put("config", new HashMap<>(Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/ints?version=1")));

            Map<String, Object> noUri = new HashMap<>();
            noUri.put("type", "eddi://ai.labs.parser.dictionaries.regular");
            noUri.put("config", new HashMap<String, Object>());

            parserStep.getExtensions().put("dictionaries",
                    new ArrayList<>(List.of("not-a-map", notAnObject, typeless, otherKind, noUri)));
            config.getWorkflowSteps().add(parserStep);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(WorkflowStore.create(any())).thenReturn(resourceId("copy1", 1));
            when(documentDescriptorStore.readDescriptor("pkg1", 1)).thenReturn(new DocumentDescriptor());

            Response response = restWorkflowStore.duplicateWorkflow("pkg1", 1, true);

            assertEquals(201, response.getStatus());
            verify(resourceClientLibrary, never()).duplicateResource(any());
        }
    }

    /**
     * Stored shapes the cascade walks. {@code "extensions": null} is what Jackson
     * leaves for an explicit JSON null, and this runs on the DESTRUCTIVE path: an
     * NPE here aborts the cascade mid-way, after some referenced resources have
     * already been deleted.
     */
    @Nested
    @DisplayName("deleteWorkflow — malformed stored steps")
    class MalformedStepsOnTheCascade {

        @Test
        @DisplayName("a parser step with null extensions does not abort the cascade")
        void nullExtensionsOnAParserStepDoesNotAbortTheCascade() throws Exception {
            mockSingleReference();

            WorkflowConfiguration config = new WorkflowConfiguration();

            WorkflowStep parserStep = new WorkflowStep();
            parserStep.setType(URI.create("eddi://ai.labs.parser"));
            parserStep.setExtensions(null);
            config.getWorkflowSteps().add(parserStep);

            // Ordered after the malformed step, so it is only reached if the cascade
            // survived it.
            WorkflowStep outputStep = new WorkflowStep();
            outputStep.setType(URI.create("eddi://ai.labs.output"));
            outputStep.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1")));
            config.getWorkflowSteps().add(outputStep);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            restWorkflowStore.deleteWorkflow("pkg1", 1, false, true);

            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=1"), false);
            verify(WorkflowStore).delete("pkg1", 1);
        }

        /**
         * {@code "dictionaries": {}} — an object where the cascade expects an array —
         * is stored data the API accepts, and a blind
         * {@code (List<Map<String, Object>>)} cast on it throws
         * {@code ClassCastException} out of the destructive walk. Same shape
         * {@code updateResourceInWorkflow} already handles defensively.
         */
        @Test
        @DisplayName("a dictionaries block that is an object, not a list, does not abort the cascade")
        void nonListDictionariesDoesNotAbortTheCascade() throws Exception {
            mockSingleReference();

            WorkflowConfiguration config = new WorkflowConfiguration();

            WorkflowStep parserStep = new WorkflowStep();
            parserStep.setType(URI.create("eddi://ai.labs.parser"));
            parserStep.getExtensions().put("dictionaries", new HashMap<String, Object>());
            config.getWorkflowSteps().add(parserStep);

            WorkflowStep listWithJunk = new WorkflowStep();
            listWithJunk.setType(URI.create("eddi://ai.labs.parser"));
            listWithJunk.getExtensions().put("dictionaries", new ArrayList<>(List.of("not-an-object")));
            config.getWorkflowSteps().add(listWithJunk);

            // Ordered after both malformed steps, so it is only reached if the cascade
            // survived them.
            WorkflowStep outputStep = new WorkflowStep();
            outputStep.setType(URI.create("eddi://ai.labs.output"));
            outputStep.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1")));
            config.getWorkflowSteps().add(outputStep);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            assertDoesNotThrow(() -> restWorkflowStore.deleteWorkflow("pkg1", 1, false, true));

            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=1"), false);
        }

        /**
         * A parser step's {@code dictionaries} array holds every dictionary kind the
         * parser understands, but only {@code ai.labs.parser.dictionaries.regular} is a
         * separately stored resource with its own id. The others are inline
         * configuration, so cascading a delete at them would address a resource that
         * does not exist — and an entry with no {@code type} at all is stored data the
         * API accepts. Both are skipped, and skipping them must not stop the rest of
         * the step being walked.
         */
        @Test
        @DisplayName("only regular dictionaries are cascaded; other kinds and typeless entries are skipped")
        void nonRegularDictionaryEntriesAreSkipped() throws Exception {
            mockSingleReference();

            WorkflowConfiguration config = new WorkflowConfiguration();

            WorkflowStep parserStep = new WorkflowStep();
            parserStep.setType(URI.create("eddi://ai.labs.parser"));

            Map<String, Object> typeless = new HashMap<>();
            typeless.put("config", new HashMap<>(Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/typeless?version=1")));

            Map<String, Object> otherKind = new HashMap<>();
            otherKind.put("type", "eddi://ai.labs.parser.dictionaries.integer");
            otherKind.put("config", new HashMap<>(Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/ints?version=1")));

            Map<String, Object> regular = new HashMap<>();
            regular.put("type", "eddi://ai.labs.parser.dictionaries.regular");
            regular.put("config", new HashMap<>(Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/dict1?version=1")));

            List<Map<String, Object>> dictionaries = new ArrayList<>(List.of(typeless, otherKind, regular));
            parserStep.getExtensions().put("dictionaries", dictionaries);
            config.getWorkflowSteps().add(parserStep);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            Response response = restWorkflowStore.deleteWorkflow("pkg1", 1, false, true);

            verify(resourceClientLibrary)
                    .deleteResource(URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/dict1?version=1"), false);
            verify(resourceClientLibrary, never())
                    .deleteResource(eq(URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/typeless?version=1")), anyBoolean());
            verify(resourceClientLibrary, never())
                    .deleteResource(eq(URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/ints?version=1")), anyBoolean());
            assertNull(response.getHeaderString("X-Cascade-Skipped"),
                    "entries that are not separately stored resources are not 'skipped' cascade candidates");
        }

        /**
         * {@code X-Cascade-Skipped} counts RESOURCES the cascade decided not to delete,
         * never STEPS it walked past. Both kinds of step that name nothing to cascade —
         * one with no {@code type} (accepted by {@code WorkflowStore.create}) and one
         * whose {@code config} block carries no {@code uri} — are put in the same
         * workflow as one genuinely skipped resource, so the header has to answer
         * exactly "1".
         *
         * <p>
         * The exact number is the whole point. Asserting only that the header is absent
         * when nothing was skipped cannot distinguish this bookkeeping from no
         * bookkeeping at all: {@code getHeaderString} answers {@code null} for a header
         * that was never invented. Making a walked-past step increment
         * {@code skipped[0]} turns this into "3", which is the header lying about work
         * that was never there to do — the failure mode the counter exists to prevent.
         * </p>
         */
        @Test
        @DisplayName("steps with no type and configs with no uri are walked past, not counted as skipped")
        void stepsWithNothingToCascadeAreNotCounted() throws Exception {
            String cascaded = "eddi://ai.labs.output/outputstore/outputsets/out1?version=1";
            String shared = "eddi://ai.labs.output/outputstore/outputsets/shared1?version=1";
            mockSingleReference();
            // One resource that IS still referenced elsewhere: the only legitimate skip
            // in this workflow.
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(eq(shared), eq(true))).thenAnswer(invocation -> referrers(2));

            WorkflowConfiguration config = new WorkflowConfiguration();

            WorkflowStep typeless = new WorkflowStep();
            typeless.setConfig(new HashMap<>(Map.of("uri", cascaded)));
            config.getWorkflowSteps().add(typeless);

            WorkflowStep noUri = new WorkflowStep();
            noUri.setType(URI.create("eddi://ai.labs.rules"));
            noUri.setConfig(new HashMap<>(Map.of("someOtherKey", "value")));
            config.getWorkflowSteps().add(noUri);

            WorkflowStep stillReferenced = new WorkflowStep();
            stillReferenced.setType(URI.create("eddi://ai.labs.output"));
            stillReferenced.setConfig(new HashMap<>(Map.of("uri", shared)));
            config.getWorkflowSteps().add(stillReferenced);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            Response response = restWorkflowStore.deleteWorkflow("pkg1", 1, false, true);

            // The typeless step still has a config.uri, so its resource IS cascaded —
            // "no type" only means "no parser dictionaries to walk".
            verify(resourceClientLibrary).deleteResource(URI.create(cascaded), false);
            verify(resourceClientLibrary, never()).deleteResource(eq(URI.create(shared)), anyBoolean());
            assertEquals("1", response.getHeaderString("X-Cascade-Skipped"),
                    "only the still-referenced resource was skipped; walking past a step is not a skip");
        }

        /**
         * A stored reference that carries no version query at all — hand-written, or
         * imported from an older export. Re-addressing it at the live version must
         * produce a usable URI rather than appending a second query to a string that
         * has none.
         */
        @Test
        @DisplayName("a reference with no version query is re-addressed at the live version")
        void unversionedReferenceIsReAddressedAtTheLiveVersion() throws Exception {
            mockSingleReference();

            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.output"));
            step.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/out1")));
            config.getWorkflowSteps().add(step);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.getCurrentResourceId(URI.create("eddi://ai.labs.output/outputstore/outputsets/out1")))
                    .thenReturn(resourceId("out1", 6));
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            restWorkflowStore.deleteWorkflow("pkg1", 1, false, true);

            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=6"), false);
        }

        /**
         * A reverse lookup that answers {@code null} rather than throwing has told us
         * nothing, and this guard is the only thing standing between an irreversible
         * cascade and a config another workflow still uses. It has to fail CLOSED, and
         * the response has to admit that it skipped something — a cascade that deleted
         * nothing must not answer identically to one that deleted everything.
         */
        @Test
        @DisplayName("a reference check that answers null is skipped, not treated as unreferenced")
        void nullReferenceAnswerFailsClosed() throws Exception {
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(anyString(), eq(true))).thenReturn(null);

            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.output"));
            step.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1")));
            config.getWorkflowSteps().add(step);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);

            Response response = restWorkflowStore.deleteWorkflow("pkg1", 1, false, true);

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
            verify(WorkflowStore).delete("pkg1", 1);
            assertEquals("1", response.getHeaderString("X-Cascade-Skipped"),
                    "a reference check that could not answer must be reported, not silently treated as 'nobody uses it'");
        }

        /**
         * The shared-resource decision is made BEFORE the workflow is deleted — it has
         * to be, so the workflow still counts among the referrers. A workflow created,
         * or re-pointed at one of these configs, in the window between the plan and the
         * child delete would otherwise have its newly shared configuration soft-deleted
         * underneath it. The re-check runs after the parent is gone, so the only safe
         * answer is zero referrers.
         */
        @Test
        @DisplayName("a resource that becomes referenced between the plan and the child delete is not deleted")
        void resourceThatBecomesSharedDuringTheCascadeIsSkipped() throws Exception {
            String outputUri = "eddi://ai.labs.output/outputstore/outputsets/out1?version=1";
            // One referrer at both moments, but not the SAME one: when the cascade is
            // planned it is this workflow (so the resource is a candidate), and by the
            // time the child delete runs this workflow is gone and the one referrer is
            // a workflow that has just been pointed at the config.
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(eq(outputUri), eq(true)))
                    .thenReturn(List.of(new DocumentDescriptor()));

            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.output"));
            step.setConfig(new HashMap<>(Map.of("uri", outputUri)));
            config.getWorkflowSteps().add(step);
            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            Response response = restWorkflowStore.deleteWorkflow("pkg1", 1, false, true);

            verify(WorkflowStore).delete("pkg1", 1);
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
            assertEquals("1", response.getHeaderString("X-Cascade-Skipped"),
                    "the resource became shared during the cascade, so it was left alone — and the response has to say so");
        }

        /**
         * The counterpart: nothing appeared in the window, so the re-check finds no
         * referrer left and the delete goes through. Without this the test above would
         * also pass against a cascade that had simply stopped deleting.
         */
        @Test
        @DisplayName("a resource nobody picked up during the cascade is still deleted")
        void resourceThatStaysUnreferencedIsStillDeleted() throws Exception {
            mockSingleReference();

            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.output"));
            step.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1")));
            config.getWorkflowSteps().add(step);
            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            Response response = restWorkflowStore.deleteWorkflow("pkg1", 1, false, true);

            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=1"), false);
            assertNull(response.getHeaderString("X-Cascade-Skipped"));
        }
    }

    /**
     * What the cascade does when a step's pinned version and the version that
     * actually exists disagree — which is the NORMAL state, since a reference is
     * not re-pointed when the resource it names is edited.
     */
    @Nested
    @DisplayName("deleteWorkflow \u2014 references resolve to the current version")
    class StaleReferences {

        /**
         * The workflow pins {@code out1?version=1}; the output set has since been
         * edited twice and is at v3. Against the pinned version BOTH halves of the
         * cascade were wrong at once: the reference check asked who else pins a version
         * nobody may still pin, and {@code deleteResource} was then rejected by the
         * store as a stale version and swallowed as a WARN — so {@code cascade=true}
         * deleted nothing at all for any resource that had ever been edited, while the
         * API description said it had soft-deleted them.
         */
        @Test
        @DisplayName("a stale pinned reference is deleted at the resource's current version, not the pinned one")
        void staleReferenceResolvesToTheCurrentVersion() throws Exception {
            String pinned = "eddi://ai.labs.output/outputstore/outputsets/out1?version=1";
            String current = "eddi://ai.labs.output/outputstore/outputsets/out1?version=3";

            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep outputStep = new WorkflowStep();
            outputStep.setType(URI.create("eddi://ai.labs.output"));
            outputStep.setConfig(new HashMap<>(Map.of("uri", pinned)));
            config.getWorkflowSteps().add(outputStep);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.getCurrentResourceId(URI.create(pinned)))
                    .thenReturn(resourceId("/outputstore/outputsets/out1", 3));
            // Only this workflow references it, asked at the version that exists.
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(eq(current), eq(true))).thenAnswer(invocation -> referrers(1));
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build());

            restWorkflowStore.deleteWorkflow("pkg1", 1, false, true);

            verify(resourceClientLibrary).deleteResource(URI.create(current), false);
            verify(resourceClientLibrary, never()).deleteResource(eq(URI.create(pinned)), anyBoolean());
        }

        /**
         * A reference whose current version cannot be resolved — an unregistered type,
         * or a resource with no live version left — is skipped rather than guessed at,
         * and the response says so.
         */
        @Test
        @DisplayName("an unresolvable reference is skipped and counted in X-Cascade-Skipped")
        void unresolvableReferenceIsSkippedAndCounted() throws Exception {
            String pinned = "eddi://ai.labs.unknown/unknownstore/unknowns/u1?version=1";

            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.unknown"));
            step.setConfig(new HashMap<>(Map.of("uri", pinned)));
            config.getWorkflowSteps().add(step);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.getCurrentResourceId(URI.create(pinned))).thenReturn(null);

            Response response = restWorkflowStore.deleteWorkflow("pkg1", 1, false, true);

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
            assertEquals("1", response.getHeaderString("X-Cascade-Skipped"),
                    "a cascade that skipped everything must not answer exactly like one that deleted everything");
        }

        /**
         * The pre-cascade version check is a check-then-act. A concurrent update
         * committing between it and the delete used to leave the referenced resources
         * torn down while the delete itself answered 409 for a stale version — the
         * exact destructive outcome the version check exists to prevent. Deleting the
         * workflow FIRST makes the store's own version check the gate.
         */
        @Test
        @DisplayName("a workflow that moved on between the guard and the delete loses nothing it referenced")
        void concurrentUpdateBetweenGuardAndDeleteCascadesNothing() throws Exception {
            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep outputStep = new WorkflowStep();
            outputStep.setType(URI.create("eddi://ai.labs.output"));
            outputStep.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1")));
            config.getWorkflowSteps().add(outputStep);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(anyString(), eq(true))).thenAnswer(invocation -> referrers(1));
            // The guard saw v1; by the time the delete runs the workflow is at v2.
            doThrow(new IResourceStore.ResourceModifiedException("not the latest version")).when(WorkflowStore).delete("pkg1", 1);

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> restWorkflowStore.deleteWorkflow("pkg1", 1, false, true));

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        /**
         * Version resolution has to fail closed PER RESOURCE, exactly like the
         * reference check that follows it. {@code getCurrentResourceId} only absorbs
         * {@code ResourceNotFoundException}: the Mongo store's
         * {@code getCurrentVersion} does {@code new ObjectId(id)}, which raises
         * {@code IllegalArgumentException} for the 18-23 char hex and UUID-shaped ids
         * {@code RestUtilities.isValidId} accepts, and a transport failure surfaces as
         * {@code MongoException}. Uncaught, ONE hand-written or imported step reference
         * aborted the whole {@code cascade=true} delete with a 500 — before the
         * workflow itself was deleted, so the workflow stayed undeletable-with-cascade
         * until someone edited the stray reference out.
         */
        @Test
        @DisplayName("a reference that cannot be resolved at all is skipped — the workflow is still deleted")
        void unresolvableReferenceDoesNotAbortTheWholeDelete() throws Exception {
            String pinned = "eddi://ai.labs.output/outputstore/outputsets/not-an-objectid?version=1";

            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep outputStep = new WorkflowStep();
            outputStep.setType(URI.create("eddi://ai.labs.output"));
            outputStep.setConfig(new HashMap<>(Map.of("uri", pinned)));
            config.getWorkflowSteps().add(outputStep);

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.getCurrentResourceId(URI.create(pinned)))
                    .thenThrow(new IllegalArgumentException("invalid hexadecimal representation of an ObjectId"));

            Response response = assertDoesNotThrow(() -> restWorkflowStore.deleteWorkflow("pkg1", 1, false, true),
                    "one unresolvable step reference must not turn the whole cascade delete into a 500");

            // The workflow itself is gone, and the operator is told one resource was left.
            verify(WorkflowStore).delete("pkg1", 1);
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
            assertEquals("1", response.getHeaderString("X-Cascade-Skipped"),
                    "the unresolvable reference must be reported as skipped, not swallowed");
        }

        /**
         * Two steps naming the same resource is ordinary (two httpcalls steps on one
         * apicalls config). Without de-duplication the second delete found the resource
         * already soft-deleted, {@code HistorizedResourceStore.delete} raised
         * {@code ResourceNotFoundException}, and the delete loop counted that as a
         * skip: a cascade that removed everything it walked answered
         * {@code X-Cascade-Skipped: 1}. The header exists to stop the response lying
         * about the cascade, so it must not lie the other way either.
         */
        @Test
        @DisplayName("two steps naming the same resource delete it once and report nothing skipped")
        void duplicateReferenceIsDeletedOnceAndNotReportedAsSkipped() throws Exception {
            String pinned = "eddi://ai.labs.apicalls/apicallstore/apicalls/api1?version=1";

            WorkflowConfiguration config = new WorkflowConfiguration();
            for (int i = 0; i < 2; i++) {
                WorkflowStep step = new WorkflowStep();
                step.setType(URI.create("eddi://ai.labs.httpcalls"));
                step.setConfig(new HashMap<>(Map.of("uri", pinned)));
                config.getWorkflowSteps().add(step);
            }

            when(WorkflowStore.read("pkg1", 1)).thenReturn(config);
            mockSingleReference();
            // Deleting the same resource a second time is what used to be counted as a
            // skip: by then it is already soft-deleted and the store refuses.
            when(resourceClientLibrary.deleteResource(any(), anyBoolean())).thenReturn(Response.ok().build())
                    .thenThrow(new ServiceException("resource not found"));

            Response response = restWorkflowStore.deleteWorkflow("pkg1", 1, false, true);

            verify(resourceClientLibrary, times(1)).deleteResource(URI.create(pinned), false);
            assertNull(response.getHeaderString("X-Cascade-Skipped"),
                    "a cascade that deleted everything it walked must not report a skip");
        }
    }
    /**
     * CWE-117 (log injection). {@code id} is the DELETE path parameter and the
     * store's message quotes caller input back, so a CR/LF in either closes the
     * real record and lets the remainder read as a second line the server wrote.
     *
     * <p>
     * These assert the CONTRACT, not the call: drive a forged record through the
     * real cascade path and require that nothing carrying a record boundary reached
     * the log. Removing either {@code sanitize(...)} fails them.
     * </p>
     */
    @Nested
    @DisplayName("deleteWorkflow — log injection (CWE-117)")
    class LogInjection {

        /** The workflow id as an attacker supplies it on the DELETE path. */
        private static final String POISONED_WORKFLOW_ID = "pkg1" + FORGED_RECORD;

        /**
         * Live at v1 under the poisoned id, so the cascade runs far enough to reach the
         * lines under test.
         */
        @BeforeEach
        void workflowIsLiveUnderThePoisonedId() throws Exception {
            when(WorkflowStore.getCurrentResourceId(POISONED_WORKFLOW_ID)).thenReturn(resourceId(POISONED_WORKFLOW_ID, 1));
        }

        @Test
        @DisplayName("a CR/LF workflow id cannot forge a record through the not-found-for-cascade WARN")
        void workflowNotFoundForCascade() throws Exception {
            when(WorkflowStore.read(POISONED_WORKFLOW_ID, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            List<String> captured = captureLogsOf(RestWorkflowStore.class,
                    () -> assertDoesNotThrow(() -> restWorkflowStore.deleteWorkflow(POISONED_WORKFLOW_ID, 1, false, true)));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("not found for cascade")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "RestWorkflowStore.planCascade's workflow-not-found WARN");
        }

        @Test
        @DisplayName("a CR/LF store error message cannot forge a record through the read-failed WARN")
        void workflowReadFailsForCascade() throws Exception {
            when(WorkflowStore.read(POISONED_WORKFLOW_ID, 1))
                    .thenThrow(new IResourceStore.ResourceStoreException("index unavailable" + FORGED_RECORD));

            List<String> captured = captureLogsOf(RestWorkflowStore.class,
                    () -> assertDoesNotThrow(() -> restWorkflowStore.deleteWorkflow(POISONED_WORKFLOW_ID, 1, false, true)));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("Error reading workflow")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "RestWorkflowStore.planCascade's read-failed WARN");
        }
    }
}
