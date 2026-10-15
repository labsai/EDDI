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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code PATCH /llmstore/llms/{id}} — the credential-safe edit.
 */
@DisplayName("RestLlmStore PATCH")
class RestLlmStorePatchTest {

    private static final String ID = "5f1c0f3a9d8e4b2a7c6d1e0f";
    private static final String STORED = """
            {"tasks":[{"actions":["help"],"type":"anthropic",
             "parameters":{"apiKey":"${vault:anthropic-api-key}","modelName":"claude-a"}}]}
            """;
    private static final String EDIT = "[{\"op\":\"replace\",\"path\":\"/tasks/0/parameters/modelName\",\"value\":\"claude-b\"}]";

    private ILlmStore llmStore;
    private RestLlmStore restStore;

    @BeforeEach
    void setUp() throws Exception {
        var mapper = new ObjectMapper();
        llmStore = mock(ILlmStore.class);
        restStore = new RestLlmStore(llmStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class));
        restStore.jsonPatcher = new ConfigJsonPatcher(mapper, new StrictConfigurationParser(mapper));

        var current = mock(IResourceStore.IResourceId.class);
        when(current.getId()).thenReturn(ID);
        when(current.getVersion()).thenReturn(2);
        when(llmStore.getCurrentResourceId(ID)).thenReturn(current);
        when(llmStore.read(ID, 2)).thenReturn(mapper.readValue(STORED, LlmConfiguration.class));
        when(llmStore.update(eq(ID), eq(2), any())).thenReturn(3);
    }

    @Test
    @DisplayName("applies to the stored version, writes a new one, returns it in Location — and the stored reference survives")
    void appliesAndStoresANewVersion() throws Exception {
        var response = restStore.patchLlm(ID, 2, EDIT);

        assertEquals(200, response.getStatus());
        assertTrue(response.getLocation().toString().endsWith(ID + "?version=3"), response.getLocation().toString());

        var written = ArgumentCaptor.forClass(LlmConfiguration.class);
        verify(llmStore).update(eq(ID), eq(2), written.capture());
        var parameters = written.getValue().tasks().get(0).getParameters();
        assertEquals("claude-b", parameters.get("modelName"));
        assertEquals("${vault:anthropic-api-key}", parameters.get("apiKey"), "the reference is stored as the reference, never resolved");
    }

    @Test
    @DisplayName("version 0 means the current version, like PUT")
    void versionZeroIsCurrent() throws Exception {
        assertEquals(200, restStore.patchLlm(ID, 0, EDIT).getStatus());
        verify(llmStore).update(eq(ID), eq(2), any());
    }

    @Test
    @DisplayName("a version that is not the current one is 409 and nothing is applied or written")
    void staleVersionIs409() throws Exception {
        var e = assertThrows(WebApplicationException.class, () -> restStore.patchLlm(ID, 1, EDIT));

        assertEquals(409, e.getResponse().getStatus());
        assertTrue(e.getResponse().getEntity().toString().contains("version=2"), "names the current version");
        verify(llmStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a failed test operation is 400 and nothing is written")
    void failedTestIs400() throws Exception {
        var e = assertThrows(WebApplicationException.class, () -> restStore.patchLlm(ID, 2, """
                [{"op":"test","path":"/tasks/0/parameters/modelName","value":"claude-z"},
                 {"op":"replace","path":"/tasks/0/parameters/modelName","value":"claude-b"}]
                """));

        assertEquals(400, e.getResponse().getStatus());
        verify(llmStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a path that does not resolve is 400 and nothing is written")
    void badPathIs400() throws Exception {
        var e = assertThrows(WebApplicationException.class,
                () -> restStore.patchLlm(ID, 2, "[{\"op\":\"remove\",\"path\":\"/tasks/3\"}]"));

        assertEquals(400, e.getResponse().getStatus());
        verify(llmStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a concurrent writer that wins the store write is still a 409")
    void lostRaceIs409() throws Exception {
        when(llmStore.update(eq(ID), eq(2), any())).thenThrow(new IResourceStore.ResourceModifiedException("moved to v3"));

        assertThrows(IResourceStore.ResourceModifiedException.class, () -> restStore.patchLlm(ID, 2, EDIT));
    }

    @Test
    @DisplayName("a patch that would introduce a plaintext credential is stored like a PUT of it would be: with the warning, not refused")
    void patchIsValidatedLikeAPut() {
        // Parity with updateLlm, which warns rather than rejects (setup_agent falls
        // back to plaintext on a vault-less instance). The refusal of an INTRODUCED
        // ${vault:…} reference is the operator tool's ConfigReferenceGuard, not this.
        var response = restStore.patchLlm(ID, 2, "[{\"op\":\"replace\",\"path\":\"/tasks/0/parameters/apiKey\",\"value\":\"sk-plain\"}]");
        assertEquals(200, response.getStatus());
    }
}
