/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
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
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * D7 — {@code convertToObject} must reach EVERY execution mode as an API-level
 * JSON response format, not just the no-tools non-streaming one.
 *
 * <p>
 * Before this, {@code jsonMode} was computed once in {@code LlmTask} and handed
 * only to {@link LegacyChatExecutor}. {@code AgentOrchestrator} and
 * {@link StreamingLegacyChatExecutor} both built their {@code ChatRequest}
 * without ever looking at it, so a tool-enabled or streaming mistral /
 * azure-openai agent got no API-level JSON at all while an otherwise identical
 * openai agent did — openai only because {@code AgentSetupService} injected a
 * builder-level {@code responseFormat} that no other builder read.
 * </p>
 *
 * <p>
 * The format is set on the REQUEST, never on the model: a model instance is
 * cached and reused across modes, so a baked format travels into requests that
 * must not carry it — the documented Gemini 400. The load-bearing assertions
 * here are therefore (a) approved providers now get it in agent mode and on the
 * streaming path, (b) an unsupported provider is not sent it at all, (c) Gemini
 * is never sent JSON mode and tool specifications on the same request, and (d)
 * the one path that already worked still works.
 * </p>
 */
@DisplayName("D7 — request-level JSON response format threading")
class JsonResponseFormatThreadingTest {

    private static final String CONVERSATION_ID = "conv-json";

    // ─── Agent-mode fixture (real tool loop) ───

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
    private McpToolProviderManager mcpToolProviderManager;
    @Mock
    private A2AToolProviderManager a2aToolProviderManager;
    @Mock
    private IRestAgentStore restAgentStore;
    @Mock
    private IRestWorkflowStore restWorkflowStore;
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
    private ToolExecutionService toolExecutionService;
    @Mock
    private IHitlToolJournalStore journalStore;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orchestrator = new AgentOrchestrator(
                calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool,
                webScraperTool, textSummarizerTool, pdfReaderTool, weatherTool,
                fetchToolResponsePageTool,
                toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                apiCallExecutor, jsonSerialization, memoryItemConverter,
                userMemoryStore, toolResponseTruncator, null,
                null,
                null, null, null, null, null,
                journalStore, new ConversationHistoryBuilder(), new TokenCounterFactory());

