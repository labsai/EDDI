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
 * <li>{@code DreamService} — future Dream consolidation</li>
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
     * Summarize content using a specified LLM.
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
        long start = System.nanoTime();
        try {
            Map<String, String> params = new HashMap<>();
            params.put("modelName", llmModel);

            var model = chatModelRegistry.getOrCreate(llmProvider, params);

            List<ChatMessage> messages = List.of(SystemMessage.from(instructions), UserMessage.from(content));

            var response = model.chat(ChatRequest.builder().messages(messages).build());

            callCounter.increment();

            String result = response.aiMessage().text();
            LOGGER.debugf("[SUMMARIZATION] Generated summary: provider=%s, model=%s, inputLength=%d, outputLength=%d", llmProvider, llmModel,
                    content.length(), result != null ? result.length() : 0);

            return result != null ? result : "";

        } catch (Exception e) {
            errorCounter.increment();
            LOGGER.warnf(e, "[SUMMARIZATION] Failed to summarize: provider=%s, model=%s, error=%s", llmProvider, llmModel, e.getMessage());
            return "";
        } finally {
            durationTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
}
