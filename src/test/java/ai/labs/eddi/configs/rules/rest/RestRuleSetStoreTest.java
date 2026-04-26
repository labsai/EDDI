/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestRuleSetStore}.
 */
class RestRuleSetStoreTest {

    private IRuleSetStore ruleSetStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private RestRuleSetStore restStore;

    @BeforeEach
    void setUp() {
        ruleSetStore = mock(IRuleSetStore.class);
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        restStore = new RestRuleSetStore(ruleSetStore, descriptorStore, jsonSchemaCreator);
    }

    @Test
    @DisplayName("readJsonSchema returns 200")
    void readJsonSchema() throws Exception {
        when(jsonSchemaCreator.generateSchema(RuleSetConfiguration.class)).thenReturn("{}");
        assertEquals(200, restStore.readJsonSchema().getStatus());
    }

    @Test
    @DisplayName("readRuleSet delegates to store")
    void readRuleSet() throws Exception {
        var config = new RuleSetConfiguration();
        when(ruleSetStore.read("rule-1", 1)).thenReturn(config);
        assertNotNull(restStore.readRuleSet("rule-1", 1));
    }

    @Test
    @DisplayName("createRuleSet returns 201")
    void createRuleSet() throws Exception {
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getId()).thenReturn("new-id");
        when(resourceId.getVersion()).thenReturn(1);
        when(ruleSetStore.create(any())).thenReturn(resourceId);
        assertEquals(201, restStore.createRuleSet(new RuleSetConfiguration()).getStatus());
    }

    @Test
    @DisplayName("deleteRuleSet delegates")
    void deleteRuleSet() throws Exception {
        restStore.deleteRuleSet("rule-1", 1, false);
        verify(ruleSetStore).delete("rule-1", 1);
    }

    @Test
    @DisplayName("duplicateRuleSet reads then creates")
    void duplicateRuleSet() throws Exception {
        var config = new RuleSetConfiguration();
        when(ruleSetStore.read("rule-1", 1)).thenReturn(config);
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getId()).thenReturn("dup-id");
        when(resourceId.getVersion()).thenReturn(1);
        when(ruleSetStore.create(any())).thenReturn(resourceId);
        assertEquals(201, restStore.duplicateRuleSet("rule-1", 1).getStatus());
    }

    @Test
    @DisplayName("getResourceURI returns non-null")
    void getResourceURI() {
        assertNotNull(restStore.getResourceURI());
    }

    @Test
    @DisplayName("getCurrentResourceId delegates")
    void getCurrentResourceId() throws Exception {
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getVersion()).thenReturn(3);
        when(ruleSetStore.getCurrentResourceId("rule-1")).thenReturn(resourceId);
        assertEquals(3, restStore.getCurrentResourceId("rule-1").getVersion());
    }
}
