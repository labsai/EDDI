/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * The production incident: a {@code greeting} task on Gemini with
 * {@code answerMode: "tool"} (the model answers through the synthetic reply
 * tool). At conversation start the history is empty, so the request used to
 * hold only the system message. This drives the same seam LlmTask uses: the
 * real history builder feeding the real tool loop, and asserts what the model
 * is sent.
 */
@DisplayName("answerMode tool greeting — the model request carries a user turn")
class GreetingAnswerToolRequestTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"htmlResponseText\":{\"type\":\"string\"}},"
            + "\"required\":[\"htmlResponseText\"]}";
    private static final String ANSWER = "{\"htmlResponseText\":\"<p>Hello</p>\"}";

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
    private IWorkflowStore workflowStore;
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
    private SimpleMeterRegistry meters;

    /** Requests the fake model received, in order. */
    private final List<ChatRequest> requests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meters = new SimpleMeterRegistry();
        Metrics.addRegistry(meters);
        orchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool, textSummarizerTool,
                pdfReaderTool, weatherTool, fetchToolResponsePageTool, toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                workflowStore, resourceClientLibrary, apiCallExecutor, jsonSerialization, memoryItemConverter, userMemoryStore,
                toolResponseTruncator, tenantQuotaService, memorySnapshotService, null, null, null, null, null, journalStore,
                new ConversationHistoryBuilder(), new TokenCounterFactory());
        when(memory.getConversationId()).thenReturn("conv-1");
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(toolResponseTruncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(4)).get());
        when(dateTimeTool.getCurrentDateTime(anyString())).thenReturn("2026-11-04T10:00:00Z");
        when(calculatorTool.calculate(anyString())).thenReturn("42");
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(meters);
    }

    private static LlmConfiguration.Task greetingTask() {
        var task = new LlmConfiguration.Task();
        task.setId("greeting");
        task.setType("gemini");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "datetime"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        task.setAnswerMode("tool");
        task.setMaxToolContextTokens(-1);
        Map<String, String> parameters = new HashMap<>();
        parameters.put("convertToObject", "true");
        parameters.put("responseSchema", SCHEMA);
        task.setParameters(parameters);
        return task;
    }

    private ChatModel replyingModel() {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                var reply = ToolExecutionRequest.builder().id("r1").name("reply").arguments(ANSWER).build();
                return ChatResponse.builder().aiMessage(AiMessage.from(List.of(reply))).build();
            }
        };
    }

    @Test
    @DisplayName("greeting at conversation start: the request sent through the answer-tool loop has a user turn")
    void greetingRequestHasUserTurn() throws Exception {
        when(memory.getConversationOutputs()).thenReturn(List.of(new ConversationOutput()));
        var builder = new ConversationHistoryBuilder();
        // exactly what LlmTask does: build once, hand the loop the history without its
        // leading system message
        List<ChatMessage> messages = builder.buildMessages(memory, "You greet.", null, -1, true, null, 0, null);
        List<ChatMessage> history = ConversationHistoryBuilder.withoutLeadingSystemMessage(messages, "You greet.");

        var result = orchestrator.executeIfToolsEnabled(replyingModel(), "You greet.", history, greetingTask(), memory, null, 0,
                PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT, JsonResponseFormatPolicy.of(true, "gemini", null, SCHEMA));

        assertEquals(ANSWER, result.response());
        assertEquals(1, requests.size());
        List<ChatMessage> sent = requests.getFirst().messages();
        assertTrue(sent.stream().anyMatch(UserMessage.class::isInstance), "a system-only request is the Gemini 400");
        assertEquals(ConversationHistoryBuilder.DEFAULT_EMPTY_INPUT_PROMPT, ((UserMessage) sent.getLast()).singleText());
    }
}
