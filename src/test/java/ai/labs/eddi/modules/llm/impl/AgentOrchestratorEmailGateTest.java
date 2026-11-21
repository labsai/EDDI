/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The email approval gate inside the live tool loop (email plan v3 §8.1): for a
 * mail turn whose target is on {@code actions}, a call not in
 * {@code readOnlyTools} pauses whatever the agent's {@code exempt} says, a
 * question is answered without approval, and an untainted turn is unaffected.
 */
class AgentOrchestratorEmailGateTest {

    @Mock
    private CalculatorTool calculatorTool;
    @Mock
    private DateTimeTool dateTimeTool;
    @Mock
    private WebSearchTool webSearchTool;
    @Mock
    private DataFormatterTool dataFormatterTool;
    @Mock
    private WebScraperTool webScraperTool;
    @Mock
    private TextSummarizerTool textSummarizerTool;
    @Mock
    private PdfReaderTool pdfReaderTool;
    @Mock
    private WeatherTool weatherTool;
    @Mock
    private FetchToolResponsePageTool fetchToolResponsePageTool;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private McpToolProviderManager mcpToolProviderManager;
    @Mock
    private A2AToolProviderManager a2aToolProviderManager;
    @Mock
    private IAgentStore restAgentStore;
    @Mock
    private IWorkflowStore restWorkflowStore;
    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IApiCallExecutor apiCallExecutor;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private IUserMemoryStore userMemoryStore;
    @Mock
    private ToolResponseTruncator toolResponseTruncator;
    @Mock
    private TenantQuotaService tenantQuotaService;
    @Mock
    private MemorySnapshotService memorySnapshotService;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;
    @Mock
    private IHitlToolJournalStore journalStore;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orchestrator = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, tenantQuotaService,
                memorySnapshotService,
                null, null, null, null, null,
                journalStore, new ConversationHistoryBuilder(), new TokenCounterFactory());

        when(memory.getConversationId()).thenReturn("conv-1");
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(toolResponseTruncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    Supplier<String> sup = inv.getArgument(4);
                    return sup.get();
                });
    }

    /**
     * A mail turn whose target is on {@code actions} with these read-only tools.
     */
    private void mailTurn(String... readOnlyTools) {
        when(memory.getOrigin()).thenReturn(TurnOrigin.EMAIL);
        IData<Object> gate = new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY,
                EmailApprovalGate.contextValue("actions", List.of(readOnlyTools), false, null, null));
        when(currentStep.getData("context:" + EmailApprovalGate.CONTEXT_KEY)).thenReturn(gate);
    }

    private static LlmConfiguration.Task twoToolTask() {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-A");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "datetime"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        return task;
    }

    /**
     * An agent gate that requires approval for every builtin tool but exempts both.
     */
    private static ToolApprovalsConfig agentExemptingBoth() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("builtin:*"));
        cfg.setExempt(List.of("calculate", "getCurrentDateTime"));
        return cfg;
    }

    private static ChatResponse toolBatch(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests))).build();
    }

    private static ChatResponse text(String answer) {
        return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
    }

    private static ToolExecutionRequest calculate() {
        return ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
    }

    private static ToolExecutionRequest dateTime() {
        return ToolExecutionRequest.builder().id("c2").name("getCurrentDateTime").arguments("{\"timezone\":\"UTC\"}").build();
    }

    @Test
    @DisplayName("actions: a question is answered without approval")
    void questionAnsweredWithoutApproval() {
        mailTurn();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("The answer is 42."));

        assertDoesNotThrow(() -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("what is it?")), twoToolTask(),
                memory, agentExemptingBoth(), 0));
    }

    @Test
    @DisplayName("actions: a call the agent exempts still pauses; a read-only call in the batch runs")
    void exemptCallPauses() {
        mailTurn("getCurrentDateTime");
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(calculate(), dateTime()));

        var pause = assertThrows(ToolApprovalRequiredException.class, () -> orchestrator.executeIfToolsEnabled(chatModel, "sys",
                List.of(UserMessage.from("hi")), twoToolTask(), memory, agentExemptingBoth(), 0));

        verify(calculatorTool, never()).calculate(anyString());
        verify(dateTimeTool, times(1)).getCurrentDateTime(anyString());
        assertEquals(1, pause.getBatch().getCalls().size());
        assertEquals("calculate", pause.getBatch().getCalls().get(0).getToolName());
        assertEquals(EmailApprovalGate.GATE_REASON, pause.getBatch().getCalls().get(0).getGateReason());
    }

    @Test
    @DisplayName("actions: the gate pauses even when the agent has no toolApprovals at all")
    void pausesWithoutAgentConfig() {
        mailTurn();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(calculate()));

        assertThrows(ToolApprovalRequiredException.class, () -> orchestrator.executeIfToolsEnabled(chatModel, "sys",
                List.of(UserMessage.from("hi")), twoToolTask(), memory, null, 0));
        verify(calculatorTool, never()).calculate(anyString());
    }

    @Test
    @DisplayName("an untainted turn with the same agent config runs both calls")
    void untaintedUnaffected() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(calculate(), dateTime()), text("done"));

        assertDoesNotThrow(() -> orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), twoToolTask(), memory,
                agentExemptingBoth(), 0));
        verify(calculatorTool, times(1)).calculate(anyString());
        verify(dateTimeTool, times(1)).getCurrentDateTime(anyString());
    }
}
