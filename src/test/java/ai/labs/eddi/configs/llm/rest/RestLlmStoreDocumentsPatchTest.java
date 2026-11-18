/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.llm.mongo.LlmStore;
import ai.labs.eddi.configs.patch.ConfigJsonPatcher;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A JSON patch reaches the same store validation as a PUT: a patch that adds a
 * {@code documents} key not available in this version is refused before
 * anything is written. Runs the real {@link LlmStore} (spied only for the read
 * of the current version), not a mocked store.
 */
@DisplayName("RestLlmStore PATCH — documents")
class RestLlmStoreDocumentsPatchTest {

    private static final String ID = "5f1c0f3a9d8e4b2a7c6d1e0f";

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a patch adding a key that is not available is refused and nothing is stored")
    void patchRefuses() throws Exception {
        IResourceStorage<LlmConfiguration> storage = mock(IResourceStorage.class);
        IResourceStorageFactory factory = mock(IResourceStorageFactory.class);
        when(factory.<LlmConfiguration>create(anyString(), any(), any(Class.class))).thenReturn(storage);
        var mapper = new ObjectMapper();

        LlmStore store = spy(new LlmStore(factory, mock(IDocumentBuilder.class)));
        var current = mock(IResourceStore.IResourceId.class);
        when(current.getId()).thenReturn(ID);
        when(current.getVersion()).thenReturn(2);
        doReturn(current).when(store).getCurrentResourceId(ID);
        doReturn(mapper.readValue("{\"tasks\":[{\"actions\":[\"help\"],\"id\":\"t1\",\"type\":\"openai\"}]}", LlmConfiguration.class))
                .when(store).read(ID, 2);

        var rest = new RestLlmStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class));
        rest.jsonPatcher = new ConfigJsonPatcher(mapper, new StrictConfigurationParser(mapper));
        String patch = "[{\"op\":\"add\",\"path\":\"/tasks/0/documents\",\"value\":{\"sharing\":{\"delegation\":true}}}]";

        try {
            var response = rest.patchLlm(ID, 2, patch);
            assertTrue(response.getStatus() >= 400, "status " + response.getStatus());
        } catch (RuntimeException e) {
            boolean named = false;
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                named |= cause.getMessage() != null && cause.getMessage().contains("documents.sharing.delegation is not available");
            }
            assertTrue(named, "refused for another reason: " + e);
        }
        verifyNoInteractions(storage);
    }
}
