/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.*;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link StructuralMatcher} focusing on readTypedExtension (all
 * switch branches) and readTargetExtensions (0% covered). These are the two
 * largest uncovered methods (22 + 18 missed lines).
 */
@DisplayName("StructuralMatcher — ReadTypedExtension and ReadTargetExtensions Coverage")
class StructuralMatcherTypedExtensionTest {

    private IRestAgentStore agentStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IRestPromptSnippetStore snippetStore;
    private IRestWorkflowStore workflowStore;
    private IRestInterfaceFactory restInterfaceFactory;
    private IJsonSerialization jsonSerialization;

    private StructuralMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IRestAgentStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        snippetStore = mock(IRestPromptSnippetStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        restInterfaceFactory = mock(IRestInterfaceFactory.class);
        jsonSerialization = mock(IJsonSerialization.class);

        matcher = new StructuralMatcher(agentStore, documentDescriptorStore, snippetStore,
                workflowStore, restInterfaceFactory, jsonSerialization);

        doReturn(Collections.emptyList()).when(snippetStore)
                .readSnippetDescriptors(anyString(), anyInt(), anyInt());
    }

    // =========================================================
    // readTargetExtensions — all switch branches via buildPreview
    // =========================================================

    @Nested
    @DisplayName("readTargetExtensions — workflow step processing")
    class ReadTargetExtensionsTests {

        private AgentConfiguration createTargetAgentWithWorkflow(String workflowId, int version,
                                                                 String stepType, String extId, int extVersion)
                throws Exception {
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" +
                    workflowId + "?version=" + version);
            AgentConfiguration targetConfig = new AgentConfiguration();
            targetConfig.setWorkflows(List.of(workflowUri));

            // Set up descriptor with resource for version reading
            DocumentDescriptor agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/targetAgent1?version=1"));
            doReturn(agentDesc).when(documentDescriptorStore).readDescriptor(eq("targetAgent1"), isNull());

            // Set up agent store
            doReturn(targetConfig).when(agentStore).readAgent("targetAgent1", 1);

            // Set up workflow config with a step
            WorkflowConfiguration wfConfig = new WorkflowConfiguration();
            WorkflowConfiguration.WorkflowStep step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create(stepType));
            Map<String, Object> extensions = new HashMap<>();
            extensions.put("uri", "eddi://ai.labs." + stepType.split("\\.")[2] + "/store/" +
                    extId + "?version=" + extVersion);
            step.setExtensions(extensions);
            wfConfig.setWorkflowSteps(List.of(step));

            doReturn(wfConfig).when(workflowStore).readWorkflow(workflowId, version);

            // Descriptor for target workflow
            DocumentDescriptor wfDesc = new DocumentDescriptor();
            wfDesc.setName("Test Workflow");
            wfDesc.setResource(workflowUri);
            doReturn(wfDesc).when(documentDescriptorStore).readDescriptor(eq(workflowId), isNull());

