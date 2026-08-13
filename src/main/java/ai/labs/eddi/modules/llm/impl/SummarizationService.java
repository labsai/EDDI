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
import java.util.concurrent.TimeUnit;

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
        return summarize(content, instructions, llmProvider, llmModel, null);
    }

    /**
     * Summarize content, inheriting the calling task's model parameters.
     * <p>
     * Finding F13: this service used to build the parameter map with
     * {@code modelName} and nothing else — no {@code apiKey}, no {@code baseUrl}.
     * Enabling {@code conversationSummary} without global-variable-backed
     * credentials therefore threw, the exception was swallowed as a WARN, and the
     * rolling summary silently never materialised. Pass the parent task's resolved
     * parameters here and only {@code modelName} is overridden — the same
     * inheritance {@link ToolResponseTruncator} already performs for its
     * summarizer.
     * <p>
     * <strong>Caller contract:</strong> the map handed in must belong to
     * {@code llmProvider}. This service cannot tell whose credentials it was given,
     * so it passes them straight to that provider's builder — a map inherited from
     * a task running on a <em>different</em> provider must have its credentials and
     * endpoint coordinates removed first (see
     * {@code LlmTask.resolveInheritedSummaryParameters}), or one vendor's plaintext
     * key ends up in another vendor's auth header.
     *
     * @param inheritedParameters
     *            the calling task's resolved parameters (apiKey, baseUrl, …) for
     *            {@code llmProvider}; may be null, in which case only the model
     *            name is passed
     */
    public String summarize(String content, String instructions, String llmProvider, String llmModel,
                            Map<String, String> inheritedParameters) {
        try {
            return summarizeWithUsage(content, instructions, llmProvider, llmModel, inheritedParameters).summary();
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
        return summarizeWithUsage(content, instructions, llmProvider, llmModel, null);
    }

    /**
     * As {@link #summarizeWithUsage(String, String, String, String)}, but
     * inheriting the calling task's model parameters so the summarizer can actually
     * authenticate (finding F13).
     *
     * @param inheritedParameters
     *            the calling task's resolved parameters, which must belong to
     *            {@code llmProvider} (see
     *            {@link #summarize(String, String, String, String, Map)});
     *            {@code modelName} is overridden with {@code llmModel} and
     *            {@code responseFormat} is stripped (a summary is plain text, never
     *            JSON)
     */
    public SummarizationResult summarizeWithUsage(String content, String instructions,
                                                  String llmProvider, String llmModel,
                                                  Map<String, String> inheritedParameters) {
        long start = System.nanoTime();
        try {
            Map<String, String> params = inheritedParameters != null ? new HashMap<>(inheritedParameters) : new HashMap<>();
            params.put("modelName", llmModel);
            params.remove("responseFormat");

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
            durationTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
