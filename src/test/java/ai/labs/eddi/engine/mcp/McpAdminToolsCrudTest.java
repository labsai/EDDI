package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.rules.IRestBehaviorStore;
import ai.labs.eddi.configs.rules.model.BehaviorConfiguration;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.apicalls.IRestHttpCallsStore;
import ai.labs.eddi.configs.apicalls.model.HttpCallsConfiguration;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.dictionary.IRestRegularDictionaryStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for McpAdminTools Phase 8a.2 —
 * update_resource, create_resource, delete_resource, apply_agent_changes,
 * list_agent_resources.
 * Phase 8a.3 — list_agent_triggers, create_agent_trigger, update_agent_trigger,
 * delete_agent_trigger.
 */
class McpAdminToolsCrudTest {

    private static final String AGENT_ID = "test-agent-id";
    private static final String RESOURCE_ID = "test-resource-id";
    private static final String PKG_ID = "test-pkg-id";

    private IRestAgentAdministration agentAdmin;
    private IRestAgentStore AgentStore;
    private IRestWorkflowStore WorkflowStore;
    private IRestDocumentDescriptorStore descriptorStore;
    private IRestBehaviorStore behaviorStore;
    private IRestLlmStore llmStore;
    private IRestHttpCallsStore httpCallsStore;
    private IRestOutputStore outputStore;
    private IRestPropertySetterStore propertySetterStore;
    private IRestRegularDictionaryStore dictionaryStore;
    private IRestAgentTriggerStore AgentTriggerStore;
    private IJsonSerialization jsonSerialization;
    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor scheduleFireExecutor;
    private SchedulePollerService schedulePollerService;
    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws Exception {
        agentAdmin = mock(IRestAgentAdministration.class);
        AgentStore = mock(IRestAgentStore.class);
        WorkflowStore = mock(IRestWorkflowStore.class);
        descriptorStore = mock(IRestDocumentDescriptorStore.class);
        behaviorStore = mock(IRestBehaviorStore.class);
        llmStore = mock(IRestLlmStore.class);
        httpCallsStore = mock(IRestHttpCallsStore.class);
        outputStore = mock(IRestOutputStore.class);
        propertySetterStore = mock(IRestPropertySetterStore.class);
        dictionaryStore = mock(IRestRegularDictionaryStore.class);
        AgentTriggerStore = mock(IRestAgentTriggerStore.class);
        jsonSerialization = mock(IJsonSerialization.class);

        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        when(restInterfaceFactory.get(IRestAgentStore.class)).thenReturn(AgentStore);
        when(restInterfaceFactory.get(IRestWorkflowStore.class)).thenReturn(WorkflowStore);
        when(restInterfaceFactory.get(IRestDocumentDescriptorStore.class)).thenReturn(descriptorStore);
        when(restInterfaceFactory.get(IRestBehaviorStore.class)).thenReturn(behaviorStore);
        when(restInterfaceFactory.get(IRestLlmStore.class)).thenReturn(llmStore);
        when(restInterfaceFactory.get(IRestHttpCallsStore.class)).thenReturn(httpCallsStore);
        when(restInterfaceFactory.get(IRestOutputStore.class)).thenReturn(outputStore);
        when(restInterfaceFactory.get(IRestPropertySetterStore.class)).thenReturn(propertySetterStore);
        when(restInterfaceFactory.get(IRestRegularDictionaryStore.class)).thenReturn(dictionaryStore);
        when(restInterfaceFactory.get(IRestAgentTriggerStore.class)).thenReturn(AgentTriggerStore);

        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        scheduleStore = mock(IScheduleStore.class);
        scheduleFireExecutor = mock(ScheduleFireExecutor.class);
        schedulePollerService = mock(SchedulePollerService.class);
        tools = new McpAdminTools(restInterfaceFactory, agentAdmin, jsonSerialization,
                scheduleStore, scheduleFireExecutor, schedulePollerService);
    }

    // ==================== update_resource ====================

