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
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.configs.rest.RedactionMarkerAssertions.assertRefusedNaming;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An editor who opened an LLM configuration (credentials redacted) and saved it
 * would replace the stored key with the marker; every write path refuses it
 * instead, for administrators too.
 */
class RestLlmStoreRedactionMarkerWriteTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private ILlmStore llmStore;
    private ResourceAccessGuard accessGuard;
    private RestLlmStore rest;

    @BeforeEach
    void setUp() throws Exception {
        llmStore = mock(ILlmStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestLlmStore(llmStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), accessGuard);
        var mapper = new ObjectMapper();
        rest.jsonPatcher = new ConfigJsonPatcher(mapper, new StrictConfigurationParser(mapper));
        var current = mock(IResourceStore.IResourceId.class);
        when(current.getId()).thenReturn(ID);
        when(current.getVersion()).thenReturn(1);
        when(llmStore.getCurrentResourceId(ID)).thenReturn(current);
        when(llmStore.update(eq(ID), eq(1), any())).thenReturn(2);
    }

    private static LlmConfiguration config(String apiKey) {
        var task = new LlmConfiguration.Task();
        task.setType("openai");
        task.setParameters(new LinkedHashMap<>(Map.of("apiKey", apiKey, "modelName", "gpt-5")));
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("an editor's read-then-save round trip is refused, naming the field, and nothing is written")
    void editorRoundTripIsRefused() throws Exception {
        when(llmStore.read(ID, 1)).thenReturn(config("sk-admin-plaintext"));
        when(accessGuard.isAdmin()).thenReturn(false);
        LlmConfiguration seen = rest.readLlm(ID, 1);

        assertRefusedNaming("tasks[0].parameters.apiKey", () -> rest.updateLlm(ID, 1, seen));
        verify(llmStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an administrator is refused the marker too, on create")
    void adminCreateIsRefused() throws Exception {
        when(accessGuard.isAdmin()).thenReturn(true);

        assertRefusedNaming("tasks[0].parameters.apiKey", () -> rest.createLlm(config(ConfigSecretRedactor.REDACTED)));
        verify(llmStore, never()).create(any());
    }

    @Test
    @DisplayName("a patch that writes the marker is refused")
    void patchIsRefused() throws Exception {
        when(llmStore.read(ID, 1)).thenReturn(config("${vault:k}"));
        String patch = "[{\"op\":\"replace\",\"path\":\"/tasks/0/parameters/apiKey\",\"value\":\"" + ConfigSecretRedactor.IN_STRING_REDACTED + "\"}]";

        assertRefusedNaming("tasks[0].parameters.apiKey", () -> rest.patchLlm(ID, 1, patch));
        verify(llmStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a stored document that already carries the marker is not duplicated")
    void duplicateIsRefused() throws Exception {
        when(llmStore.read(ID, 1)).thenReturn(config(ConfigSecretRedactor.REDACTED));

        assertRefusedNaming("tasks[0].parameters.apiKey", () -> rest.duplicateLlm(ID, 1));
        verify(llmStore, never()).create(any());
    }

    @Test
    @DisplayName("a document with a reference or a re-entered literal is written")
    void cleanWritePasses() throws Exception {
        assertEquals(200, rest.updateLlm(ID, 1, config("${vault:openai}")).getStatus());
        assertEquals(200, rest.updateLlm(ID, 1, config("sk-re-entered")).getStatus());
    }
}
