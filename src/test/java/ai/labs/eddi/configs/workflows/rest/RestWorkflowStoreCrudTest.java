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
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional unit tests for {@link RestWorkflowStore} — CRUD operations,
 * schema, descriptors, resource update, and duplicate flows. Existing tests in
 * {@link RestWorkflowStoreTest} cover cascade-delete scenarios.
 */
class RestWorkflowStoreCrudTest {

    private static final String WORKFLOW_ID = "aabbccddee1122334455";

    private IWorkflowStore workflowStore;
    private ResourceClientLibrary resourceClientLibrary;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private RestWorkflowStore sut;

    @BeforeEach
    void setUp() {
        workflowStore = mock(IWorkflowStore.class);
        resourceClientLibrary = mock(ResourceClientLibrary.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        sut = new RestWorkflowStore(workflowStore, resourceClientLibrary, documentDescriptorStore, jsonSchemaCreator);
    }

    private IResourceStore.IResourceId dummyResourceId(String id, int version) {
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

    // ─── readJsonSchema ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {

        @Test
        @DisplayName("should return JSON schema successfully")
        void success() throws Exception {
            when(jsonSchemaCreator.generateSchema(WorkflowConfiguration.class)).thenReturn("{}");

            Response response = sut.readJsonSchema();

            assertEquals(200, response.getStatus());
            assertEquals("{}", response.getEntity());
        }

        @Test
        @DisplayName("should propagate exception when schema creation fails")
        void schemaFails() throws Exception {
            when(jsonSchemaCreator.generateSchema(WorkflowConfiguration.class))
                    .thenThrow(new RuntimeException("schema error"));

            assertThrows(RuntimeException.class, () -> sut.readJsonSchema());
        }
    }

    // ─── readWorkflowDescriptors ───────────────────────────────────────────────

    @Nested
    @DisplayName("readWorkflowDescriptors")
    class ReadDescriptors {

        @Test
        @DisplayName("should delegate to document descriptor store")
        void standard() throws Exception {
            when(documentDescriptorStore.readDescriptors("ai.labs.workflow", "filter", 0, 20, false))
                    .thenReturn(List.of(new DocumentDescriptor()));

            List<DocumentDescriptor> result = sut.readWorkflowDescriptors("filter", 0, 20);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("should query workflows containing a resource URI")
        void containingResource() throws Exception {
            String containingUri = "eddi://ai.labs.rules/rulestore/rulesets/abc123456789012345?version=1";
            when(workflowStore.getWorkflowDescriptorsContainingResource(containingUri, false))
                    .thenReturn(List.of(new DocumentDescriptor()));

            List<DocumentDescriptor> result = sut.readWorkflowDescriptors("", 0, 20, containingUri, false);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("should reject malformed resource URI")
        void malformedUri() {
            // Malformed URI — createMalFormattedResourceUriException throws
            // BadRequestException
            assertThrows(jakarta.ws.rs.BadRequestException.class, () -> sut.readWorkflowDescriptors("", 0, 20, "not-a-valid-uri", false));
        }
    }

    // ─── readWorkflow ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readWorkflow")
    class ReadWorkflow {

        @Test
        @DisplayName("should return workflow configuration")
        void success() throws Exception {
            var config = new WorkflowConfiguration();
            when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(config);

            WorkflowConfiguration result = sut.readWorkflow(WORKFLOW_ID, 1);

            assertNotNull(result);
        }
    }

    // ─── createWorkflow ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createWorkflow")
    class CreateWorkflow {

        @Test
        @DisplayName("should create and return 201")
        void success() throws Exception {
            var config = new WorkflowConfiguration();
            when(workflowStore.create(any())).thenReturn(dummyResourceId(WORKFLOW_ID, 1));

            Response response = sut.createWorkflow(config);

            assertEquals(201, response.getStatus());
        }
    }

    // ─── updateWorkflow ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateWorkflow")
    class UpdateWorkflow {

        @Test
        @DisplayName("should update and return OK")
        void success() throws Exception {
            var config = new WorkflowConfiguration();
            when(workflowStore.update(eq(WORKFLOW_ID), eq(1), any())).thenReturn(2);

            Response response = sut.updateWorkflow(WORKFLOW_ID, 1, config);

            assertEquals(200, response.getStatus());
        }
    }

    // ─── updateResourceInWorkflow ──────────────────────────────────────────────

    @Nested
    @DisplayName("updateResourceInWorkflow")
    class UpdateResourceInWorkflow {

        @Test
        @DisplayName("should update resource URI in workflow step config")
        void updatesStepConfigUri() throws Exception {
            var config = new WorkflowConfiguration();
            var step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.rules"));
            step.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/111111111111111111111111?version=1")));
            config.getWorkflowSteps().add(step);

            when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(config);
            when(workflowStore.update(eq(WORKFLOW_ID), eq(1), any())).thenReturn(2);

            URI newResourceUri = URI.create("eddi://ai.labs.rules/rulestore/rulesets/111111111111111111111111?version=2");
            Response response = sut.updateResourceInWorkflow(WORKFLOW_ID, 1, newResourceUri);

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should update resource URI in extension element config")
        void updatesExtensionConfigUri() throws Exception {
            var config = new WorkflowConfiguration();
            var step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.parser"));

            Map<String, Object> extElement = new HashMap<>();
            extElement.put("config", new HashMap<>(Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/222222222222222222222222?version=1")));
            List<Map<String, Object>> extensions = new ArrayList<>();
            extensions.add(extElement);
            step.getExtensions().put("dictionaries", extensions);
            config.getWorkflowSteps().add(step);

            when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(config);
            when(workflowStore.update(eq(WORKFLOW_ID), eq(1), any())).thenReturn(2);

            URI newUri = URI.create("eddi://ai.labs.dictionary/dictionarystore/222222222222222222222222?version=2");
            Response response = sut.updateResourceInWorkflow(WORKFLOW_ID, 1, newUri);

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should return 400 when no matching resource URI found")
        void noMatch() throws Exception {
            var config = new WorkflowConfiguration();
            var step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.rules"));
            step.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/333333333333333333333333?version=1")));
            config.getWorkflowSteps().add(step);

            when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(config);

            URI newUri = URI.create("eddi://ai.labs.rules/rulestore/rulesets/111111111111111111111111?version=2");
            Response response = sut.updateResourceInWorkflow(WORKFLOW_ID, 1, newUri);

            assertEquals(400, response.getStatus());
        }
    }

    // ─── getResourceURI / getCurrentResourceId ─────────────────────────────────

    @Nested
    @DisplayName("Utility methods")
    class UtilityMethods {

        @Test
        @DisplayName("getResourceURI should return the resource URI")
        void getResourceUri() {
            String uri = sut.getResourceURI();

            assertNotNull(uri);
            assertTrue(uri.contains("workflow"));
        }

        @Test
        @DisplayName("getCurrentResourceId should delegate to workflowStore")
        void getCurrentResourceId() throws Exception {
            when(workflowStore.getCurrentResourceId(WORKFLOW_ID)).thenReturn(dummyResourceId(WORKFLOW_ID, 3));

            IResourceStore.IResourceId result = sut.getCurrentResourceId(WORKFLOW_ID);

            assertEquals(WORKFLOW_ID, result.getId());
            assertEquals(3, result.getVersion());
        }
    }

    // ─── deleteWorkflow cascade with ResourceStoreException ────────────────────

    @Nested
    @DisplayName("deleteWorkflow cascade error handling")
    class DeleteCascadeErrorHandling {

        @Test
        @DisplayName("should log warning and still delete when ResourceStoreException occurs during cascade")
        void cascadeWithResourceStoreException() throws Exception {
            when(workflowStore.read("aabbccddeeff112233445566", 1))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB failure"));

            // Should not throw — logs warning and proceeds to delete
            assertDoesNotThrow(() -> sut.deleteWorkflow("aabbccddeeff112233445566", 1, true, true));
        }

        @Test
        @DisplayName("should log warning and still delete when ResourceNotFoundException occurs during cascade")
        void cascadeWithResourceNotFoundException() throws Exception {
            when(workflowStore.read("aabbccddeeff112233445566", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertDoesNotThrow(() -> sut.deleteWorkflow("aabbccddeeff112233445566", 1, true, true));
            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }
    }

    // ─── duplicateWorkflow ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("duplicateWorkflow")
    class DuplicateWorkflow {

        @Test
        @DisplayName("should duplicate workflow without deep copy (deepCopy=false)")
        void duplicateShallowCopy() throws Exception {
            var config = new WorkflowConfiguration();
            var step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.rules"));
            step.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1")));
            config.getWorkflowSteps().add(step);

            when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(config);
            when(workflowStore.create(any())).thenReturn(dummyResourceId("newwf112233445566aabb", 1));

            // Stub descriptor read for createDocumentDescriptorForDuplicate
            when(documentDescriptorStore.readDescriptor(eq(WORKFLOW_ID), eq(1)))
                    .thenReturn(new DocumentDescriptor());

            Response response = sut.duplicateWorkflow(WORKFLOW_ID, 1, false);

            assertEquals(201, response.getStatus());
            // With deepCopy=false, should NOT duplicate sub-resources
            verify(resourceClientLibrary, never()).duplicateResource(any());
        }

        @Test
        @DisplayName("should duplicate workflow with deep copy including parser dictionaries")
        void duplicateDeepCopyWithParserDictionaries() throws Exception {
            var config = new WorkflowConfiguration();

            // Parser step with dictionary
            var parserStep = new WorkflowStep();
            parserStep.setType(URI.create("eddi://ai.labs.parser"));

            Map<String, Object> dictEntry = new HashMap<>();
            dictEntry.put("type", "eddi://ai.labs.parser.dictionaries.regular");
            dictEntry.put("config",
                    new HashMap<>(Map.of("uri", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/222222222222222222222222?version=1")));
            List<Map<String, Object>> dictionaries = new ArrayList<>();
            dictionaries.add(dictEntry);
            parserStep.getExtensions().put("dictionaries", dictionaries);
            config.getWorkflowSteps().add(parserStep);

            // Rules step
            var rulesStep = new WorkflowStep();
            rulesStep.setType(URI.create("eddi://ai.labs.rules"));
            rulesStep.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/333333333333333333333333?version=1")));
            config.getWorkflowSteps().add(rulesStep);

            when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(config);
            when(workflowStore.create(any())).thenReturn(dummyResourceId("newwf112233445566aabb", 1));

            // Stub descriptor reads for createDocumentDescriptorForDuplicate
            when(documentDescriptorStore.readDescriptor(anyString(), any()))
                    .thenReturn(new DocumentDescriptor());

            // Mock duplicate responses with valid Location headers
            Response dictDupResponse = Response.created(
                    URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/444444444444444444444444?version=1"))
                    .build();
            Response ruleDupResponse = Response.created(
                    URI.create("eddi://ai.labs.rules/rulestore/rulesets/555555555555555555555555?version=1"))
                    .build();

            when(resourceClientLibrary.duplicateResource(
                    URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/222222222222222222222222?version=1")))
                    .thenReturn(dictDupResponse);
            when(resourceClientLibrary.duplicateResource(
                    URI.create("eddi://ai.labs.rules/rulestore/rulesets/333333333333333333333333?version=1")))
                    .thenReturn(ruleDupResponse);

            Response response = sut.duplicateWorkflow(WORKFLOW_ID, 1, true);

            assertEquals(201, response.getStatus());
            // With deepCopy=true, should duplicate both resources
            verify(resourceClientLibrary).duplicateResource(
                    URI.create("eddi://ai.labs.dictionary/dictionarystore/dictionaries/222222222222222222222222?version=1"));
            verify(resourceClientLibrary).duplicateResource(
                    URI.create("eddi://ai.labs.rules/rulestore/rulesets/333333333333333333333333?version=1"));
        }

        @Test
        @DisplayName("should throw ServiceException when duplicateResource returns null location header")
        void duplicateResourceNullLocation() throws Exception {
            var config = new WorkflowConfiguration();
            var step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.rules"));
            step.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/333333333333333333333333?version=1")));
            config.getWorkflowSteps().add(step);

            when(workflowStore.read(WORKFLOW_ID, 1)).thenReturn(config);

            // Return a response WITHOUT Location header
            Response noLocationResponse = Response.ok().build();
            when(resourceClientLibrary.duplicateResource(any())).thenReturn(noLocationResponse);

            // Stub descriptor read for createDocumentDescriptorForDuplicate
            when(documentDescriptorStore.readDescriptor(anyString(), any()))
                    .thenReturn(new DocumentDescriptor());

            // duplicateResource should throw ServiceException wrapped by sneakyThrow
            assertThrows(Exception.class, () -> sut.duplicateWorkflow(WORKFLOW_ID, 1, true));
        }
    }

    // ─── deleteResourceSafely edge cases ───────────────────────────────────────

    @Nested
    @DisplayName("deleteResourceSafely edge cases")
    class DeleteResourceSafelyTests {

        @Test
        @DisplayName("should skip deletion when resource is referenced by multiple workflows")
        void skipsDeletionForMultiReferencedResource() throws Exception {
            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep ext = new WorkflowStep();
            ext.setType(URI.create("eddi://ai.labs.rules"));
            ext.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/111111111111111111111111?version=1")));
            config.getWorkflowSteps().add(ext);

            when(workflowStore.read("aabbccddeeff112233445566", 1)).thenReturn(config);

            // Resource referenced by 3 workflows — should skip
            when(workflowStore.getWorkflowDescriptorsContainingResource(
                    eq("eddi://ai.labs.rules/rulestore/rulesets/111111111111111111111111?version=1"), eq(false)))
                    .thenReturn(List.of(new DocumentDescriptor(), new DocumentDescriptor(), new DocumentDescriptor()));

            sut.deleteWorkflow("aabbccddeeff112233445566", 1, true, true);

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("should log warning when deletion throws and continue")
        void logsWarningWhenDeletionThrows() throws Exception {
            WorkflowConfiguration config = new WorkflowConfiguration();
            WorkflowStep ext = new WorkflowStep();
            ext.setType(URI.create("eddi://ai.labs.output"));
            ext.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/222222222222222222222222?version=1")));
            config.getWorkflowSteps().add(ext);

            when(workflowStore.read("aabbccddeeff112233445566", 1)).thenReturn(config);
            when(workflowStore.getWorkflowDescriptorsContainingResource(anyString(), eq(false)))
                    .thenReturn(List.of(new DocumentDescriptor())); // single reference

            when(resourceClientLibrary.deleteResource(any(), anyBoolean()))
                    .thenThrow(new RuntimeException("Network error"));

            // Should not propagate — logs warning and continues
            assertDoesNotThrow(() -> sut.deleteWorkflow("aabbccddeeff112233445566", 1, true, true));
        }
    }
}
