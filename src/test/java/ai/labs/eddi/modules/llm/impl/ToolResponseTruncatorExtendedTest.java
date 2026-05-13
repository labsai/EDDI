/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ToolResponseLimits;
import ai.labs.eddi.modules.llm.tools.PaginatedResponseStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ToolResponseTruncator Extended Tests")
class ToolResponseTruncatorExtendedTest {

    private ToolResponseTruncator truncator;
    private PaginatedResponseStore paginatedResponseStore;
    private ChatModelRegistry chatModelRegistry;

    // Default task context for tests
    private static final String TASK_TYPE = "openai";
    private static final Map<String, String> TASK_PARAMS = Map.of(
            "apiKey", "sk-test-key",
            "modelName", "gpt-4o",
            "baseUrl", "https://api.openai.com");

    @BeforeEach
    void setUp() {
        chatModelRegistry = mock(ChatModelRegistry.class);
        truncator = new ToolResponseTruncator(new SimpleMeterRegistry(), chatModelRegistry);
        paginatedResponseStore = mock(PaginatedResponseStore.class);

        // Inject mocked store
        try {
            var f = ToolResponseTruncator.class.getDeclaredField("paginatedResponseStore");
            f.setAccessible(true);
            f.set(truncator, paginatedResponseStore);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("Truncate Strategy (default)")
    class TruncateStrategyTests {

        @Test
        @DisplayName("Should not truncate when within limit")
        void testWithinLimit() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);

            String result = truncator.truncateIfNeeded("testTool", "short response", limits, TASK_TYPE, TASK_PARAMS);
            assertEquals("short response", result);
        }

        @Test
        @DisplayName("Should truncate and append note when exceeding limit")
        void testExceedsLimit() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(10);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(100), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.startsWith("AAAAAAAAAA"));
            assertTrue(result.contains("[TRUNCATED:"));
            assertTrue(result.contains("100 characters"));
        }

        @Test
        @DisplayName("Should return null input unchanged")
        void testNullInput() {
            var limits = new ToolResponseLimits();
            assertNull(truncator.truncateIfNeeded("testTool", null, limits, TASK_TYPE, TASK_PARAMS));
        }

        @Test
        @DisplayName("Should return result unchanged when limits is null")
        void testNullLimits() {
            assertEquals("content", truncator.truncateIfNeeded("testTool", "content", null, TASK_TYPE, TASK_PARAMS));
        }

        @Test
        @DisplayName("Should use per-tool limits when configured")
        void testPerToolLimits() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(1000);
            limits.setPerToolLimits(Map.of("specialTool", 5));

            String result = truncator.truncateIfNeeded("specialTool", "A".repeat(20), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.startsWith("AAAAA"));
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should not truncate when maxChars is 0 (disabled)")
        void testMaxCharsZero() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(0);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(100), limits, TASK_TYPE, TASK_PARAMS);
            assertEquals("A".repeat(100), result);
        }
    }

    @Nested
    @DisplayName("Paginate Strategy")
    class PaginateStrategyTests {

        @Test
        @DisplayName("Should paginate and return first page with responseId")
        void testPaginateResponse() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("paginate");

            when(paginatedResponseStore.store("testTool", "A".repeat(200), 50)).thenReturn("resp-123");
            when(paginatedResponseStore.getPageCount("resp-123")).thenReturn(4);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[PAGINATED:"));
            assertTrue(result.contains("resp-123"));
            assertTrue(result.contains("page 1 of 4"));
        }

        @Test
        @DisplayName("Should fallback to truncate when store returns null")
        void testPaginateFallback() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("paginate");

            when(paginatedResponseStore.store(anyString(), anyString(), anyInt())).thenReturn(null);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback to truncate when store is null")
        void testPaginateNoStore() {
            // Create truncator without store
            var truncatorNoStore = new ToolResponseTruncator(new SimpleMeterRegistry(), chatModelRegistry);

            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("paginate");

            String result = truncatorNoStore.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }
    }

    @Nested
    @DisplayName("Summarize Strategy")
    class SummarizeStrategyTests {

        @Test
        @DisplayName("Should summarize successfully when model returns concise summary")
        void testSummarizeSuccess() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            ChatModel mockModel = mock(ChatModel.class);
            when(chatModelRegistry.getOrCreate(eq("openai"), anyMap())).thenReturn(mockModel);

            ChatResponse mockResponse = mock(ChatResponse.class);
            AiMessage mockMessage = mock(AiMessage.class);
            when(mockResponse.aiMessage()).thenReturn(mockMessage);
            when(mockMessage.text()).thenReturn("Concise summary of the data.");
            when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);

            String result = truncator.truncateIfNeeded("webScraper", "A".repeat(500), limits, TASK_TYPE, TASK_PARAMS);

            assertTrue(result.contains("[SUMMARY"));
            assertTrue(result.contains("Concise summary of the data."));
            assertTrue(result.contains("500 chars"));
            assertTrue(result.contains("webScraper"));
        }

        @Test
        @DisplayName("Should inherit API key from parent task parameters")
        void testSummarizeInheritsApiKey() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            ChatModel mockModel = mock(ChatModel.class);
            ChatResponse mockResponse = mock(ChatResponse.class);
            AiMessage mockMessage = mock(AiMessage.class);
            when(mockResponse.aiMessage()).thenReturn(mockMessage);
            when(mockMessage.text()).thenReturn("Summary");
            when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);

            // Capture the params passed to ChatModelRegistry
            when(chatModelRegistry.getOrCreate(eq("openai"), argThat(params -> "sk-test-key".equals(params.get("apiKey")) &&
                    "gpt-4o-mini".equals(params.get("modelName")) &&
                    "https://api.openai.com".equals(params.get("baseUrl"))))).thenReturn(mockModel);

            String result = truncator.truncateIfNeeded("tool1", "A".repeat(500), limits, TASK_TYPE, TASK_PARAMS);

            // Verify the model was created with correct inherited params
            verify(chatModelRegistry).getOrCreate(eq("openai"), argThat(params -> "sk-test-key".equals(params.get("apiKey")) &&
                    "gpt-4o-mini".equals(params.get("modelName")) &&
                    "https://api.openai.com".equals(params.get("baseUrl"))));
            assertTrue(result.contains("[SUMMARY"));
        }

        @Test
        @DisplayName("Should override modelName with summarizerModel")
        void testSummarizeOverridesModel() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("claude-haiku-4.6");

            Map<String, String> anthropicParams = Map.of(
                    "apiKey", "sk-ant-key",
                    "modelName", "claude-sonnet-4-6");

            ChatModel mockModel = mock(ChatModel.class);
            ChatResponse mockResponse = mock(ChatResponse.class);
            AiMessage mockMessage = mock(AiMessage.class);
            when(mockResponse.aiMessage()).thenReturn(mockMessage);
            when(mockMessage.text()).thenReturn("Summary");
            when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);
            when(chatModelRegistry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(mockModel);

            truncator.truncateIfNeeded("tool1", "A".repeat(500), limits, "anthropic", anthropicParams);

            // Verify modelName was overridden to the summarizer model
            verify(chatModelRegistry).getOrCreate(eq("anthropic"), argThat(params -> "claude-haiku-4.6".equals(params.get("modelName")) &&
                    "sk-ant-key".equals(params.get("apiKey"))));
        }

        @Test
        @DisplayName("Should fallback to truncate when no summarizer model configured")
        void testSummarizeNoModel() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("summarize");
            // No summarizerModel set

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback when response too large for summarization")
        void testSummarizeTooLarge() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(300_000), limits, TASK_TYPE, TASK_PARAMS);
            // Should fallback to truncation due to cost ceiling
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback when ChatModelRegistry throws")
        void testSummarizeFallbackOnModelFailure() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            when(chatModelRegistry.getOrCreate(anyString(), anyMap()))
                    .thenThrow(new ChatModelRegistry.UnsupportedLlmTaskException("unknown type"));

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(500), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback when LLM call throws RuntimeException")
        void testSummarizeFallbackOnLlmError() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            ChatModel mockModel = mock(ChatModel.class);
            when(chatModelRegistry.getOrCreate(anyString(), anyMap())).thenReturn(mockModel);
            when(mockModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("API timeout"));

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(500), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback when summary exceeds maxChars limit")
        void testSummarizeFallbackOnLongerOutput() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            ChatModel mockModel = mock(ChatModel.class);
            when(chatModelRegistry.getOrCreate(anyString(), anyMap())).thenReturn(mockModel);

            ChatResponse mockResponse = mock(ChatResponse.class);
            AiMessage mockMessage = mock(AiMessage.class);
            when(mockResponse.aiMessage()).thenReturn(mockMessage);
            // Model returns text longer than maxChars
            when(mockMessage.text()).thenReturn("X".repeat(100));
            when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback when summary is empty")
        void testSummarizeFallbackOnEmptySummary() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            ChatModel mockModel = mock(ChatModel.class);
            when(chatModelRegistry.getOrCreate(anyString(), anyMap())).thenReturn(mockModel);

            ChatResponse mockResponse = mock(ChatResponse.class);
            AiMessage mockMessage = mock(AiMessage.class);
            when(mockResponse.aiMessage()).thenReturn(mockMessage);
            when(mockMessage.text()).thenReturn("");
            when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback when summary is null")
        void testSummarizeFallbackOnNullSummary() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            ChatModel mockModel = mock(ChatModel.class);
            when(chatModelRegistry.getOrCreate(anyString(), anyMap())).thenReturn(mockModel);

            ChatResponse mockResponse = mock(ChatResponse.class);
            AiMessage mockMessage = mock(AiMessage.class);
            when(mockResponse.aiMessage()).thenReturn(mockMessage);
            when(mockMessage.text()).thenReturn(null);
            when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback when taskType is null")
        void testSummarizeFallbackOnNullTaskType() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, null, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback when taskParameters is null")
        void testSummarizeFallbackOnNullTaskParams() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, null);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should not modify original task parameters")
        void testDoesNotMutateTaskParameters() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            Map<String, String> mutableParams = new HashMap<>(TASK_PARAMS);

            ChatModel mockModel = mock(ChatModel.class);
            ChatResponse mockResponse = mock(ChatResponse.class);
            AiMessage mockMessage = mock(AiMessage.class);
            when(mockResponse.aiMessage()).thenReturn(mockMessage);
            when(mockMessage.text()).thenReturn("Summary");
            when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);
            when(chatModelRegistry.getOrCreate(anyString(), anyMap())).thenReturn(mockModel);

            truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, mutableParams);

            // Verify original params were not mutated
            assertEquals("gpt-4o", mutableParams.get("modelName"));
        }

        @Test
        @DisplayName("Should strip responseFormat from summarizer params")
        void testStripsResponseFormat() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("gpt-4o-mini");

            // Parent task uses JSON response format
            Map<String, String> jsonParams = new HashMap<>(TASK_PARAMS);
            jsonParams.put("responseFormat", "json");

            ChatModel mockModel = mock(ChatModel.class);
            ChatResponse mockResponse = mock(ChatResponse.class);
            AiMessage mockMessage = mock(AiMessage.class);
            when(mockResponse.aiMessage()).thenReturn(mockMessage);
            when(mockMessage.text()).thenReturn("Summary");
            when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);
            when(chatModelRegistry.getOrCreate(anyString(), anyMap())).thenReturn(mockModel);

            truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, jsonParams);

            // Verify responseFormat was stripped (not passed to model registry)
            verify(chatModelRegistry).getOrCreate(eq("openai"), argThat(params -> !params.containsKey("responseFormat") &&
                    "gpt-4o-mini".equals(params.get("modelName"))));
        }
    }

    @Nested
    @DisplayName("Strategy Selection")
    class StrategySelectionTests {

        @Test
        @DisplayName("Should default to truncate when strategy is null")
        void testNullStrategy() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            // truncationStrategy is null (default)

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should default to truncate for unknown strategy")
        void testUnknownStrategy() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("unknown_strategy");

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Strategy selection should be case-insensitive")
        void testCaseInsensitiveStrategy() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("PAGINATE");

            when(paginatedResponseStore.store(anyString(), anyString(), anyInt())).thenReturn("id-1");
            when(paginatedResponseStore.getPageCount("id-1")).thenReturn(2);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[PAGINATED:"));
        }

        @Test
        @DisplayName("Summarize strategy should be case-insensitive")
        void testCaseInsensitiveSummarize() throws Exception {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);
            limits.setTruncationStrategy("SUMMARIZE");
            limits.setSummarizerModel("gpt-4o-mini");

            ChatModel mockModel = mock(ChatModel.class);
            ChatResponse mockResponse = mock(ChatResponse.class);
            AiMessage mockMessage = mock(AiMessage.class);
            when(mockResponse.aiMessage()).thenReturn(mockMessage);
            when(mockMessage.text()).thenReturn("Summary");
            when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);
            when(chatModelRegistry.getOrCreate(anyString(), anyMap())).thenReturn(mockModel);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits, TASK_TYPE, TASK_PARAMS);
            assertTrue(result.contains("[SUMMARY"));
        }
    }

    @Nested
    @DisplayName("Constants")
    class ConstantsTests {

        @Test
        @DisplayName("Cost ceiling should be 200_000 characters")
        void testCostCeiling() {
            assertEquals(200_000, ToolResponseTruncator.SUMMARIZE_COST_CEILING_CHARS);
        }

        @Test
        @DisplayName("System prompt should contain maxChars placeholder")
        void testSystemPromptPlaceholder() {
            assertTrue(ToolResponseTruncator.SUMMARIZER_SYSTEM_PROMPT.contains("%d"));
        }
    }
}
