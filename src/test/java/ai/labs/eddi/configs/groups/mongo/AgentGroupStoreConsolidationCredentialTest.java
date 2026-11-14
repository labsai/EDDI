/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LessonConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LessonConsolidationConfig;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * {@code lessonConfig.consolidation.parameters} is held to the rule a SERVICE
 * judge's parameters already follow: a new plaintext credential is refused at
 * save, so a group config cannot become a plaintext key store. A literal the
 * stored version already holds is carried over, so no stored group stops
 * saving.
 */
@DisplayName("AgentGroupStore — plaintext credentials in lesson consolidation parameters")
class AgentGroupStoreConsolidationCredentialTest {

    private static final String ID = "group-1";
    private static final String LITERAL = "sk-consolidation-literal-0123456789";

    private AgentGroupStore store;

    @BeforeEach
    void setUp() {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        when(storageFactory.create(eq("groups"), any(), eq(AgentGroupConfiguration.class))).thenReturn(mock(IResourceStorage.class));
        store = spy(new AgentGroupStore(storageFactory, mock(IDocumentBuilder.class)));
    }

    private static AgentGroupConfiguration withParameters(Map<String, String> parameters) {
        var config = new AgentGroupConfiguration();
        config.setName("team");
        config.setLessonConfig(new LessonConfig(null, null, null,
                new LessonConsolidationConfig("openai", "gpt-5-mini", parameters, null, null, null, null, null, null, null)));
        return config;
    }

    @Test
    @DisplayName("a new literal apiKey is refused on create, naming the field but not the value")
    void literalRefusedOnCreate() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> store.validateConsolidationCredentials(null, null, withParameters(Map.of("apiKey", LITERAL))));
        assertTrue(e.getMessage().contains("lessonConfig.consolidation.parameters.apiKey"), e.getMessage());
        assertFalse(e.getMessage().contains(LITERAL), e.getMessage());
    }

    @Test
    @DisplayName("a vault or vars reference is accepted")
    void referenceAccepted() {
        assertDoesNotThrow(() -> store.validateConsolidationCredentials(null, null,
                withParameters(Map.of("apiKey", "${vault:consolidation-key}", "accessKey", "${vars:aws-access}"))));
    }

    @Test
    @DisplayName("a new ${connection:...} reference is refused, as for a SERVICE judge; a stored one is carried over")
    void connectionReferenceRefused() throws Exception {
        var e = assertThrows(IllegalArgumentException.class,
                () -> store.validateConsolidationCredentials(null, null, withParameters(Map.of("apiKey", "${connection:crm}"))));
        assertTrue(e.getMessage().contains("lessonConfig.consolidation.parameters.apiKey") && e.getMessage().contains("connection"),
                e.getMessage());

        doReturn(withParameters(Map.of("apiKey", "${connection:crm}"))).when(store).read(ID, 1);
        assertDoesNotThrow(() -> store.validateConsolidationCredentials(ID, 1, withParameters(Map.of("apiKey", "${connection:crm}"))));
    }

    @Test
    @DisplayName("a literal the stored version already holds under the same key is carried over on update")
    void carriedOverLiteralAccepted() throws Exception {
        doReturn(withParameters(Map.of("apiKey", LITERAL))).when(store).read(ID, 1);

        assertDoesNotThrow(() -> store.validateConsolidationCredentials(ID, 1, withParameters(Map.of("apiKey", LITERAL, "temperature", "0"))));
    }

    @Test
    @DisplayName("a changed literal, or one the stored version does not hold, is refused on update")
    void changedLiteralRefused() throws Exception {
        doReturn(withParameters(Map.of("apiKey", LITERAL))).when(store).read(ID, 1);

        assertThrows(IllegalArgumentException.class,
                () -> store.validateConsolidationCredentials(ID, 1, withParameters(Map.of("apiKey", LITERAL + "-changed"))));
        assertThrows(IllegalArgumentException.class,
                () -> store.validateConsolidationCredentials(ID, 1, withParameters(Map.of("apiKey", LITERAL, "secretKey", "another"))));
    }

    @Test
    @DisplayName("a stored version that cannot be read counts as holding nothing (fail closed)")
    void unreadableStoredVersionFailsClosed() throws Exception {
        doThrow(new IResourceStore.ResourceStoreException("down")).when(store).read(ID, 1);

        assertThrows(IllegalArgumentException.class,
                () -> store.validateConsolidationCredentials(ID, 1, withParameters(Map.of("apiKey", LITERAL))));
    }

    @Test
    @DisplayName("create() runs the check")
    void createRunsTheCheck() {
        var e = assertThrows(IllegalArgumentException.class, () -> store.create(withParameters(Map.of("apiKey", LITERAL))));
        assertTrue(e.getMessage().contains("lessonConfig.consolidation.parameters.apiKey"), e.getMessage());
    }
}