    @Test
    void updateResource_langchain_success() throws IOException {
        var config = new LlmConfiguration(List.of());
        when(jsonSerialization.deserialize("{\"tasks\":[]}", LlmConfiguration.class)).thenReturn(config);
        when(llmStore.updateLlm(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location",
                        "/llmstore/llmconfigs/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\",\"newVersion\":2}");

        String result = tools.updateResource("langchain", RESOURCE_ID, 1, "{\"tasks\":[]}");

        assertNotNull(result);
        assertTrue(result.contains("updated"));
        verify(llmStore).updateLlm(RESOURCE_ID, 1, config);
    }

    @Test
    void updateResource_behavior_success() throws IOException {
        var config = new BehaviorConfiguration();
        when(jsonSerialization.deserialize("{}", BehaviorConfiguration.class)).thenReturn(config);
        when(behaviorStore.updateBehaviorRuleSet(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location",
                        "/behaviorstore/behaviorsets/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        String result = tools.updateResource("behavior", RESOURCE_ID, 1, "{}");

        assertNotNull(result);
        verify(behaviorStore).updateBehaviorRuleSet(RESOURCE_ID, 1, config);
    }

    @Test
    void updateResource_missingType_returnsError() {
        String result = tools.updateResource(null, RESOURCE_ID, 1, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceType is required"));
    }

    @Test
    void updateResource_missingId_returnsError() {
        String result = tools.updateResource("langchain", null, 1, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceId is required"));
    }

    @Test
    void updateResource_missingConfig_returnsError() {
        String result = tools.updateResource("langchain", RESOURCE_ID, 1, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    @Test
    void updateResource_unknownType_returnsError() throws IOException {
        when(jsonSerialization.deserialize(anyString(), any())).thenReturn(null);

        String result = tools.updateResource("unknown", RESOURCE_ID, 1, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Unknown resource type"));
    }

    // ==================== create_resource ====================

    @Test
    void createResource_httpcalls_success() throws IOException {
        var config = new HttpCallsConfiguration();
        when(jsonSerialization.deserialize("{}", HttpCallsConfiguration.class)).thenReturn(config);
        when(httpCallsStore.createHttpCalls(config))
                .thenReturn(Response.created(URI.create(
                        "/httpcallsstore/httpcalls/new-id?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\",\"resourceId\":\"new-id\"}");

        String result = tools.createResource("httpcalls", "{}");

        assertNotNull(result);
        assertTrue(result.contains("created"));
        verify(httpCallsStore).createHttpCalls(config);
    }

    @Test
    void createResource_output_success() throws IOException {
        var config = new OutputConfigurationSet();
        when(jsonSerialization.deserialize("{}", OutputConfigurationSet.class)).thenReturn(config);
        when(outputStore.createOutputSet(config))
                .thenReturn(Response.created(URI.create(
                        "/outputstore/outputsets/new-id?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        String result = tools.createResource("output", "{}");

        assertNotNull(result);
        verify(outputStore).createOutputSet(config);
    }

    @Test
    void createResource_missingType_returnsError() {
        String result = tools.createResource(null, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceType is required"));
    }

    @Test
    void createResource_missingConfig_returnsError() {
        String result = tools.createResource("behavior", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    // ==================== delete_resource ====================

    @Test
    void deleteResource_httpcalls_success() throws IOException {
        when(httpCallsStore.deleteHttpCalls(RESOURCE_ID, 1, false))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        String result = tools.deleteResource("httpcalls", RESOURCE_ID, 1, false);

        assertNotNull(result);
        assertTrue(result.contains("deleted"));
        verify(httpCallsStore).deleteHttpCalls(RESOURCE_ID, 1, false);
    }

    @Test
    void deleteResource_permanent_success() throws IOException {
        when(behaviorStore.deleteBehaviorRuleSet(RESOURCE_ID, 2, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\",\"permanent\":true}");

        String result = tools.deleteResource("behavior", RESOURCE_ID, 2, true);

        assertNotNull(result);
        verify(behaviorStore).deleteBehaviorRuleSet(RESOURCE_ID, 2, true);
    }

    @Test
    void deleteResource_missingType_returnsError() {
        String result = tools.deleteResource(null, RESOURCE_ID, 1, false);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceType is required"));
    }

    @Test
    void deleteResource_missingId_returnsError() {
        String result = tools.deleteResource("langchain", null, 1, false);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceId is required"));
    }

    // ==================== apply_agent_changes ====================

    @Test
    void applyAgentChanges_singleWorkflow_success() throws IOException {
        // Set up Agent with 1 package
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(
                URI.create("eddi://ai.labs.package/WorkflowStore/packages/" + PKG_ID + "?version=1")));
        when(AgentStore.readAgent(AGENT_ID, 1)).thenReturn(agentConfig);

        // Set up package with one extension containing the old URI
        var ext = new WorkflowConfiguration.WorkflowStep();
        ext.setType(URI.create("eddi://ai.labs.llm"));
        var configMap = new HashMap<String, Object>();
        configMap.put("uri", "eddi://ai.labs.llm/llmstore/llmconfigs/lc1?version=1");
        ext.setConfig(configMap);
        var pkgConfig = new WorkflowConfiguration();
        pkgConfig.setWorkflowSteps(new ArrayList<>(List.of(ext)));
        when(WorkflowStore.readWorkflow(PKG_ID, 1)).thenReturn(pkgConfig);

        // Mock updates
        when(WorkflowStore.updateWorkflow(eq(PKG_ID), eq(1), any()))
                .thenReturn(Response.ok().header("Location",
                        "/WorkflowStore/packages/" + PKG_ID + "?version=2").build());
        when(AgentStore.updateAgent(eq(AGENT_ID), eq(1), any()))
                .thenReturn(Response.ok().header("Location",
                        "/AgentStore/agents/" + AGENT_ID + "?version=2").build());

        // Parse mappings JSON
        String mappingsJson = "[{\"oldUri\":\"eddi://ai.labs.llm/llmstore/llmconfigs/lc1?version=1\"," +
                "\"newUri\":\"eddi://ai.labs.llm/llmstore/llmconfigs/lc1?version=2\"}]";
        List<Map<String, String>> mappings = List.of(Map.of(
                "oldUri", "eddi://ai.labs.llm/llmstore/llmconfigs/lc1?version=1",
                "newUri", "eddi://ai.labs.llm/llmstore/llmconfigs/lc1?version=2"));
        when(jsonSerialization.deserialize(mappingsJson, List.class)).thenReturn(mappings);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"cascaded\",\"updatedWorkflows\":1}");

        String result = tools.applyAgentChanges(AGENT_ID, 1, mappingsJson, false, null);

        assertNotNull(result);
        assertTrue(result.contains("cascaded"));
        verify(WorkflowStore).updateWorkflow(eq(PKG_ID), eq(1), any());
        verify(AgentStore).updateAgent(eq(AGENT_ID), eq(1), any());
    }

    @Test
    void applyAgentChanges_noMatchingUris_noUpdates() throws IOException {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(
                URI.create("eddi://ai.labs.package/WorkflowStore/packages/" + PKG_ID + "?version=1")));
        when(AgentStore.readAgent(AGENT_ID, 1)).thenReturn(agentConfig);

        var ext = new WorkflowConfiguration.WorkflowStep();
        ext.setType(URI.create("eddi://ai.labs.llm"));
        var configMap = new HashMap<String, Object>();
        configMap.put("uri", "eddi://ai.labs.llm/llmstore/llmconfigs/lc1?version=1");
        ext.setConfig(configMap);
        var pkgConfig = new WorkflowConfiguration();
        pkgConfig.setWorkflowSteps(new ArrayList<>(List.of(ext)));
        when(WorkflowStore.readWorkflow(PKG_ID, 1)).thenReturn(pkgConfig);

        // Mappings don't match any existing URIs
        String mappingsJson = "[{\"oldUri\":\"eddi://different/resource?version=1\",\"newUri\":\"eddi://different/resource?version=2\"}]";
        List<Map<String, String>> mappings = List.of(Map.of(
                "oldUri", "eddi://different/resource?version=1",
                "newUri", "eddi://different/resource?version=2"));
        when(jsonSerialization.deserialize(mappingsJson, List.class)).thenReturn(mappings);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"cascaded\",\"updatedWorkflows\":0}");

        tools.applyAgentChanges(AGENT_ID, 1, mappingsJson, false, null);

        // No package or Agent updates should occur
        verify(WorkflowStore, never()).updateWorkflow(any(), anyInt(), any());
        verify(AgentStore, never()).updateAgent(any(), anyInt(), any());
    }

    @Test
    void applyAgentChanges_withRedeploy_success() throws IOException {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(
                URI.create("eddi://ai.labs.package/WorkflowStore/packages/" + PKG_ID + "?version=1")));
        when(AgentStore.readAgent(AGENT_ID, 1)).thenReturn(agentConfig);

        var ext = new WorkflowConfiguration.WorkflowStep();
        ext.setType(URI.create("eddi://ai.labs.behavior"));
        var configMap = new HashMap<String, Object>();
        configMap.put("uri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=1");
        ext.setConfig(configMap);
        var pkgConfig = new WorkflowConfiguration();
        pkgConfig.setWorkflowSteps(new ArrayList<>(List.of(ext)));
        when(WorkflowStore.readWorkflow(PKG_ID, 1)).thenReturn(pkgConfig);

        when(WorkflowStore.updateWorkflow(eq(PKG_ID), eq(1), any()))
                .thenReturn(Response.ok().header("Location",
                        "/WorkflowStore/packages/" + PKG_ID + "?version=2").build());
        when(AgentStore.updateAgent(eq(AGENT_ID), eq(1), any()))
                .thenReturn(Response.ok().header("Location",
                        "/AgentStore/agents/" + AGENT_ID + "?version=2").build());
        when(agentAdmin.deployAgent(Environment.production, AGENT_ID, 2, true, true))
                .thenReturn(Response.ok().build());

        String mappingsJson = "[{\"oldUri\":\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=1\"," +
                "\"newUri\":\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=2\"}]";
        List<Map<String, String>> mappings = List.of(Map.of(
                "oldUri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=1",
                "newUri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=2"));
        when(jsonSerialization.deserialize(mappingsJson, List.class)).thenReturn(mappings);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"cascaded\",\"redeployed\":true}");

        String result = tools.applyAgentChanges(AGENT_ID, 1, mappingsJson, true, "production");

        assertTrue(result.contains("cascaded"));
        verify(agentAdmin).deployAgent(Environment.production, AGENT_ID, 2, true, true);
    }

    @Test
    void applyAgentChanges_missingAgentId_returnsError() {
        String result = tools.applyAgentChanges(null, 1, "[{}]", false, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void applyAgentChanges_emptyMappings_noChanges() throws IOException {
        when(jsonSerialization.deserialize("[]", List.class)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"no_changes\"}");

        String result = tools.applyAgentChanges(AGENT_ID, 1, "[]", false, null);

        assertNotNull(result);
        verify(AgentStore, never()).readAgent(any(), anyInt());
    }

    @Test
    void applyAgentChanges_agentNotFound_returnsError() throws IOException {
        when(AgentStore.readAgent(AGENT_ID, 1)).thenReturn(null);
        List<Map<String, String>> mappings = List.of(Map.of("oldUri", "a", "newUri", "b"));
        when(jsonSerialization.deserialize(anyString(), eq(List.class))).thenReturn(mappings);

        String result = tools.applyAgentChanges(AGENT_ID, 1, "[{\"oldUri\":\"a\",\"newUri\":\"b\"}]", false, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Agent not found"));
    }

    // ==================== list_agent_resources ====================

    @Test
    void listAgentResources_success() throws IOException {
        // Set up Agent with 1 package
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(
                URI.create("eddi://ai.labs.package/WorkflowStore/packages/" + PKG_ID + "?version=1")));
        when(AgentStore.readAgent(AGENT_ID, 1)).thenReturn(agentConfig);

        // Agent descriptor
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Test Agent");
        when(descriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(descriptor);

        // Workflow with 2 extensions
        var ext1 = new WorkflowConfiguration.WorkflowStep();
        ext1.setType(URI.create("eddi://ai.labs.llm"));
        ext1.setConfig(Map.of("uri", "eddi://ai.labs.llm/llmstore/llmconfigs/lc1?version=1"));
        var ext2 = new WorkflowConfiguration.WorkflowStep();
        ext2.setType(URI.create("eddi://ai.labs.behavior"));
        ext2.setConfig(Map.of("uri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=1"));
        var pkgConfig = new WorkflowConfiguration();
        pkgConfig.setWorkflowSteps(List.of(ext1, ext2));
        when(WorkflowStore.readWorkflow(PKG_ID, 1)).thenReturn(pkgConfig);

        when(jsonSerialization.serialize(any())).thenReturn(
                "{\"agentId\":\"test-agent-id\",\"agentName\":\"Test Agent\",\"packageCount\":1}");

        String result = tools.listAgentResources(AGENT_ID, 1);

        assertNotNull(result);
        assertTrue(result.contains("Test Agent"));
        verify(AgentStore).readAgent(AGENT_ID, 1);
        verify(WorkflowStore).readWorkflow(PKG_ID, 1);
    }

    @Test
    void listAgentResources_agentNotFound_returnsError() {
        when(AgentStore.readAgent(AGENT_ID, 1)).thenReturn(null);

        String result = tools.listAgentResources(AGENT_ID, 1);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Agent not found"));
    }

    @Test
    void listAgentResources_missingAgentId_returnsError() {
        String result = tools.listAgentResources(null, 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void listAgentResources_packageReadFailure_includesError() throws IOException {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(
                URI.create("eddi://ai.labs.package/WorkflowStore/packages/" + PKG_ID + "?version=1")));
        when(AgentStore.readAgent(AGENT_ID, 1)).thenReturn(agentConfig);

        when(WorkflowStore.readWorkflow(PKG_ID, 1))
                .thenThrow(new RuntimeException("Workflow corrupted"));
        when(jsonSerialization.serialize(any())).thenReturn("{\"packages\":[{\"error\":\"Failed to read package\"}]}");

        String result = tools.listAgentResources(AGENT_ID, 1);

        // Should still succeed (graceful degradation), but include error info
        assertNotNull(result);
        assertTrue(result.contains("error") || result.contains("packages"));
    }

    // ==================== list_agent_triggers ====================

    @Test
    void listAgentTriggers_success() throws IOException {
        var trigger = new AgentTriggerConfiguration();
        trigger.setIntent("support");
        when(AgentTriggerStore.readAllAgentTriggers()).thenReturn(List.of(trigger));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.listAgentTriggers();

        assertNotNull(result);
        verify(AgentTriggerStore).readAllAgentTriggers();
    }

    @Test
    void listAgentTriggers_error_returnsError() {
        when(AgentTriggerStore.readAllAgentTriggers()).thenThrow(new RuntimeException("db error"));

        String result = tools.listAgentTriggers();

        assertTrue(result.contains("error"));
    }

    // ==================== create_agent_trigger ====================

    @Test
    void createAgentTrigger_success() throws IOException {
        var config = new AgentTriggerConfiguration();
        config.setIntent("support");
        when(jsonSerialization.deserialize(anyString(), eq(AgentTriggerConfiguration.class))).thenReturn(config);
        when(AgentTriggerStore.createAgentTrigger(any())).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        String result = tools.createAgentTrigger("{\"intent\":\"support\",\"agentDeployments\":[]}");

        assertNotNull(result);
        assertTrue(result.contains("created"));
        verify(AgentTriggerStore).createAgentTrigger(any());
    }

    @Test
    void createAgentTrigger_missingConfig_returnsError() {
        String result = tools.createAgentTrigger(null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    // ==================== update_agent_trigger ====================

    @Test
    void updateAgentTrigger_success() throws IOException {
        var config = new AgentTriggerConfiguration();
        when(jsonSerialization.deserialize(anyString(), eq(AgentTriggerConfiguration.class))).thenReturn(config);
        when(AgentTriggerStore.updateAgentTrigger(eq("support"), any())).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        String result = tools.updateAgentTrigger("support", "{\"intent\":\"support\"}");

        assertNotNull(result);
        assertTrue(result.contains("updated"));
    }

    @Test
    void updateAgentTrigger_missingIntent_returnsError() {
        String result = tools.updateAgentTrigger(null, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("intent is required"));
    }

    // ==================== delete_agent_trigger ====================

    @Test
    void deleteAgentTrigger_success() throws IOException {
        when(AgentTriggerStore.deleteAgentTrigger("support")).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        String result = tools.deleteAgentTrigger("support");

        assertNotNull(result);
        assertTrue(result.contains("deleted"));
        verify(AgentTriggerStore).deleteAgentTrigger("support");
    }

    @Test
    void deleteAgentTrigger_missingIntent_returnsError() {
        String result = tools.deleteAgentTrigger(null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("intent is required"));
    }
}
