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
        when(WorkflowStore.getWorkflowDescriptorsContainingResource(anyString(), eq(false))).thenReturn(List.of(new DocumentDescriptor()));
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
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(eq(sharedUri), eq(false)))
                    .thenReturn(List.of(new DocumentDescriptor(), new DocumentDescriptor()));

            // Unique resource referenced by only 1 package
            String uniqueUri = "eddi://ai.labs.output/outputstore/outputsets/unique1?version=1";
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(eq(uniqueUri), eq(false))).thenReturn(List.of(new DocumentDescriptor()));

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
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(eq(sharedOutputUri), eq(false)))
                    .thenReturn(List.of(new DocumentDescriptor(), new DocumentDescriptor()));

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
            when(WorkflowStore.getWorkflowDescriptorsContainingResource(anyString(), eq(false)))
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
    }
}
