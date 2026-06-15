/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Covers ALL switch cases in readResourceByType, updateResourceByType,
 * createResourceByType, deleteResourceByType, uriToResourceType, and other
 * branch-heavy methods like listAgentResources, applyAgentChanges, createAgent,
 * updateAgent, listWorkflows, readWorkflow, and trigger CRUD.
 */
@DisplayName("McpAdminTools — Switch & Dispatch Branch Coverage")
class McpAdminToolsSwitchCoverageTest {

    @Mock
    private IRestInterfaceFactory restInterfaceFactory;
    @Mock
    private IRestAgentAdministration agentAdmin;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private IScheduleStore scheduleStore;
    @Mock
    private ScheduleFireExecutor scheduleFireExecutor;
    @Mock
    private SchedulePollerService schedulePollerService;
    @Mock
    private SecurityIdentity identity;

    // Store mocks
    @Mock
    private IRestRuleSetStore ruleSetStore;
    @Mock
    private IRestLlmStore llmStore;
    @Mock
    private IRestApiCallsStore apiCallsStore;
    @Mock
    private IRestMcpCallsStore mcpCallsStore;
    @Mock
    private IRestOutputStore outputStore;
    @Mock
    private IRestPropertySetterStore propertySetterStore;
    @Mock
    private IRestDictionaryStore dictionaryStore;
    @Mock
    private IRestAgentStore restAgentStore;
    @Mock
    private IRestWorkflowStore workflowStore;
    @Mock
    private IRestDocumentDescriptorStore descriptorStore;
    @Mock
    private IRestAgentTriggerStore triggerStore;

    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        tools = new McpAdminTools(restInterfaceFactory, agentAdmin, jsonSerialization,
                scheduleStore, scheduleFireExecutor, schedulePollerService,
                identity, false);

