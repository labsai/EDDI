/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.security.CallerTokenAuthorPolicy;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The LLM store applies the same author check as httpcalls and mcpcalls. */
class RestLlmStoreCallerTokenTest {

    @Test
    @DisplayName("an editor cannot create an LLM config that contains ${caller:token}")
    void editorRefused() throws Exception {
        var mapper = new ObjectMapper();
        var llmStore = mock(ILlmStore.class);
        var rest = new RestLlmStore(llmStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class));
        var identity = mock(SecurityIdentity.class);
        when(identity.hasRole("eddi-editor")).thenReturn(true);
        var serialization = mock(IJsonSerialization.class);
        when(serialization.serialize(any())).thenAnswer(i -> mapper.writeValueAsString(i.getArgument(0)));
        rest.callerTokenAuthorPolicy = new CallerTokenAuthorPolicy(identity, serialization, true, List.of("eddi-admin"));

        var config = mapper.readValue("""
                {"tasks":[{"actions":["help"],"type":"openai","parameters":{"apiKey":"${caller:token}","modelName":"m"}}]}
                """, LlmConfiguration.class);

        assertThrows(ForbiddenException.class, () -> rest.createLlm(config));
        verify(llmStore, never()).create(any());
    }
}
