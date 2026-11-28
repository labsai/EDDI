/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.patch.ConfigJsonPatcher;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.impl.VaultReferencePlacementGuard;
import ai.labs.eddi.modules.llm.impl.builder.AnthropicLanguageModelBuilder;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.llm.impl.builder.OpenAILanguageModelBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Provider;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The save-time half of finding 1 of the 2026-11 review: an LLM configuration
 * that puts a {@code ${vault:…}} into a parameter that is not a credential is
 * refused with a 400 naming the field, on create, update, patch and duplicate.
 */
@DisplayName("RestLlmStore — vault references only in credential parameters")
class RestLlmStoreVaultPlacementTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private ILlmStore llmStore;
    private RestLlmStore rest;

    @BeforeEach
    void setUp() throws Exception {
        llmStore = mock(ILlmStore.class);
        rest = new RestLlmStore(llmStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class));
        var mapper = new ObjectMapper();
        rest.jsonPatcher = new ConfigJsonPatcher(mapper, new StrictConfigurationParser(mapper));
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", OpenAILanguageModelBuilder::new);
        builders.put("anthropic", AnthropicLanguageModelBuilder::new);
        rest.vaultReferencePlacementGuard = new VaultReferencePlacementGuard(builders);
        var current = mock(IResourceStore.IResourceId.class);
        when(current.getId()).thenReturn(ID);
        when(current.getVersion()).thenReturn(1);
        when(llmStore.getCurrentResourceId(ID)).thenReturn(current);
        when(llmStore.update(eq(ID), eq(1), any())).thenReturn(2);
    }

    private static LlmConfiguration config(String type, Map<String, String> parameters) {
        var task = new LlmConfiguration.Task();
        task.setType(type);
        task.setParameters(new LinkedHashMap<>(parameters));
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("the exploit: modelName holding a reference is refused on create and update, naming the field")
    void modelNameReferenceIsRefused() throws Exception {
        var exploit = config("openai", Map.of("apiKey", "own-key", "modelName", "${vault:any-secret}"));

        var created = assertThrows(BadRequestException.class, () -> rest.createLlm(exploit));
        assertTrue(created.getMessage().contains("tasks[0].parameters"), created.getMessage());
        assertTrue(created.getMessage().contains("'modelName'"), created.getMessage());
        assertFalse(created.getMessage().contains("any-secret"));
        assertThrows(BadRequestException.class, () -> rest.updateLlm(ID, 1, exploit));
        verify(llmStore, never()).create(any());
        verify(llmStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a patch that introduces one is refused")
    void patchIsRefused() throws Exception {
        when(llmStore.read(ID, 1)).thenReturn(config("openai", Map.of("apiKey", "${vault:k}", "modelName", "gpt-5")));
        String patch = "[{\"op\":\"replace\",\"path\":\"/tasks/0/parameters/modelName\",\"value\":\"${vault:any-secret}\"}]";

        assertThrows(BadRequestException.class, () -> rest.patchLlm(ID, 1, patch));
        verify(llmStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a stored document carrying one is not duplicated")
    void duplicateIsRefused() throws Exception {
        when(llmStore.read(ID, 1)).thenReturn(config("anthropic", Map.of("apiKey", "k", "baseUrl", "${vault:any-secret}")));

        assertThrows(BadRequestException.class, () -> rest.duplicateLlm(ID, 1));
        verify(llmStore, never()).create(any());
    }

    @Test
    @DisplayName("cascade steps and the judge model are checked against their own type")
    void cascadeIsChecked() {
        var cascade = new LlmConfiguration.ModelCascadeConfig();
        var step = new LlmConfiguration.CascadeStep();
        step.setType("anthropic");
        step.setParameters(Map.of("apiKey", "${vault:anthropic-key}", "modelName", "${vault:any-secret}"));
        cascade.setSteps(List.of(step));
        var task = new LlmConfiguration.Task();
        task.setType("openai");
        task.setParameters(Map.of("apiKey", "${vault:openai-key}"));
        task.setModelCascade(cascade);

        var refusal = assertThrows(BadRequestException.class, () -> rest.createLlm(new LlmConfiguration(List.of(task))));
        assertTrue(refusal.getMessage().contains("tasks[0].modelCascade.steps[0].parameters"), refusal.getMessage());
    }

    @Test
    @DisplayName("references in credential keys, and reference-shaped text in a prompt, are written")
    void legitimateDocumentsPass() throws Exception {
        assertEquals(200, rest.updateLlm(ID, 1, config("openai",
                Map.of("apiKey", "${vault:openai}", "customHeaders", "{\"X\":\"${vault:h}\"}", "systemMessage", "Say ${vault:x} literally")))
                .getStatus());
    }

    @Test
    @DisplayName("a templated type is judged against every provider's credential keys")
    void unresolvedTypeUsesTheUnion() throws Exception {
        assertEquals(200, rest.updateLlm(ID, 1, config("${vars:llm-type}", Map.of("apiKey", "${vault:k}"))).getStatus());
        assertThrows(BadRequestException.class,
                () -> rest.updateLlm(ID, 1, config("${vars:llm-type}", Map.of("apiKey", "k", "modelName", "${vault:any-secret}"))));
    }
}