        lenient().when(memory.getConversationId()).thenReturn(CONVERSATION_ID);
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
    }

    /** A task with one built-in tool enabled, so the agent loop actually runs. */
    private LlmConfiguration.Task toolEnabledTask(String provider) {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-json");
        task.setType(provider);
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        task.setMaxToolIterations(2);
        return task;
    }

    /**
     * Runs one agent-mode turn against a model that answers immediately (no tool
     * call), capturing the request the loop actually issued.
     */
    private ChatRequest runAgentTurn(String provider, JsonResponseFormatPolicy policy) throws Exception {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                captured.set(chatRequest);
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"htmlResponseText\":\"hi\"}")).build();
            }
        };

        var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")),
                toolEnabledTask(provider), memory, null, -1, 2_000_000, policy);

        assertNotNull(result, "the tool-enabled task must take the agent path");
        assertNotNull(captured.get(), "the loop must have issued a request");
        return captured.get();
    }

    // ─── 1. Agent mode on an approved provider ───

    @Test
    @DisplayName("a tool-enabled mistral turn with convertToObject sends the JSON response format")
    void agentModeApprovedProviderSendsJson() throws Exception {
        ChatRequest request = runAgentTurn("mistral", JsonResponseFormatPolicy.of(true, "mistral", null));

        assertFalse(request.toolSpecifications().isEmpty(), "the request must carry the tool surface");
        assertNotNull(request.responseFormat(), "mistral accepts json_object alongside function calling");
        assertEquals(ResponseFormatType.JSON, request.responseFormat().type());
    }

    @Test
    @DisplayName("a tool-enabled azure-openai turn with convertToObject sends the JSON response format")
    void agentModeAzureSendsJson() throws Exception {
        ChatRequest request = runAgentTurn("azure-openai", JsonResponseFormatPolicy.of(true, "azure-openai", null));

        assertNotNull(request.responseFormat());
        assertEquals(ResponseFormatType.JSON, request.responseFormat().type());
    }

    // ─── 3. Provider that does not support it ───

    @Test
    @DisplayName("a tool-enabled anthropic turn is NOT sent a schemaless JSON format")
    void agentModeUnsupportedProviderSendsNothing() throws Exception {
        // langchain4j's InternalAnthropicHelper#validate throws
        // UnsupportedFeatureException("Schemaless JSON response format ...") — sending
        // it would fail the turn, not degrade it.
        ChatRequest request = runAgentTurn("anthropic", JsonResponseFormatPolicy.of(true, "anthropic", null));

        assertNull(request.responseFormat());
    }

    // ─── 4. Gemini must never get JSON mode AND tools ───

    @Test
    @DisplayName("gemini with tools and convertToObject is not sent both")
    void geminiWithToolsIsNotSentBoth() throws Exception {
        ChatRequest request = runAgentTurn("gemini", JsonResponseFormatPolicy.of(true, "gemini", null));

        assertFalse(request.toolSpecifications().isEmpty(), "the request does carry tools");
        assertNull(request.responseFormat(),
                "responseMimeType=application/json + tools is the documented Gemini 400");
    }

    @Test
    @DisplayName("gemini WITHOUT tools still gets the JSON format on the legacy path")
    void geminiWithoutToolsStillGetsJson() throws Exception {
        var captured = new ArrayList<ChatRequest>();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                captured.add(chatRequest);
                return ChatResponse.builder().aiMessage(AiMessage.from("{}")).build();
            }
        };

        new LegacyChatExecutor().execute(chatModel, List.of(UserMessage.from("hi")), plainTask("gemini"),
                JsonResponseFormatPolicy.of(true, "gemini", null));

        assertEquals(1, captured.size());
        assertNotNull(captured.getFirst().responseFormat());
        assertTrue(captured.getFirst().toolSpecifications() == null || captured.getFirst().toolSpecifications().isEmpty());
    }

    // ─── 2. Streaming path ───

    @Nested
    @DisplayName("streaming path")
    class Streaming {

        private final StreamingLegacyChatExecutor executor = new StreamingLegacyChatExecutor();
        private final ConversationEventSink eventSink = mock(ConversationEventSink.class);

        private ChatRequest stream(String provider, JsonResponseFormatPolicy policy) {
            AtomicReference<ChatRequest> captured = new AtomicReference<>();
            StreamingChatModel streamingModel = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    captured.set(chatRequest);
                    handler.onPartialResponse("{}");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("{}")).build());
                }
            };

            var result = executor.execute(streamingModel, List.of(UserMessage.from("hi")), eventSink, plainTask(provider), policy);
            assertEquals("{}", result.response());
            assertNotNull(captured.get());
            return captured.get();
        }

        @Test
        @DisplayName("a streamed mistral turn with convertToObject sends the JSON response format")
        void streamingApprovedProviderSendsJson() {
            ChatRequest request = stream("mistral", JsonResponseFormatPolicy.of(true, "mistral", null));

            assertNotNull(request.responseFormat(), "the streaming path dropped jsonMode entirely before D7");
            assertEquals(ResponseFormatType.JSON, request.responseFormat().type());
        }

        @Test
        @DisplayName("a streamed anthropic turn is not sent a schemaless JSON format")
        void streamingUnsupportedProviderSendsNothing() {
            assertNull(stream("anthropic", JsonResponseFormatPolicy.of(true, "anthropic", null)).responseFormat());
        }

        @Test
        @DisplayName("a streamed turn without convertToObject is unchanged")
        void streamingWithoutJsonModeUnchanged() {
            assertNull(stream("openai", JsonResponseFormatPolicy.of(false, "openai", null)).responseFormat());
            assertNull(stream("openai", JsonResponseFormatPolicy.DISABLED).responseFormat());
        }
    }

    // ─── 5. The one path that already worked must keep working ───

    @Test
    @DisplayName("openai no-tools non-streaming still sends the JSON response format")
    void openAiLegacyPathUnchanged() throws Exception {
        var captured = new ArrayList<ChatRequest>();
        var messagesApiCalls = new ArrayList<List<ChatMessage>>();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                captured.add(chatRequest);
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"a\":1}")).build();
            }

            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                messagesApiCalls.add(messages);
                return ChatResponse.builder().aiMessage(AiMessage.from("plain")).build();
            }
        };

        var result = new LegacyChatExecutor().execute(chatModel, List.of(UserMessage.from("hi")), plainTask("openai"),
                JsonResponseFormatPolicy.of(true, "openai", null));

        assertEquals("{\"a\":1}", result.response());
        assertEquals(1, captured.size(), "the ChatRequest API must be used, not the messages API");
        assertTrue(messagesApiCalls.isEmpty());
        assertEquals(ResponseFormatType.JSON, captured.getFirst().responseFormat().type());
    }

    @Test
    @DisplayName("openai no-tools non-streaming without convertToObject uses the plain messages API")
    void openAiLegacyPathWithoutJsonModeUnchanged() throws Exception {
        var captured = new ArrayList<ChatRequest>();
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                captured.add(chatRequest);
                return ChatResponse.builder().aiMessage(AiMessage.from("json")).build();
            }

            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from("plain")).build();
            }
        };

        var result = new LegacyChatExecutor().execute(chatModel, List.of(UserMessage.from("hi")), plainTask("openai"),
                JsonResponseFormatPolicy.of(false, "openai", null));

        assertEquals("plain", result.response());
        assertTrue(captured.isEmpty());
    }

    // ─── Task-level override ───

    @Test
    @DisplayName("jsonResponseFormat=off suppresses the format on an approved provider in agent mode")
    void taskOverrideOff() throws Exception {
        ChatRequest request = runAgentTurn("mistral", JsonResponseFormatPolicy.of(true, "mistral", "off"));

        assertNull(request.responseFormat());
    }

    @Test
    @DisplayName("jsonResponseFormat=on forces the format onto an unlisted provider in agent mode")
    void taskOverrideOn() throws Exception {
        ChatRequest request = runAgentTurn("ollama", JsonResponseFormatPolicy.of(true, "ollama", "on"));

        assertNotNull(request.responseFormat());
    }

    private static LlmConfiguration.Task plainTask(String provider) {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-plain");
        task.setType(provider);
        return task;
    }
}
