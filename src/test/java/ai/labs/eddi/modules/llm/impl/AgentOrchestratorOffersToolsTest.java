/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.FetchToolResponsePageTool;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * {@link AgentOrchestrator#offersTools} answers from the assembly the tool loop
 * runs on, not from {@link LlmConfiguration.Task#isAgentMode()}: a task whose
 * only tools come from the workflow (httpcalls, mcpcalls) has tools, and a task
 * whose only tool is the reply tool of {@code answerMode: "tool"} has none of
 * its own.
 */
@DisplayName("AgentOrchestrator.offersTools — the turn's tools, from the loop's own assembly")
class AgentOrchestratorOffersToolsTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}";

    private static AgentOrchestrator orchestrator() {
        return new AgentOrchestrator(new CalculatorTool(), new DateTimeTool(), mock(WebSearchTool.class), new DataFormatterTool(),
                mock(WebScraperTool.class), mock(TextSummarizerTool.class), mock(PdfReaderTool.class), mock(WeatherTool.class),
                mock(FetchToolResponsePageTool.class), mock(ToolExecutionService.class), mock(McpToolProviderManager.class),
                mock(A2AToolProviderManager.class), mock(IWorkflowStore.class), mock(IResourceClientLibrary.class),
                mock(IApiCallExecutor.class), mock(IJsonSerialization.class), mock(IMemoryItemConverter.class),
                mock(IUserMemoryStore.class), mock(ToolResponseTruncator.class), mock(TenantQuotaService.class),
                mock(MemorySnapshotService.class), mock(AgentSetupService.class), mock(CapabilityRegistryService.class),
                mock(IConversationService.class), mock(IAgentFactory.class), mock(IAgentStore.class),
                mock(IHitlToolJournalStore.class), mock(ConversationHistoryBuilder.class), mock(TokenCounterFactory.class));
    }

    private static IConversationMemory memory() {
        var memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        when(memory.getAgentId()).thenReturn("agent-1");
        when(memory.getUserId()).thenReturn("user-1");
        return memory;
    }

    /** No tools of the task's own; the workflow sources are off too. */
    private static LlmConfiguration.Task bareTask() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("openai");
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        return task;
    }

    private static LlmConfiguration.Task answerToolTask() {
        var task = bareTask();
        task.setAnswerMode("tool");
        Map<String, String> parameters = new HashMap<>();
        parameters.put("responseSchema", SCHEMA);
        task.setParameters(parameters);
        return task;
    }

    private static ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).description(name).build();
    }

    private static AgentOrchestrator.ToolSetup setupOf(List<ToolSpecification> specs) {
        return new AgentOrchestrator.ToolSetup(specs, Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of());
    }

    @Test
    @DisplayName("a workflow httpcall tool counts although the task's own configuration has no tools")
    void workflowToolCounts() {
        var task = bareTask();
        var memory = memory();
        var orchestrator = spy(orchestrator());
        doReturn(setupOf(List.of(spec("getWeather")))).when(orchestrator).buildToolSetup(task, memory);

        assertFalse(task.isAgentMode(), "precondition: the configuration-only check sees no tools");
        assertTrue(orchestrator.offersTools(task, memory));
    }

    @Test
    @DisplayName("an empty assembly offers no tools")
    void nothingAssembled() {
        assertFalse(orchestrator().offersTools(bareTask(), memory()));
    }

    @Test
    @DisplayName("a built-in tool counts, through the real assembly")
    void builtInCounts() {
        var task = bareTask();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));

        assertTrue(orchestrator().offersTools(task, memory()));
    }

    @Test
    @DisplayName("the reply tool of answerMode \"tool\" alone is not a tool of the task's own")
    void replyToolAloneDoesNotCount() {
        var task = answerToolTask();
        var memory = memory();
        var orchestrator = orchestrator();

        assertFalse(orchestrator.buildToolSetup(task, memory).toolSpecs().isEmpty(), "precondition: the reply tool is assembled");
        assertFalse(orchestrator.offersTools(task, memory));
    }

    @Test
    @DisplayName("the reply tool next to another tool: that other tool counts")
    void replyToolWithAnotherTool() {
        var task = answerToolTask();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));

        assertTrue(orchestrator().offersTools(task, memory()));
    }
}