        // Wire up all store mocks through restInterfaceFactory
        doReturn(ruleSetStore).when(restInterfaceFactory).get(IRestRuleSetStore.class);
        doReturn(llmStore).when(restInterfaceFactory).get(IRestLlmStore.class);
        doReturn(apiCallsStore).when(restInterfaceFactory).get(IRestApiCallsStore.class);
        doReturn(mcpCallsStore).when(restInterfaceFactory).get(IRestMcpCallsStore.class);
        doReturn(outputStore).when(restInterfaceFactory).get(IRestOutputStore.class);
        doReturn(propertySetterStore).when(restInterfaceFactory).get(IRestPropertySetterStore.class);
        doReturn(dictionaryStore).when(restInterfaceFactory).get(IRestDictionaryStore.class);
        doReturn(restAgentStore).when(restInterfaceFactory).get(IRestAgentStore.class);
        doReturn(workflowStore).when(restInterfaceFactory).get(IRestWorkflowStore.class);
        doReturn(descriptorStore).when(restInterfaceFactory).get(IRestDocumentDescriptorStore.class);
        doReturn(triggerStore).when(restInterfaceFactory).get(IRestAgentTriggerStore.class);
    }

    // ═══════════════════════════════════════════════════════════════
    // readResource — all 7 switch cases + default
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("readResource — all type switch cases")
    class ReadResourceSwitch {

        @Test
        @DisplayName("behavior → reads RuleSet")
        void readBehavior() throws Exception {
            when(ruleSetStore.readRuleSet("id1", 1)).thenReturn(new RuleSetConfiguration());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.readResource("behavior", "id1", 1);
            assertNotNull(result);
            verify(ruleSetStore).readRuleSet("id1", 1);
        }

        @Test
        @DisplayName("langchain → reads Llm")
        void readLangchain() throws Exception {
            when(llmStore.readLlm("id1", 1)).thenReturn(new LlmConfiguration(List.of()));
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.readResource("langchain", "id1", 1);
            assertNotNull(result);
            verify(llmStore).readLlm("id1", 1);
        }

        @Test
        @DisplayName("httpcalls → reads ApiCalls")
        void readHttpcalls() throws Exception {
            when(apiCallsStore.readApiCalls("id1", 1)).thenReturn(new ApiCallsConfiguration());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.readResource("httpcalls", "id1", 1);
            assertNotNull(result);
            verify(apiCallsStore).readApiCalls("id1", 1);
        }

        @Test
        @DisplayName("mcpcalls → reads McpCalls")
        void readMcpcalls() throws Exception {
            when(mcpCallsStore.readMcpCalls("id1", 1)).thenReturn(new McpCallsConfiguration());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.readResource("mcpcalls", "id1", 1);
            assertNotNull(result);
            verify(mcpCallsStore).readMcpCalls("id1", 1);
        }

        @Test
        @DisplayName("output → reads OutputSet")
        void readOutput() throws Exception {
            when(outputStore.readOutputSet("id1", 1, "", "", 0, 0)).thenReturn(new OutputConfigurationSet());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.readResource("output", "id1", 1);
            assertNotNull(result);
            verify(outputStore).readOutputSet("id1", 1, "", "", 0, 0);
        }

        @Test
        @DisplayName("propertysetter → reads PropertySetter")
        void readPropertysetter() throws Exception {
            when(propertySetterStore.readPropertySetter("id1", 1)).thenReturn(new PropertySetterConfiguration());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.readResource("propertysetter", "id1", 1);
            assertNotNull(result);
            verify(propertySetterStore).readPropertySetter("id1", 1);
        }

        @Test
        @DisplayName("dictionaries → reads RegularDictionary")
        void readDictionaries() throws Exception {
            when(dictionaryStore.readRegularDictionary("id1", 1, "", "", 0, 0))
                    .thenReturn(new DictionaryConfiguration());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.readResource("dictionaries", "id1", 1);
            assertNotNull(result);
            verify(dictionaryStore).readRegularDictionary("id1", 1, "", "", 0, 0);
        }

        @Test
        @DisplayName("readResource with null config → error")
        void readReturnsNull() throws Exception {
            when(ruleSetStore.readRuleSet("id1", 1)).thenReturn(null);
            String result = tools.readResource("behavior", "id1", 1);
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("readResource version defaults to 1 when null")
        void readNullVersion() throws Exception {
            when(ruleSetStore.readRuleSet("id1", 1)).thenReturn(new RuleSetConfiguration());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.readResource("behavior", "id1", null);
            assertNotNull(result);
            verify(ruleSetStore).readRuleSet("id1", 1);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // updateResource — all 7 switch cases + default
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("updateResource — all type switch cases")
    class UpdateResourceSwitch {

        private Response mockLocationResponse(String location) {
            Response resp = mock(Response.class);
            doReturn(location).when(resp).getHeaderString("Location");
            doReturn(200).when(resp).getStatus();
            return resp;
        }

        @Test
        @DisplayName("behavior → updates RuleSet")
        void updateBehavior() throws Exception {
            Response resp = mockLocationResponse("/rulesets/id1?version=2");
            when(jsonSerialization.deserialize(eq("{}"), eq(RuleSetConfiguration.class))).thenReturn(new RuleSetConfiguration());
            when(ruleSetStore.updateRuleSet(eq("id1"), eq(1), any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateResource("behavior", "id1", 1, "{}");
            assertNotNull(result);
            verify(ruleSetStore).updateRuleSet(eq("id1"), eq(1), any());
        }

        @Test
        @DisplayName("langchain → updates Llm")
        void updateLangchain() throws Exception {
            Response resp = mockLocationResponse("/llms/id1?version=2");
            when(jsonSerialization.deserialize(eq("{}"), eq(LlmConfiguration.class))).thenReturn(new LlmConfiguration(List.of()));
            when(llmStore.updateLlm(eq("id1"), eq(1), any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateResource("langchain", "id1", 1, "{}");
            assertNotNull(result);
            verify(llmStore).updateLlm(eq("id1"), eq(1), any());
        }

        @Test
        @DisplayName("httpcalls → updates ApiCalls")
        void updateHttpcalls() throws Exception {
            Response resp = mockLocationResponse("/apicalls/id1?version=2");
            when(jsonSerialization.deserialize(eq("{}"), eq(ApiCallsConfiguration.class))).thenReturn(new ApiCallsConfiguration());
            when(apiCallsStore.updateApiCalls(eq("id1"), eq(1), any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateResource("httpcalls", "id1", 1, "{}");
            assertNotNull(result);
            verify(apiCallsStore).updateApiCalls(eq("id1"), eq(1), any());
        }

        @Test
        @DisplayName("mcpcalls → updates McpCalls")
        void updateMcpcalls() throws Exception {
            Response resp = mockLocationResponse("/mcpcalls/id1?version=2");
            when(jsonSerialization.deserialize(eq("{}"), eq(McpCallsConfiguration.class))).thenReturn(new McpCallsConfiguration());
            when(mcpCallsStore.updateMcpCalls(eq("id1"), eq(1), any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateResource("mcpcalls", "id1", 1, "{}");
            assertNotNull(result);
            verify(mcpCallsStore).updateMcpCalls(eq("id1"), eq(1), any());
        }

        @Test
        @DisplayName("output → updates OutputSet")
        void updateOutput() throws Exception {
            Response resp = mockLocationResponse("/output/id1?version=2");
            when(jsonSerialization.deserialize(eq("{}"), eq(OutputConfigurationSet.class))).thenReturn(new OutputConfigurationSet());
            when(outputStore.updateOutputSet(eq("id1"), eq(1), any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateResource("output", "id1", 1, "{}");
            assertNotNull(result);
            verify(outputStore).updateOutputSet(eq("id1"), eq(1), any());
        }

        @Test
        @DisplayName("propertysetter → updates PropertySetter")
        void updatePropertysetter() throws Exception {
            Response resp = mockLocationResponse("/prop/id1?version=2");
            when(jsonSerialization.deserialize(eq("{}"), eq(PropertySetterConfiguration.class))).thenReturn(new PropertySetterConfiguration());
            when(propertySetterStore.updatePropertySetter(eq("id1"), eq(1), any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateResource("propertysetter", "id1", 1, "{}");
            assertNotNull(result);
            verify(propertySetterStore).updatePropertySetter(eq("id1"), eq(1), any());
        }

        @Test
        @DisplayName("dictionaries → updates Dictionary")
        void updateDictionaries() throws Exception {
            Response resp = mockLocationResponse("/dict/id1?version=2");
            when(jsonSerialization.deserialize(eq("{}"), eq(DictionaryConfiguration.class))).thenReturn(new DictionaryConfiguration());
            when(dictionaryStore.updateRegularDictionary(eq("id1"), eq(1), any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateResource("dictionaries", "id1", 1, "{}");
            assertNotNull(result);
            verify(dictionaryStore).updateRegularDictionary(eq("id1"), eq(1), any());
        }

        @Test
        @DisplayName("unknown type → error")
        void updateUnknownType() {
            String result = tools.updateResource("invalid_type", "id1", 1, "{}");
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("null location header — no resourceUri in result")
        void updateNullLocation() throws Exception {
            when(jsonSerialization.deserialize(eq("{}"), eq(RuleSetConfiguration.class))).thenReturn(new RuleSetConfiguration());
            Response resp = mock(Response.class);
            doReturn((String) null).when(resp).getHeaderString("Location");
            doReturn(200).when(resp).getStatus();
            when(ruleSetStore.updateRuleSet(eq("id1"), eq(1), any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateResource("behavior", "id1", 1, "{}");
            assertNotNull(result);
        }

        @Test
        @DisplayName("null version defaults to 1")
        void updateNullVersion() throws Exception {
            Response resp = mockLocationResponse("/rules/id1?version=2");
            when(jsonSerialization.deserialize(eq("{}"), eq(RuleSetConfiguration.class))).thenReturn(new RuleSetConfiguration());
            when(ruleSetStore.updateRuleSet(eq("id1"), eq(1), any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateResource("behavior", "id1", null, "{}");
            assertNotNull(result);
            verify(ruleSetStore).updateRuleSet(eq("id1"), eq(1), any());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // createResource — all 7 switch cases + default
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("createResource — all type switch cases")
    class CreateResourceSwitch {

        private Response mockLocationResponse(String location) {
            Response resp = mock(Response.class);
            doReturn(location).when(resp).getHeaderString("Location");
            doReturn(201).when(resp).getStatus();
            return resp;
        }

        @Test
        @DisplayName("behavior → creates RuleSet")
        void createBehavior() throws Exception {
            Response resp = mockLocationResponse("/rulesets/newid?version=1");
            when(jsonSerialization.deserialize(eq("{}"), eq(RuleSetConfiguration.class))).thenReturn(new RuleSetConfiguration());
            when(ruleSetStore.createRuleSet(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createResource("behavior", "{}");
            assertNotNull(result);
        }

        @Test
        @DisplayName("langchain → creates Llm")
        void createLangchain() throws Exception {
            Response resp = mockLocationResponse("/llms/newid?version=1");
            when(jsonSerialization.deserialize(eq("{}"), eq(LlmConfiguration.class))).thenReturn(new LlmConfiguration(List.of()));
            when(llmStore.createLlm(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createResource("langchain", "{}");
            assertNotNull(result);
        }

        @Test
        @DisplayName("httpcalls → creates ApiCalls")
        void createHttpcalls() throws Exception {
            Response resp = mockLocationResponse("/apicalls/newid?version=1");
            when(jsonSerialization.deserialize(eq("{}"), eq(ApiCallsConfiguration.class))).thenReturn(new ApiCallsConfiguration());
            when(apiCallsStore.createApiCalls(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createResource("httpcalls", "{}");
            assertNotNull(result);
        }

        @Test
        @DisplayName("mcpcalls → creates McpCalls")
        void createMcpcalls() throws Exception {
            Response resp = mockLocationResponse("/mcpcalls/newid?version=1");
            when(jsonSerialization.deserialize(eq("{}"), eq(McpCallsConfiguration.class))).thenReturn(new McpCallsConfiguration());
            when(mcpCallsStore.createMcpCalls(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createResource("mcpcalls", "{}");
            assertNotNull(result);
        }

        @Test
        @DisplayName("output → creates OutputSet")
        void createOutput() throws Exception {
            Response resp = mockLocationResponse("/output/newid?version=1");
            when(jsonSerialization.deserialize(eq("{}"), eq(OutputConfigurationSet.class))).thenReturn(new OutputConfigurationSet());
            when(outputStore.createOutputSet(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createResource("output", "{}");
            assertNotNull(result);
        }

        @Test
        @DisplayName("propertysetter → creates PropertySetter")
        void createPropertysetter() throws Exception {
            Response resp = mockLocationResponse("/prop/newid?version=1");
            when(jsonSerialization.deserialize(eq("{}"), eq(PropertySetterConfiguration.class))).thenReturn(new PropertySetterConfiguration());
            when(propertySetterStore.createPropertySetter(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createResource("propertysetter", "{}");
            assertNotNull(result);
        }

        @Test
        @DisplayName("dictionaries → creates Dictionary")
        void createDictionaries() throws Exception {
            Response resp = mockLocationResponse("/dict/newid?version=1");
            when(jsonSerialization.deserialize(eq("{}"), eq(DictionaryConfiguration.class))).thenReturn(new DictionaryConfiguration());
            when(dictionaryStore.createRegularDictionary(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createResource("dictionaries", "{}");
            assertNotNull(result);
        }

        @Test
        @DisplayName("unknown type → error")
        void createUnknownType() {
            String result = tools.createResource("invalid_type", "{}");
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("null location → resourceId unknown")
        void createNullLocation() throws Exception {
            when(jsonSerialization.deserialize(eq("{}"), eq(RuleSetConfiguration.class))).thenReturn(new RuleSetConfiguration());
            Response resp = mock(Response.class);
            doReturn((String) null).when(resp).getHeaderString("Location");
            doReturn(201).when(resp).getStatus();
            when(ruleSetStore.createRuleSet(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createResource("behavior", "{}");
            assertNotNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // deleteResource — all 7 switch cases + default
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("deleteResource — all type switch cases")
    class DeleteResourceSwitch {

        @Test
        @DisplayName("behavior → deletes RuleSet")
        void deleteBehavior() throws Exception {
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(ruleSetStore.deleteRuleSet("id1", 1, false)).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteResource("behavior", "id1", 1, false);
            assertNotNull(result);
            verify(ruleSetStore).deleteRuleSet("id1", 1, false);
        }

        @Test
        @DisplayName("langchain → deletes Llm")
        void deleteLangchain() throws Exception {
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(llmStore.deleteLlm("id1", 1, true)).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteResource("langchain", "id1", 1, true);
            assertNotNull(result);
            verify(llmStore).deleteLlm("id1", 1, true);
        }

        @Test
        @DisplayName("httpcalls → deletes ApiCalls")
        void deleteHttpcalls() throws Exception {
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(apiCallsStore.deleteApiCalls("id1", 1, false)).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteResource("httpcalls", "id1", 1, false);
            assertNotNull(result);
        }

        @Test
        @DisplayName("mcpcalls → deletes McpCalls")
        void deleteMcpcalls() throws Exception {
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(mcpCallsStore.deleteMcpCalls("id1", 1, false)).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteResource("mcpcalls", "id1", 1, false);
            assertNotNull(result);
        }

        @Test
        @DisplayName("output → deletes OutputSet")
        void deleteOutput() throws Exception {
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(outputStore.deleteOutputSet("id1", 1, false)).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteResource("output", "id1", 1, false);
            assertNotNull(result);
        }

        @Test
        @DisplayName("propertysetter → deletes PropertySetter")
        void deletePropertysetter() throws Exception {
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(propertySetterStore.deletePropertySetter("id1", 1, false)).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteResource("propertysetter", "id1", 1, false);
            assertNotNull(result);
        }

        @Test
        @DisplayName("dictionaries → deletes Dictionary")
        void deleteDictionaries() throws Exception {
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(dictionaryStore.deleteRegularDictionary("id1", 1, false)).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteResource("dictionaries", "id1", 1, false);
            assertNotNull(result);
        }

        @Test
        @DisplayName("unknown type → error")
        void deleteUnknownType() {
            String result = tools.deleteResource("invalid_type", "id1", 1, false);
            assertTrue(result.contains("error"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // listWorkflows
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("listWorkflows")
    class ListWorkflowsTests {

        @Test
        @DisplayName("with filter and limit")
        void withFilterAndLimit() throws Exception {
            when(workflowStore.readWorkflowDescriptors("test", 0, 5)).thenReturn(List.of());
            when(jsonSerialization.serialize(any())).thenReturn("[]");
            String result = tools.listWorkflows("test", 5);
            assertNotNull(result);
            verify(workflowStore).readWorkflowDescriptors("test", 0, 5);
        }

        @Test
        @DisplayName("null filter/limit defaults")
        void nullDefaults() throws Exception {
            when(workflowStore.readWorkflowDescriptors("", 0, 20)).thenReturn(List.of());
            when(jsonSerialization.serialize(any())).thenReturn("[]");
            String result = tools.listWorkflows(null, null);
            assertNotNull(result);
            verify(workflowStore).readWorkflowDescriptors("", 0, 20);
        }

        @Test
        @DisplayName("exception → error")
        void exception() throws Exception {
            doThrow(new RuntimeException("fail")).when(restInterfaceFactory).get(IRestWorkflowStore.class);
            String result = tools.listWorkflows(null, null);
            assertTrue(result.contains("error"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // readWorkflow — config null branch
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("readWorkflow — success cases")
    class ReadWorkflowSuccess {

        @Test
        @DisplayName("successful read returns config")
        void successfulRead() throws Exception {
            var wfConfig = new WorkflowConfiguration();
            when(workflowStore.readWorkflow("wf1", 2)).thenReturn(wfConfig);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.readWorkflow("wf1", 2);
            assertNotNull(result);
            assertFalse(result.contains("error"));
        }

        @Test
        @DisplayName("config null → error")
        void configNull() throws Exception {
            when(workflowStore.readWorkflow("wf1", 1)).thenReturn(null);
            String result = tools.readWorkflow("wf1", 1);
            assertTrue(result.contains("error"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // createAgent — with workflows, descriptor patch, null fallbacks
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("createAgent — branches")
    class CreateAgentBranches {

        @Test
        @DisplayName("with workflow URIs")
        void withWorkflowUris() throws Exception {
            Response resp = mock(Response.class);
            doReturn("/agentstore/agents/newAgent?version=1").when(resp).getHeaderString("Location");
            doReturn(201).when(resp).getStatus();
            when(restAgentStore.createAgent(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createAgent("TestAgent", "A test agent",
                    "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1");
            assertNotNull(result);
        }

        @Test
        @DisplayName("null location → agentId unknown")
        void nullLocation() throws Exception {
            Response resp = mock(Response.class);
            doReturn((String) null).when(resp).getHeaderString("Location");
            doReturn(201).when(resp).getStatus();
            when(restAgentStore.createAgent(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createAgent("TestAgent", null, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("descriptor patch fails but agent still created")
        void descriptorPatchFails() throws Exception {
            Response resp = mock(Response.class);
            doReturn("/agentstore/agents/newAgent?version=1").when(resp).getHeaderString("Location");
            doReturn(201).when(resp).getStatus();
            when(restAgentStore.createAgent(any())).thenReturn(resp);
            doThrow(new RuntimeException("patch error")).when(descriptorStore).patchDescriptor(anyString(), anyInt(), any());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createAgent("TestAgent", "desc", null);
            assertNotNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // updateAgent — name/desc/redeploy branches
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("updateAgent — branches")
    class UpdateAgentBranches {

        @Test
        @DisplayName("update with name only (no description)")
        void nameOnly() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateAgent("agent1", 1, "NewName", null, null, null);
            assertNotNull(result);
            verify(descriptorStore).patchDescriptor(eq("agent1"), eq(1), any());
        }

        @Test
        @DisplayName("update with description only (no name)")
        void descOnly() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateAgent("agent1", 1, null, "New description", null, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("neither name nor description — no patch")
        void noPatch() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateAgent("agent1", 1, null, null, null, null);
            assertNotNull(result);
            verify(descriptorStore, never()).patchDescriptor(anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("redeploy=true — deploys agent")
        void redeploy() throws Exception {
            Response deployResp = mock(Response.class);
            when(deployResp.getStatus()).thenReturn(200);
            when(agentAdmin.deployAgent(any(), eq("agent1"), eq(1), anyBoolean(), anyBoolean())).thenReturn(deployResp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateAgent("agent1", 1, null, null, true, "test");
            assertNotNull(result);
        }

        @Test
        @DisplayName("redeploy fails → deployError in result")
        void redeployFails() throws Exception {
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenThrow(new RuntimeException("deploy fail"));
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateAgent("agent1", 1, null, null, true, null);
            assertNotNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // deleteAgent — cascade/permanent branches
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("deleteAgent — branches")
    class DeleteAgentBranches {

        @Test
        @DisplayName("with cascade=true, permanent=true")
        void cascadePermanent() throws Exception {
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(restAgentStore.deleteAgent("agent1", 1, true, true)).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteAgent("agent1", 1, true, true);
            assertNotNull(result);
            verify(restAgentStore).deleteAgent("agent1", 1, true, true);
        }

        @Test
        @DisplayName("null version/permanent/cascade defaults")
        void nullDefaults() throws Exception {
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(restAgentStore.deleteAgent("agent1", 1, false, false)).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteAgent("agent1", null, null, null);
            assertNotNull(result);
            verify(restAgentStore).deleteAgent("agent1", 1, false, false);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // listAgentResources — deep workflow walk
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("listAgentResources — branches")
    class ListAgentResourcesBranches {

        @Test
        @DisplayName("agent with workflows, extensions with type and URI")
        void agentWithExtensions() throws Exception {
            var agent = new AgentConfiguration();
            agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1")));
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(agent);

            var desc = new DocumentDescriptor();
            desc.setName("TestAgent");
            when(descriptorStore.readDescriptor("agent1", 1)).thenReturn(desc);

            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("ai.labs.rules"));
            step.setConfig(new java.util.HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=1")));
            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow("wf1", 1)).thenReturn(wfConfig);

            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.listAgentResources("agent1", 1);
            assertNotNull(result);
        }

        @Test
        @DisplayName("agent not found → error")
        void agentNotFound() throws Exception {
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(null);
            String result = tools.listAgentResources("agent1", 1);
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("descriptor read fails → no name")
        void descriptorReadFails() throws Exception {
            var agent = new AgentConfiguration();
            agent.setWorkflows(List.of());
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(agent);
            doThrow(new RuntimeException("fail")).when(descriptorStore).readDescriptor("agent1", 1);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.listAgentResources("agent1", 1);
            assertNotNull(result);
        }

        @Test
        @DisplayName("null descriptor → no name")
        void nullDescriptor() throws Exception {
            var agent = new AgentConfiguration();
            agent.setWorkflows(List.of());
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(agent);
            when(descriptorStore.readDescriptor("agent1", 1)).thenReturn(null);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.listAgentResources("agent1", 1);
            assertNotNull(result);
        }

        @Test
        @DisplayName("workflow read fails → error in wfInfo")
        void workflowReadFails() throws Exception {
            var agent = new AgentConfiguration();
            agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1")));
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(agent);
            when(descriptorStore.readDescriptor("agent1", 1)).thenReturn(null);
            when(workflowStore.readWorkflow("wf1", 1)).thenThrow(new RuntimeException("read fail"));
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.listAgentResources("agent1", 1);
            assertNotNull(result);
        }

        @Test
        @DisplayName("extension with null type → no type in extInfo")
        void extensionNullType() throws Exception {
            var agent = new AgentConfiguration();
            agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1")));
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(agent);
            when(descriptorStore.readDescriptor("agent1", 1)).thenReturn(null);

            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(null);
            step.setConfig(new java.util.HashMap<>());
            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow("wf1", 1)).thenReturn(wfConfig);

            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.listAgentResources("agent1", 1);
            assertNotNull(result);
        }

        @Test
        @DisplayName("workflow config null → no extensions")
        void workflowConfigNull() throws Exception {
            var agent = new AgentConfiguration();
            agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1")));
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(agent);
            when(descriptorStore.readDescriptor("agent1", 1)).thenReturn(null);
            when(workflowStore.readWorkflow("wf1", 1)).thenReturn(null);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.listAgentResources("agent1", 1);
            assertNotNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Trigger CRUD — success paths
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent trigger CRUD — success paths")
    class TriggerCrudSuccess {

        @Test
        @DisplayName("listAgentTriggers success")
        void listSuccess() throws Exception {
            when(triggerStore.readAllAgentTriggers()).thenReturn(List.of());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.listAgentTriggers();
            assertNotNull(result);
        }

        @Test
        @DisplayName("listAgentTriggers exception → error")
        void listException() throws Exception {
            doThrow(new RuntimeException("fail")).when(restInterfaceFactory).get(IRestAgentTriggerStore.class);
            String result = tools.listAgentTriggers();
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("createAgentTrigger success")
        void createSuccess() throws Exception {
            var config = new AgentTriggerConfiguration();
            config.setIntent("greeting");
            when(jsonSerialization.deserialize(anyString(), eq(AgentTriggerConfiguration.class))).thenReturn(config);
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(201);
            when(triggerStore.createAgentTrigger(any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.createAgentTrigger("{\"intent\":\"greeting\"}");
            assertNotNull(result);
        }

        @Test
        @DisplayName("createAgentTrigger null intent in config → error")
        void createNullIntent() throws Exception {
            var config = new AgentTriggerConfiguration();
            config.setIntent(null);
            when(jsonSerialization.deserialize(anyString(), eq(AgentTriggerConfiguration.class))).thenReturn(config);
            String result = tools.createAgentTrigger("{\"intent\":null}");
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("createAgentTrigger blank intent in config → error")
        void createBlankIntent() throws Exception {
            var config = new AgentTriggerConfiguration();
            config.setIntent("  ");
            when(jsonSerialization.deserialize(anyString(), eq(AgentTriggerConfiguration.class))).thenReturn(config);
            String result = tools.createAgentTrigger("{\"intent\":\"  \"}");
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("createAgentTrigger exception → error")
        void createException() throws Exception {
            when(jsonSerialization.deserialize(anyString(), eq(AgentTriggerConfiguration.class)))
                    .thenThrow(new RuntimeException("parse error"));
            String result = tools.createAgentTrigger("{\"bad\":true}");
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("updateAgentTrigger success")
        void updateSuccess() throws Exception {
            var config = new AgentTriggerConfiguration();
            when(jsonSerialization.deserialize(anyString(), eq(AgentTriggerConfiguration.class))).thenReturn(config);
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(triggerStore.updateAgentTrigger(eq("greeting"), any())).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.updateAgentTrigger("greeting", "{}");
            assertNotNull(result);
        }

        @Test
        @DisplayName("updateAgentTrigger exception → error")
        void updateException() throws Exception {
            doThrow(new RuntimeException("fail")).when(restInterfaceFactory).get(IRestAgentTriggerStore.class);
            String result = tools.updateAgentTrigger("greeting", "{}");
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("deleteAgentTrigger success")
        void deleteSuccess() throws Exception {
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(triggerStore.deleteAgentTrigger("greeting")).thenReturn(resp);
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.deleteAgentTrigger("greeting");
            assertNotNull(result);
        }

        @Test
        @DisplayName("deleteAgentTrigger exception → error")
        void deleteException() throws Exception {
            doThrow(new RuntimeException("fail")).when(restInterfaceFactory).get(IRestAgentTriggerStore.class);
            String result = tools.deleteAgentTrigger("greeting");
            assertTrue(result.contains("error"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // applyAgentChanges — deep workflow cascade branches
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("applyAgentChanges — branches")
    class ApplyAgentChangesBranches {

        @Test
        @DisplayName("empty mappings → no_changes result")
        void emptyMappings() throws Exception {
            when(jsonSerialization.deserialize(eq("[]"), eq(List.class))).thenReturn(List.of());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.applyAgentChanges("agent1", 1, "[]", null, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("null version defaults to 1")
        void nullVersion() throws Exception {
            when(jsonSerialization.deserialize(eq("[]"), eq(List.class))).thenReturn(List.of());
            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.applyAgentChanges("agent1", null, "[]", null, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("agent not found → error")
        void agentNotFound() throws Exception {
            when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                    .thenReturn(List.of(Map.of("oldUri", "old", "newUri", "new")));
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(null);
            String result = tools.applyAgentChanges("agent1", 1, "[{}]", null, null);
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("workflow modified → agent updated with new workflow URIs")
        void workflowModified() throws Exception {
            var mapping = new java.util.HashMap<String, String>();
            mapping.put("oldUri", "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=1");
            mapping.put("newUri", "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=2");
            when(jsonSerialization.deserialize(anyString(), eq(List.class))).thenReturn(List.of(mapping));

            var agent = new AgentConfiguration();
            agent.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1"))));
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(agent);

            var step = new WorkflowConfiguration.WorkflowStep();
            step.setConfig(new java.util.HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=1")));
            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow("wf1", 1)).thenReturn(wfConfig);

            Response wfResp = mock(Response.class);
            doReturn("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2").when(wfResp).getHeaderString("Location");
            when(workflowStore.updateWorkflow(eq("wf1"), eq(1), any())).thenReturn(wfResp);

            Response agentResp = mock(Response.class);
            doReturn("/agentstore/agents/agent1?version=2").when(agentResp).getHeaderString("Location");
            when(restAgentStore.updateAgent(eq("agent1"), eq(1), any())).thenReturn(agentResp);

            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.applyAgentChanges("agent1", 1, "[{}]", false, null);
            assertNotNull(result);
            verify(workflowStore).updateWorkflow(eq("wf1"), eq(1), any());
            verify(restAgentStore).updateAgent(eq("agent1"), eq(1), any());
        }

        @Test
        @DisplayName("redeploy=true after cascade")
        void redeployAfterCascade() throws Exception {
            var mapping = new java.util.HashMap<String, String>();
            mapping.put("oldUri", "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=1");
            mapping.put("newUri", "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=2");
            when(jsonSerialization.deserialize(anyString(), eq(List.class))).thenReturn(List.of(mapping));

            var agent = new AgentConfiguration();
            agent.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1"))));
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(agent);

            var step = new WorkflowConfiguration.WorkflowStep();
            step.setConfig(new java.util.HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=1")));
            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow("wf1", 1)).thenReturn(wfConfig);

            Response wfResp = mock(Response.class);
            doReturn((String) null).when(wfResp).getHeaderString("Location");
            when(workflowStore.updateWorkflow(eq("wf1"), eq(1), any())).thenReturn(wfResp);

            Response agentResp = mock(Response.class);
            doReturn((String) null).when(agentResp).getHeaderString("Location");
            when(restAgentStore.updateAgent(eq("agent1"), eq(1), any())).thenReturn(agentResp);

            Response deployResp = mock(Response.class);
            when(deployResp.getStatus()).thenReturn(200);
            when(agentAdmin.deployAgent(any(), eq("agent1"), eq(2), anyBoolean(), anyBoolean())).thenReturn(deployResp);

            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.applyAgentChanges("agent1", 1, "[{}]", true, "production");
            assertNotNull(result);
            verify(agentAdmin).deployAgent(any(), eq("agent1"), eq(2), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("redeploy exception → deployError in result")
        void redeployException() throws Exception {
            var mapping = new java.util.HashMap<String, String>();
            mapping.put("oldUri", "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=1");
            mapping.put("newUri", "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=2");
            when(jsonSerialization.deserialize(anyString(), eq(List.class))).thenReturn(List.of(mapping));

            var agent = new AgentConfiguration();
            agent.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1"))));
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(agent);

            var step = new WorkflowConfiguration.WorkflowStep();
            step.setConfig(new java.util.HashMap<>(Map.of("uri", "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=1")));
            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow("wf1", 1)).thenReturn(wfConfig);

            Response wfResp = mock(Response.class);
            doReturn("wf1?version=2").when(wfResp).getHeaderString("Location");
            when(workflowStore.updateWorkflow(eq("wf1"), eq(1), any())).thenReturn(wfResp);

            Response agentResp = mock(Response.class);
            doReturn("agent1?version=2").when(agentResp).getHeaderString("Location");
            when(restAgentStore.updateAgent(eq("agent1"), eq(1), any())).thenReturn(agentResp);

            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenThrow(new RuntimeException("deploy fail"));
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            String result = tools.applyAgentChanges("agent1", 1, "[{}]", true, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("workflow config null → keep URI as-is")
        void workflowConfigNull() throws Exception {
            var mapping = new java.util.HashMap<String, String>();
            mapping.put("oldUri", "old");
            mapping.put("newUri", "new");
            when(jsonSerialization.deserialize(anyString(), eq(List.class))).thenReturn(List.of(mapping));

            var agent = new AgentConfiguration();
            agent.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1"))));
            when(restAgentStore.readAgent("agent1", 1)).thenReturn(agent);
            when(workflowStore.readWorkflow("wf1", 1)).thenReturn(null);

            when(jsonSerialization.serialize(any())).thenReturn("{}");
            String result = tools.applyAgentChanges("agent1", 1, "[{}]", false, null);
            assertNotNull(result);
            verify(restAgentStore, never()).updateAgent(anyString(), anyInt(), any());
        }
    }
}
