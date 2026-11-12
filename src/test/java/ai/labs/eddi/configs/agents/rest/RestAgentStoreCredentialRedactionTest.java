/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An agent holds credentials of its own — the Dream consolidation model's
 * {@code parameters} and a legacy channel connector's {@code config} — and
 * {@code GET /agentstore/agents/{id}} returned them verbatim to every caller
 * with VIEW. Editors now read them redacted, as they do LLM configs, and a
 * document carrying the marker is never written back.
 */
@DisplayName("RestAgentStore — plaintext credentials are hidden from editors")
class RestAgentStoreCredentialRedactionTest {

    private static final String ID = "aabbccddee1122334455";
    private static final String DREAM_KEY = "sk-dream-literal-0123456789abcdef";
    private static final String BOT_TOKEN = "xoxb-literal-bot-token-0123456789";
    private static final String SIGNING_SECRET = "literal-signing-secret";

    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private IAgentStore agentStore;
    private ResourceAccessGuard accessGuard;
    private IRestWorkflowStore workflowStore;
    private RestAgentStore rest;

    @BeforeEach
    void setUp() {
        agentStore = mock(IAgentStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        workflowStore = mock(IRestWorkflowStore.class);
        rest = new RestAgentStore(agentStore, workflowStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(IScheduleStore.class), mock(CapabilityRegistryService.class), mock(IDeploymentStore.class), accessGuard,
                mock(AgentSigningService.class), mock(IAgentFactory.class), "default", mock(AgentCascadeService.class));
    }

    private AgentConfiguration stored() throws Exception {
        return mapper.readValue("""
                {"workflows":["eddi://ai.labs.workflow/workflowstore/workflows/ff00112233445566aa77?version=1"],
                 "userMemoryConfig":{"dream":{"enabled":true,"llmProvider":"openai","llmModel":"gpt-5-mini",
                   "parameters":{"apiKey":"%s","baseUrl":"https://llm.internal.example/v1"}}},
                 "channels":[{"type":"eddi://ai.labs.channel.slack",
                   "config":{"botToken":"%s","signingSecret":"%s","channelId":"C0123"}}]}
                """.formatted(DREAM_KEY, BOT_TOKEN, SIGNING_SECRET), AgentConfiguration.class);
    }

    private AgentConfiguration read(boolean admin) throws Exception {
        when(agentStore.read(ID, 1)).thenReturn(stored());
        when(accessGuard.isAdmin()).thenReturn(admin);
        return rest.readAgent(ID, 1);
    }

    @Test
    @DisplayName("an editor reads dream.parameters.apiKey and the channel connector secrets redacted")
    void editorReadsRedacted() throws Exception {
        AgentConfiguration read = read(false);
        String json = mapper.writeValueAsString(read);

        assertFalse(json.contains(DREAM_KEY), json);
        assertFalse(json.contains(BOT_TOKEN), json);
        assertFalse(json.contains(SIGNING_SECRET), json);
        assertEquals(ConfigSecretRedactor.REDACTED, read.getUserMemoryConfig().getDream().getParameters().get("apiKey"));
        assertEquals(ConfigSecretRedactor.REDACTED, read.getChannels().get(0).getConfig().get("botToken"));
        // Everything that is not a credential is still readable.
        assertEquals("https://llm.internal.example/v1", read.getUserMemoryConfig().getDream().getParameters().get("baseUrl"));
        assertEquals("C0123", read.getChannels().get(0).getConfig().get("channelId"));
        assertEquals(1, read.getWorkflows().size());
    }

    @Test
    @DisplayName("accessKey and subscriptionKey in dream parameters are redacted for an editor; elsewhere *Key keeps its meaning")
    void keyNamedDreamParametersAreRedacted() throws Exception {
        AgentConfiguration withKeys = mapper.readValue("""
                {"workflows":[],"userMemoryConfig":{"dream":{"parameters":
                  {"accessKey":"AKIALITERALACCESSKEY","subscriptionKey":"plainsub","modelName":"m"}}},
                 "channels":[{"type":"eddi://ai.labs.channel.slack","config":{"channelKey":"C0123"}}]}
                """, AgentConfiguration.class);
        when(agentStore.read(ID, 1)).thenReturn(withKeys);
        when(accessGuard.isAdmin()).thenReturn(false);

        AgentConfiguration read = rest.readAgent(ID, 1);
        var parameters = read.getUserMemoryConfig().getDream().getParameters();
        assertEquals(ConfigSecretRedactor.REDACTED, parameters.get("accessKey"));
        assertEquals(ConfigSecretRedactor.REDACTED, parameters.get("subscriptionKey"));
        assertEquals("m", parameters.get("modelName"));
        // Not a model-parameter map: the stricter rule does not reach it.
        assertEquals("C0123", read.getChannels().get(0).getConfig().get("channelKey"));
    }

    @Test
    @DisplayName("an administrator reads the agent as stored")
    void adminReadsStored() throws Exception {
        AgentConfiguration read = read(true);
        assertEquals(DREAM_KEY, read.getUserMemoryConfig().getDream().getParameters().get("apiKey"));
        assertEquals(BOT_TOKEN, read.getChannels().get(0).getConfig().get("botToken"));
    }

    @Test
    @DisplayName("a vault reference is a pointer and is shown to editors unchanged")
    void referenceIsNotRedacted() throws Exception {
        AgentConfiguration withReference = mapper.readValue("""
                {"workflows":[],"userMemoryConfig":{"dream":{"parameters":{"apiKey":"${vault:dream-key}"}}}}
                """, AgentConfiguration.class);
        when(agentStore.read(ID, 1)).thenReturn(withReference);
        when(accessGuard.isAdmin()).thenReturn(false);

        assertSame(withReference, rest.readAgent(ID, 1));
    }

    @Test
    @DisplayName("readUnredacted — for server-side write-backs — returns the stored literal to an editor")
    void readUnredactedForEditors() throws Exception {
        when(agentStore.read(ID, 1)).thenReturn(stored());
        when(accessGuard.isAdmin()).thenReturn(false);

        assertEquals(DREAM_KEY, rest.readUnredacted(ID, 1).getUserMemoryConfig().getDream().getParameters().get("apiKey"));
        assertEquals(DREAM_KEY, RestAgentStore.readUnredacted(rest, ID, 1).getUserMemoryConfig().getDream().getParameters().get("apiKey"));

        IRestAgentStore other = mock(IRestAgentStore.class);
        AgentConfiguration fromOther = new AgentConfiguration();
        when(other.readAgent(ID, 1)).thenReturn(fromOther);
        assertSame(fromOther, RestAgentStore.readUnredacted(other, ID, 1));
    }

    @Test
    @DisplayName("an agent read through the redacted view is refused on update and create, naming the fields")
    void markerIsNeverWritten() throws Exception {
        AgentConfiguration redacted = read(false);

        var update = assertThrows(BadRequestException.class, () -> rest.updateAgent(ID, 1, redacted, false));
        assertTrue(String.valueOf(update.getResponse().getEntity()).contains("userMemoryConfig.dream.parameters.apiKey"));
        assertThrows(BadRequestException.class, () -> rest.createAgent(redacted));
        verify(agentStore, never()).update(any(), any(), any(), anyBoolean());
        verify(agentStore, never()).create(any());
    }

    @Test
    @DisplayName("re-pointing a workflow as an editor keeps the stored credentials instead of writing the marker")
    void updateResourceInAgentKeepsStoredCredentials() throws Exception {
        when(agentStore.read(ID, 1)).thenReturn(stored());
        when(accessGuard.isAdmin()).thenReturn(false);
        when(agentStore.update(eq(ID), eq(1), any(), anyBoolean())).thenReturn(2);

        rest.updateResourceInAgent(ID, 1, URI.create("eddi://ai.labs.workflow/workflowstore/workflows/ff00112233445566aa77?version=2"),
                false);

        ArgumentCaptor<AgentConfiguration> written = ArgumentCaptor.forClass(AgentConfiguration.class);
        verify(agentStore).update(eq(ID), eq(1), written.capture(), anyBoolean());
        assertEquals(DREAM_KEY, written.getValue().getUserMemoryConfig().getDream().getParameters().get("apiKey"));
        assertEquals(BOT_TOKEN, written.getValue().getChannels().get(0).getConfig().get("botToken"));
    }

    @Test
    @DisplayName("a plaintext dream key is still accepted on save (warned, not refused), so stored agents keep saving")
    void literalIsStillAccepted() throws Exception {
        when(agentStore.update(eq(ID), eq(1), any(), anyBoolean())).thenReturn(2);

        assertEquals(200, rest.updateAgent(ID, 1, stored(), false).getStatus());
    }
}
