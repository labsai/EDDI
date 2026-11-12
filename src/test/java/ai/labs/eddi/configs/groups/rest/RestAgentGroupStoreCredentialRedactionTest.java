/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LessonConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LessonConsolidationConfig;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A group stored before consolidation parameters were checked at save can hold
 * a literal key there, and {@code GET /groupstore/groups/{id}} returned it to
 * every caller with VIEW.
 */
@DisplayName("RestAgentGroupStore — plaintext credentials are hidden from editors")
class RestAgentGroupStoreCredentialRedactionTest {

    private static final String ID = "group-1";
    private static final String LITERAL = "sk-consolidation-literal-0123456789";

    private IAgentGroupStore groupStore;
    private ResourceAccessGuard accessGuard;
    private RestAgentGroupStore rest;

    @BeforeEach
    void setUp() {
        groupStore = mock(IAgentGroupStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestAgentGroupStore(groupStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(IGroupWorkspaceStore.class), mock(IScheduleStore.class), accessGuard);
    }

    private static AgentGroupConfiguration stored() {
        var config = new AgentGroupConfiguration();
        config.setName("team");
        config.setLessonConfig(new LessonConfig(null, null, null, new LessonConsolidationConfig("openai", "gpt-5-mini",
                Map.of("apiKey", LITERAL, "baseUrl", "https://llm.internal.example/v1"), null, null, null, null, null, null, null)));
        return config;
    }

    private AgentGroupConfiguration read(boolean admin) throws Exception {
        when(groupStore.read(ID, 1)).thenReturn(stored());
        when(accessGuard.isAdmin()).thenReturn(admin);
        return rest.readGroup(ID, 1);
    }

    @Test
    @DisplayName("an editor reads lessonConfig.consolidation.parameters.apiKey redacted")
    void editorReadsRedacted() throws Exception {
        var parameters = read(false).getLessonConfig().consolidation().parameters();
        assertEquals(ConfigSecretRedactor.REDACTED, parameters.get("apiKey"));
        assertEquals("https://llm.internal.example/v1", parameters.get("baseUrl"));
    }

    @Test
    @DisplayName("accessKey and subscriptionKey in consolidation parameters are redacted for an editor, as the save rule treats them")
    void keyNamedParametersAreRedacted() throws Exception {
        var config = new AgentGroupConfiguration();
        config.setName("team");
        config.setLessonConfig(new LessonConfig(null, null, null, new LessonConsolidationConfig("bedrock", "m",
                Map.of("accessKey", "plain-access-literal", "subscriptionKey", "plainsub", "promptCacheKey", "${vault:k}"), null, null, null,
                null, null, null, null)));
        when(groupStore.read(ID, 1)).thenReturn(config);
        when(accessGuard.isAdmin()).thenReturn(false);

        var parameters = rest.readGroup(ID, 1).getLessonConfig().consolidation().parameters();
        assertEquals(ConfigSecretRedactor.REDACTED, parameters.get("accessKey"));
        assertEquals(ConfigSecretRedactor.REDACTED, parameters.get("subscriptionKey"));
        assertEquals("${vault:k}", parameters.get("promptCacheKey"));
    }

    @Test
    @DisplayName("an administrator reads the group as stored")
    void adminReadsStored() throws Exception {
        assertEquals(LITERAL, read(true).getLessonConfig().consolidation().parameters().get("apiKey"));
    }

    @Test
    @DisplayName("a group read through the redacted view is refused on update and create")
    void markerIsNeverWritten() throws Exception {
        AgentGroupConfiguration redacted = read(false);

        assertThrows(BadRequestException.class, () -> rest.updateGroup(ID, 1, redacted));
        assertThrows(BadRequestException.class, () -> rest.createGroup(redacted));
        verify(groupStore, never()).update(any(), any(), any());
        verify(groupStore, never()).create(any());
    }
}