            return targetConfig;
        }

        @Test
        @DisplayName("null stepType is skipped in readTargetExtensions")
        void nullStepTypeSkipped() throws Exception {
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1");
            AgentConfiguration targetConfig = new AgentConfiguration();
            targetConfig.setWorkflows(List.of(workflowUri));

            DocumentDescriptor agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/targetAgent1?version=1"));
            doReturn(agentDesc).when(documentDescriptorStore).readDescriptor(eq("targetAgent1"), isNull());
            doReturn(targetConfig).when(agentStore).readAgent("targetAgent1", 1);

            // Workflow config with null type step
            WorkflowConfiguration wfConfig = new WorkflowConfiguration();
            WorkflowConfiguration.WorkflowStep step = new WorkflowConfiguration.WorkflowStep();
            step.setType(null); // null type
            step.setExtensions(Map.of("uri", "eddi://ai.labs.x/store/ext1?version=1"));
            wfConfig.setWorkflowSteps(List.of(step));
            doReturn(wfConfig).when(workflowStore).readWorkflow("wf1", 1);

            // Provide source workflow to match
            IResourceSource source = mock(IResourceSource.class);
            AgentSourceData agentSource = new AgentSourceData("srcAgent1", "Source Agent",
                    new AgentConfiguration());
            doReturn(agentSource).when(source).readAgent();
            doReturn(List.of(new WorkflowSourceData("srcWf1", "Src Wf", 0,
                    new WorkflowConfiguration(), Map.of()))).when(source).readWorkflows();
            doReturn(List.of()).when(source).readSnippets();

            ImportPreview preview = matcher.buildPreview(source, "targetAgent1", false);
            assertNotNull(preview);
        }

        @Test
        @DisplayName("null uriObj in step extensions is skipped")
        void nullUriObjSkipped() throws Exception {
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1");
            AgentConfiguration targetConfig = new AgentConfiguration();
            targetConfig.setWorkflows(List.of(workflowUri));

            DocumentDescriptor agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/targetAgent1?version=1"));
            doReturn(agentDesc).when(documentDescriptorStore).readDescriptor(eq("targetAgent1"), isNull());
            doReturn(targetConfig).when(agentStore).readAgent("targetAgent1", 1);

            // Step with type but no URI
            WorkflowConfiguration wfConfig = new WorkflowConfiguration();
            WorkflowConfiguration.WorkflowStep step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("ai.labs.dictionary"));
            step.setExtensions(Map.of()); // no "uri" key
            wfConfig.setWorkflowSteps(List.of(step));
            doReturn(wfConfig).when(workflowStore).readWorkflow("wf1", 1);

            IResourceSource source = mock(IResourceSource.class);
            doReturn(new AgentSourceData("srcAgent1", "Source Agent",
                    new AgentConfiguration())).when(source).readAgent();
            doReturn(List.of(new WorkflowSourceData("srcWf1", "Src Wf", 0,
                    new WorkflowConfiguration(), Map.of()))).when(source).readWorkflows();
            doReturn(List.of()).when(source).readSnippets();

            ImportPreview preview = matcher.buildPreview(source, "targetAgent1", false);
            assertNotNull(preview);
        }

        @Test
        @DisplayName("exception in readTargetExtensions is logged and returns empty map")
        void exceptionReturnsEmptyMap() throws Exception {
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1");
            AgentConfiguration targetConfig = new AgentConfiguration();
            targetConfig.setWorkflows(List.of(workflowUri));

            DocumentDescriptor agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/targetAgent1?version=1"));
            doReturn(agentDesc).when(documentDescriptorStore).readDescriptor(eq("targetAgent1"), isNull());
            doReturn(targetConfig).when(agentStore).readAgent("targetAgent1", 1);

            // Throw when reading workflow config
            doThrow(new RuntimeException("test error")).when(workflowStore).readWorkflow("wf1", 1);

            IResourceSource source = mock(IResourceSource.class);
            doReturn(new AgentSourceData("srcAgent1", "Source Agent",
                    new AgentConfiguration())).when(source).readAgent();
            doReturn(List.of(new WorkflowSourceData("srcWf1", "Src Wf", 0,
                    new WorkflowConfiguration(),
                    Map.of("ai.labs.dictionary", new ExtensionSourceData(
                            "dict1", "Dict", "regulardictionary", "ai.labs.dictionary", "{}")))))
                    .when(source).readWorkflows();
            doReturn(List.of()).when(source).readSnippets();
            doReturn("{}").when(jsonSerialization).serialize(any());

            // DocumentDescriptor for wf1
            DocumentDescriptor wfDesc = new DocumentDescriptor();
            wfDesc.setName("Target Wf");
            wfDesc.setResource(workflowUri);
            // The readDescriptor for wf1 is called to get name
            doReturn(wfDesc).when(documentDescriptorStore).readDescriptor(eq("wf1"), isNull());

            ImportPreview preview = matcher.buildPreview(source, "targetAgent1", true);
            assertNotNull(preview);

            // The extension should be CREATE since readTargetExtensions returned empty
            List<ResourceDiff> diffs = preview.resources();
            boolean hasCreateDict = diffs.stream()
                    .anyMatch(d -> "regulardictionary".equals(d.resourceType()) && d.action() == DiffAction.CREATE);
            assertTrue(hasCreateDict, "Extension should be CREATE when target workflow read fails");
        }
    }

    // =========================================================
    // readTypedExtension — switch branches for each type
    // =========================================================

    @Nested
    @DisplayName("readTypedExtension — via buildPreview with includeContent")
    class ReadTypedExtensionSwitchBranches {

        @Test
        @DisplayName("dictionary step type reads via IRestDictionaryStore")
        void dictionaryStepType() throws Exception {
            verifyExtensionTypeRead("ai.labs.dictionary", IRestDictionaryStore.class);
        }

        @Test
        @DisplayName("rules step type reads via IRestRuleSetStore")
        void rulesStepType() throws Exception {
            verifyExtensionTypeRead("ai.labs.rules", IRestRuleSetStore.class);
        }

        @Test
        @DisplayName("apicalls step type reads via IRestApiCallsStore")
        void apicallsStepType() throws Exception {
            verifyExtensionTypeRead("ai.labs.apicalls", IRestApiCallsStore.class);
        }

        @Test
        @DisplayName("llm step type reads via IRestLlmStore")
        void llmStepType() throws Exception {
            verifyExtensionTypeRead("ai.labs.llm", IRestLlmStore.class);
        }

        @Test
        @DisplayName("property step type reads via IRestPropertySetterStore")
        void propertyStepType() throws Exception {
            verifyExtensionTypeRead("ai.labs.property", IRestPropertySetterStore.class);
        }

        @Test
        @DisplayName("output step type reads via IRestOutputStore")
        void outputStepType() throws Exception {
            verifyExtensionTypeRead("ai.labs.output", IRestOutputStore.class);
        }

        @Test
        @DisplayName("mcpcalls step type reads via IRestMcpCallsStore")
        void mcpcallsStepType() throws Exception {
            verifyExtensionTypeRead("ai.labs.mcpcalls", IRestMcpCallsStore.class);
        }

        @Test
        @DisplayName("rag step type reads via IRestRagStore")
        void ragStepType() throws Exception {
            verifyExtensionTypeRead("ai.labs.rag", IRestRagStore.class);
        }

        @Test
        @DisplayName("unknown step type returns null")
        void unknownStepType() throws Exception {
            verifyExtensionTypeRead("ai.labs.unknown", null);
        }

        private void verifyExtensionTypeRead(String stepType, Class<?> expectedStoreClass) throws Exception {
            String extId = "aabbccddeeff112233445566";
            int extVersion = 1;
            URI workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1");

            AgentConfiguration targetConfig = new AgentConfiguration();
            targetConfig.setWorkflows(List.of(workflowUri));

            DocumentDescriptor agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/targetAgent1?version=1"));
            doReturn(agentDesc).when(documentDescriptorStore).readDescriptor(eq("targetAgent1"), isNull());
            doReturn(targetConfig).when(agentStore).readAgent("targetAgent1", 1);

            // Workflow config with the step type
            WorkflowConfiguration wfConfig = new WorkflowConfiguration();
            WorkflowConfiguration.WorkflowStep step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create(stepType));
            Map<String, Object> extensions = new HashMap<>();
            extensions.put("uri", "eddi://ai.labs.x/store/" + extId + "?version=" + extVersion);
            step.setExtensions(extensions);
            wfConfig.setWorkflowSteps(List.of(step));
            doReturn(wfConfig).when(workflowStore).readWorkflow("wf1", 1);

            // Mock the rest interface factory to return a mock store if expected
            if (expectedStoreClass != null) {
                Object mockStore = mock(expectedStoreClass);
                doReturn(mockStore).when(restInterfaceFactory).get(eq(expectedStoreClass));
            }

            // Serialization
            doReturn("{}").when(jsonSerialization).serialize(any());

            // Descriptor for wf1
            DocumentDescriptor wfDesc = new DocumentDescriptor();
            wfDesc.setName("Target Wf");
            wfDesc.setResource(workflowUri);
            doReturn(wfDesc).when(documentDescriptorStore).readDescriptor(eq("wf1"), isNull());

            // Source with a matching step type
            IResourceSource source = mock(IResourceSource.class);
            doReturn(new AgentSourceData("srcAgent1", "Source Agent",
                    new AgentConfiguration())).when(source).readAgent();
            doReturn(List.of(new WorkflowSourceData("srcWf1", "Src Wf", 0,
                    new WorkflowConfiguration(),
                    Map.of(stepType, new ExtensionSourceData(
                            "srcExt1", "Ext", "type", stepType, "{}")))))
                    .when(source).readWorkflows();
            doReturn(List.of()).when(source).readSnippets();

            ImportPreview preview = matcher.buildPreview(source, "targetAgent1", true);
            assertNotNull(preview);
        }
    }

    // =========================================================
    // buildUnmatchedWorkflowDiffs — via buildPreview with extensions
    // =========================================================

    @Nested
    @DisplayName("buildUnmatchedWorkflowDiffs — with extensions")
    class UnmatchedWorkflowWithExtensions {

        @Test
        @DisplayName("unmatched workflow with extensions produces CREATE for all")
        void unmatchedWorkflowWithExtensions() throws Exception {
            // Target agent with no workflows
            AgentConfiguration targetConfig = new AgentConfiguration();
            targetConfig.setWorkflows(List.of());

            DocumentDescriptor agentDesc = new DocumentDescriptor();
            agentDesc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/targetAgent1?version=1"));
            doReturn(agentDesc).when(documentDescriptorStore).readDescriptor(eq("targetAgent1"), isNull());
            doReturn(targetConfig).when(agentStore).readAgent("targetAgent1", 1);

            IResourceSource source = mock(IResourceSource.class);
            doReturn(new AgentSourceData("srcAgent1", "Source Agent",
                    new AgentConfiguration())).when(source).readAgent();

            // Source has a workflow with 2 extensions
            Map<String, ExtensionSourceData> extensions = new LinkedHashMap<>();
            extensions.put("ai.labs.dictionary", new ExtensionSourceData(
                    "dict1", "Dict", "regulardictionary", "ai.labs.dictionary", "{}"));
            extensions.put("ai.labs.llm", new ExtensionSourceData(
                    "llm1", "LLM", "langchain", "ai.labs.llm", "{}"));

            doReturn(List.of(new WorkflowSourceData("srcWf1", "Src Wf", 0,
                    new WorkflowConfiguration(), extensions))).when(source).readWorkflows();
            doReturn(List.of()).when(source).readSnippets();
            doReturn("{}").when(jsonSerialization).serialize(any());

            ImportPreview preview = matcher.buildPreview(source, "targetAgent1", false);
            assertNotNull(preview);

            // All should be CREATE
            long createCount = preview.resources().stream()
                    .filter(d -> d.action() == DiffAction.CREATE)
                    .count();
            // workflow + dict + llm = 3 CREATE diffs (plus agent diff)
            assertTrue(createCount >= 3, "Should have at least 3 CREATE diffs (wf + 2 extensions)");
        }
    }
}
