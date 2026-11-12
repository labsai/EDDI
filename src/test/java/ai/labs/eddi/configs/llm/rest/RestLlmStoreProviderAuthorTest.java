/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.security.LlmProviderAuthorPolicy;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The LLM store refuses restricted providers from an editor on create, and
 * carries an admin's over on update.
 */
class RestLlmStoreProviderAuthorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ILlmStore llmStore;
    private RestLlmStore rest;

    @BeforeEach
    void setUp() throws Exception {
        llmStore = mock(ILlmStore.class);
        var accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestLlmStore(llmStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), accessGuard);
        var identity = mock(SecurityIdentity.class);
        when(identity.hasRole("eddi-editor")).thenReturn(true);
        var serialization = mock(IJsonSerialization.class);
        when(serialization.serialize(any())).thenAnswer(i -> mapper.writeValueAsString(i.getArgument(0)));
        rest.llmProviderAuthorPolicy = new LlmProviderAuthorPolicy(identity, serialization, true, List.of("bedrock"), List.of("bedrock"), false,
                Optional.empty());
    }

    private LlmConfiguration bedrock(String modelId, String prompt) throws Exception {
        return mapper.readValue("{\"tasks\":[{\"actions\":[\"help\"],\"type\":\"bedrock\",\"parameters\":{\"modelId\":\"" + modelId
                + "\",\"systemMessage\":\"" + prompt + "\"}}]}", LlmConfiguration.class);
    }

    @Test
    @DisplayName("an editor cannot create a Bedrock config")
    void createRefused() throws Exception {
        var config = bedrock("m1", "hi");

        assertThrows(ForbiddenException.class, () -> rest.createLlm(config));
        verify(llmStore, never()).create(any());
    }

    @Test
    @DisplayName("an editor cannot re-point an administrator's Bedrock config at another model")
    void repointRefused() throws Exception {
        when(llmStore.read("abc", 1)).thenReturn(bedrock("m1", "hi"));
        var changed = bedrock("m2", "hi");

        assertThrows(ForbiddenException.class, () -> rest.updateLlm("abc", 1, changed));
        verify(llmStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an update whose replaced version cannot be read gets no carry-over")
    void unreadableStoredVersionIsNoCarryOver() throws Exception {
        when(llmStore.read("abc", 1)).thenThrow(new RuntimeException("store down"));
        var unchanged = bedrock("m1", "hi");

        assertThrows(ForbiddenException.class, () -> rest.updateLlm("abc", 1, unchanged));
    }

    @Test
    @DisplayName("version 0 (the current version) still carries an unchanged administrator setting over")
    void versionZeroCarriesOver() throws Exception {
        IResourceStore.IResourceId current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(1);
        when(llmStore.getCurrentResourceId("abc")).thenReturn(current);
        when(llmStore.read("abc", 1)).thenReturn(bedrock("m1", "hi"));
        var unchanged = bedrock("m1", "edited prompt");

        try {
            rest.updateLlm("abc", 0, unchanged);
        } catch (ForbiddenException e) {
            fail("an unchanged administrator setting must carry over on version 0: " + e.getMessage());
        } catch (RuntimeException afterTheCheck) {
            // the mocked store beyond the policy check is not under test
        }
    }
}
