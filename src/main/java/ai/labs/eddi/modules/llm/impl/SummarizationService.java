/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared LLM summarization infrastructure.
 * <p>
 * Provides a single entry point for any component that needs to summarize text
 * via an LLM call. Currently consumed by:
 * <ul>
 * <li>{@link ConversationSummarizer} — rolling conversation summary</li>
 * <li>{@code DreamService} — Dream memory consolidation</li>
 * </ul>
 * <p>
 * Uses {@link ChatModelRegistry} for model creation and caching. Thread-safe
 * and stateless — all configuration is passed via parameters.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class SummarizationService {

    private static final Logger LOGGER = Logger.getLogger(SummarizationService.class);

    private final ChatModelRegistry chatModelRegistry;
    private final MeterRegistry meterRegistry;

    private Counter callCounter;
    private Counter errorCounter;
    private Timer durationTimer;

    @Inject
    public SummarizationService(ChatModelRegistry chatModelRegistry, MeterRegistry meterRegistry) {
        this.chatModelRegistry = chatModelRegistry;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        callCounter = meterRegistry.counter("summarization.calls");
        errorCounter = meterRegistry.counter("summarization.errors");
        durationTimer = meterRegistry.timer("summarization.duration");
    }

    /**
     * Result of an LLM summarization call, including token usage for cost tracking.
     *
     * @param summary
     *            the generated summary text (empty string on failure)
     * @param inputTokens
     *            number of input tokens consumed (0 if unavailable)
     * @param outputTokens
     *            number of output tokens generated (0 if unavailable)
     */
    public record SummarizationResult(String summary, int inputTokens, int outputTokens) {

        /** Total tokens consumed (input + output). */
        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    /**
     * Summarize content using a specified LLM. Failures are swallowed and return an
     * empty string — use {@link #summarizeWithUsage} if you need to distinguish
     * failures from empty LLM responses.
     *
     * @param content
     *            the text to summarize
     * @param instructions
     *            system-level instructions for the summarizer
     * @param llmProvider
     *            LLM provider type (e.g., "openai", "anthropic")
     * @param llmModel
     *            model name (e.g., "gpt-4o-mini", "claude-sonnet-4-6")
     * @return the generated summary text, or empty string on failure
     */
    public String summarize(String content, String instructions, String llmProvider, String llmModel) {
        try {
            return summarizeWithUsage(content, instructions, llmProvider, llmModel).summary();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Summarize content and return token usage for cost tracking. Unlike
     * {@link #summarize}, this method propagates exceptions to the caller so that
     * failures can be distinguished from empty LLM responses.
     *
     * @return a {@link SummarizationResult} with summary text and token counts
     * @throws RuntimeException
     *             if the LLM call fails
     * @see #summarize(String, String, String, String)
     */
    public SummarizationResult summarizeWithUsage(String content, String instructions,
                                                  String llmProvider, String llmModel) {
        long start = System.nanoTime();
        try {
            Map<String, String> params = new HashMap<>();
            params.put("modelName", llmModel);

            var model = chatModelRegistry.getOrCreate(llmProvider, params);

            List<ChatMessage> messages = List.of(SystemMessage.from(instructions), UserMessage.from(content));

            var response = model.chat(ChatRequest.builder().messages(messages).build());

            callCounter.increment();

            String result = response.aiMessage().text();
            int inputTokens = 0;
            int outputTokens = 0;
            if (response.tokenUsage() != null) {
                inputTokens = response.tokenUsage().inputTokenCount() != null
                        ? response.tokenUsage().inputTokenCount()
                        : 0;
                outputTokens = response.tokenUsage().outputTokenCount() != null
                        ? response.tokenUsage().outputTokenCount()
                        : 0;
            }

            LOGGER.debugf("[SUMMARIZATION] Generated summary: provider=%s, model=%s, " +
                    "inputLength=%d, outputLength=%d, tokens=%d+%d",
                    llmProvider, llmModel,
                    content.length(), result != null ? result.length() : 0,
                    inputTokens, outputTokens);

            return new SummarizationResult(
                    result != null ? result : "", inputTokens, outputTokens);

        } catch (RuntimeException e) {
            errorCounter.increment();
            LOGGER.errorf(e, "[SUMMARIZATION] Failed to summarize: provider=%s, model=%s, error=%s",
                    llmProvider, llmModel, e.getMessage());
            throw e;
        } catch (Exception e) {
            errorCounter.increment();
            LOGGER.errorf(e, "[SUMMARIZATION] Failed to summarize: provider=%s, model=%s, error=%s",
                    llmProvider, llmModel, e.getMessage());
            throw new RuntimeException(e);
        } finally {
            durationTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
}
