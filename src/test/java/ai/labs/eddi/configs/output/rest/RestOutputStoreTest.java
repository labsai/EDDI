/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.output.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestOutputStore}.
 */
class RestOutputStoreTest {

    private IOutputStore outputStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private RestOutputStore restStore;

    @BeforeEach
    void setUp() {
        outputStore = mock(IOutputStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        restStore = new RestOutputStore(outputStore, documentDescriptorStore, jsonSchemaCreator);
    }

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {

        @Test
        @DisplayName("should return 200 with schema")
        void returnsSchema() throws Exception {
            when(jsonSchemaCreator.generateSchema(OutputConfigurationSet.class)).thenReturn("{\"type\":\"object\"}");

            Response response = restStore.readJsonSchema();

            assertEquals(200, response.getStatus());
        }
    }

    @Nested
    @DisplayName("readOutputSet")
    class ReadOutputSet {

        @Test
        @DisplayName("should delegate to output store")
        void delegatesToStore() throws Exception {
            var config = new OutputConfigurationSet();
            when(outputStore.read("out-1", 1, "", "", 0, 10)).thenReturn(config);

            OutputConfigurationSet result = restStore.readOutputSet("out-1", 1, "", "", 0, 10);

            assertNotNull(result);
        }

        @Test
        @DisplayName("should throw on ResourceNotFoundException")
        void throwsOnNotFound() throws Exception {
            when(outputStore.read("missing", 1, "", "", 0, 10))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> restStore.readOutputSet("missing", 1, "", "", 0, 10));
        }
    }

    @Nested
    @DisplayName("readOutputKeys")
    class ReadOutputKeys {

        @Test
        @DisplayName("should return action keys")
        void returnsKeys() throws Exception {
            when(outputStore.readActions("out-1", 1, "greet", 10))
                    .thenReturn(List.of("greet_user", "greeting_default"));

            List<String> result = restStore.readOutputKeys("out-1", 1, "greet", 10);

            assertEquals(2, result.size());
            assertTrue(result.contains("greet_user"));
        }
    }

    @Nested
    @DisplayName("createOutputSet")
    class CreateOutputSet {

        @Test
        @DisplayName("should delegate to restVersionInfo")
        void delegatesToVersionInfo() throws Exception {
            var config = new OutputConfigurationSet();
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("new-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(outputStore.create(any())).thenReturn(resourceId);

            Response response = restStore.createOutputSet(config);

            assertEquals(201, response.getStatus());
        }
    }

    @Nested
    @DisplayName("deleteOutputSet")
    class DeleteOutputSet {

        @Test
        @DisplayName("should delegate to restVersionInfo")
        void delegatesToVersionInfo() throws Exception {
            restStore.deleteOutputSet("out-1", 1, false);

            verify(outputStore).delete("out-1", 1);
        }
    }

    @Nested
    @DisplayName("duplicateOutputSet")
    class DuplicateOutputSet {

        @Test
        @DisplayName("should read then create")
        void readsAndRecreates() throws Exception {
            var config = new OutputConfigurationSet();
            when(outputStore.read("out-1", 1)).thenReturn(config);
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getId()).thenReturn("dup-id");
            when(resourceId.getVersion()).thenReturn(1);
            when(outputStore.create(any())).thenReturn(resourceId);

            Response response = restStore.duplicateOutputSet("out-1", 1);

            assertEquals(201, response.getStatus());
            verify(outputStore).read("out-1", 1);
        }
    }

    @Nested
    @DisplayName("patchOutputSet")
    class PatchOutputSet {

        @Test
        @DisplayName("should apply SET patch")
        void appliesSetPatch() throws Exception {
            var current = new OutputConfigurationSet();
            current.setOutputSet(new ArrayList<>());
            when(outputStore.read("out-1", 1)).thenReturn(current);

            var patch = new OutputConfigurationSet();
            patch.setOutputSet(new ArrayList<>());

            var instruction = new PatchInstruction<OutputConfigurationSet>();
            instruction.setOperation(PatchInstruction.PatchOperation.SET);
            instruction.setDocument(patch);

            restStore.patchOutputSet("out-1", 1, List.of(instruction));

            verify(outputStore).read("out-1", 1);
        }

        @Test
        @DisplayName("should apply DELETE patch")
        void appliesDeletePatch() throws Exception {
            var current = new OutputConfigurationSet();
            current.setOutputSet(new ArrayList<>());
            when(outputStore.read("out-1", 1)).thenReturn(current);

            var patch = new OutputConfigurationSet();
            patch.setOutputSet(new ArrayList<>());

            var instruction = new PatchInstruction<OutputConfigurationSet>();
            instruction.setOperation(PatchInstruction.PatchOperation.DELETE);
            instruction.setDocument(patch);

            restStore.patchOutputSet("out-1", 1, List.of(instruction));

            verify(outputStore).read("out-1", 1);
        }
    }

    @Nested
    @DisplayName("getResourceURI")
    class GetResourceURI {

        @Test
        @DisplayName("should return non-null URI")
        void returnsUri() {
            assertNotNull(restStore.getResourceURI());
        }
    }

    @Nested
    @DisplayName("getCurrentResourceId")
    class GetCurrentResourceId {

        @Test
        @DisplayName("should delegate to output store")
        void delegatesToStore() throws Exception {
            var resourceId = mock(IResourceStore.IResourceId.class);
            when(resourceId.getVersion()).thenReturn(2);
            when(outputStore.getCurrentResourceId("out-1")).thenReturn(resourceId);

            var result = restStore.getCurrentResourceId("out-1");

            assertEquals(2, result.getVersion());
        }
    }
}
