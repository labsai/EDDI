/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.ResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
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
    void setUp() {
        openMocks(this);
        restWorkflowStore = new RestWorkflowStore(WorkflowStore, resourceClientLibrary, documentDescriptorStore, jsonSchemaCreator);
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

            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1"), true);
            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.apicalls/apicallstore/apicalls/http1?version=3"), true);
            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=1"), true);
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

            verify(resourceClientLibrary).deleteResource(URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/dict1?version=1"), true);
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
            verify(resourceClientLibrary).deleteResource(URI.create(uniqueUri), true);
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
            when(resourceClientLibrary.deleteResource(URI.create("eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1"), true))
                    .thenThrow(new ServiceException("DB error"));
            when(resourceClientLibrary.deleteResource(URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=1"), true))
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
}
