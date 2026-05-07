/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.CounterweightConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Engine-level safety injection into LLM system prompts. Counterweights temper
 * agent behavior (overconfident refactors, false claims, identity leakage)
 * without editing prompt templates.
 * <p>
 * Preset text is resolved from {@link PromptSnippetService} first (snippet
 * names: {@code counterweight-cautious}, {@code counterweight-strict}). If no
 * snippet is configured, built-in defaults are used as fallback. Users override
 * presets entirely via {@code customInstructions}.
 * <p>
 * When the conversation was started via {@code ScheduleFireExecutor} (channel
 * tag {@code "scheduled"}), {@code strict} is auto-downgraded to
 * {@code cautious} because "one-step-at-a-time" is destructive for batch/cron
 * agents.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class CounterweightService {

    private static final Logger LOGGER = Logger.getLogger(CounterweightService.class);

    // === Snippet keys for Prompt Snippet resolution ===
    static final String SNIPPET_KEY_CAUTIOUS = "counterweight-cautious";
    static final String SNIPPET_KEY_STRICT = "counterweight-strict";

    // === Fallback presets (used when no Prompt Snippet is configured) ===
    static final String CAUTIOUS_FALLBACK = """
            ## BEHAVIORAL GUIDELINES (engine-injected)
            - Declare your intended actions before executing them.
            - Prefer asking for clarification over guessing.
            - Verify assumptions explicitly before proceeding.
            - When uncertain, state your uncertainty rather than fabricating an answer.""";

    static final String STRICT_FALLBACK = """
            ## BEHAVIORAL GUIDELINES — STRICT MODE (engine-injected)
            - Declare your intended actions before executing them.
            - Prefer asking for clarification over guessing.
            - Verify assumptions explicitly before proceeding.
            - When uncertain, state your uncertainty rather than fabricating an answer.
            - Prohibit sweeping changes — work one step at a time.
            - Explicitly flag any state-changing operation before requesting approval.
            - Do NOT make multiple changes simultaneously.
            - Summarize what you plan to do and wait for confirmation.""";

    static final String SCHEDULED_CHANNEL_TAG = "scheduled";

    private final PromptSnippetService promptSnippetService;
    private final MeterRegistry meterRegistry;
    private Counter activationNormalCounter;
    private Counter activationCautiousCounter;
    private Counter activationStrictCounter;
    private Counter strictDowngradedCounter;

    @Inject
    public CounterweightService(PromptSnippetService promptSnippetService, MeterRegistry meterRegistry) {
        this.promptSnippetService = promptSnippetService;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        activationNormalCounter = meterRegistry.counter("eddi.counterweight.activation.count", "level", "normal");
        activationCautiousCounter = meterRegistry.counter("eddi.counterweight.activation.count", "level", "cautious");
        activationStrictCounter = meterRegistry.counter("eddi.counterweight.activation.count", "level", "strict");
        strictDowngradedCounter = meterRegistry.counter("eddi.counterweight.strict.downgraded");
    }

    /**
     * Apply counterweight injection to the system message.
     *
     * @param systemMessage
     *            the current system message (may be empty)
     * @param config
     *            counterweight config from the LLM task (may be null)
     * @param channelTag
     *            the channel tag of the conversation (e.g. "scheduled")
     * @return the (possibly modified) system message
     */
    public String apply(String systemMessage, CounterweightConfig config, String channelTag) {
        if (config == null || !config.isEnabled()) {
            return systemMessage;
        }

        // Defensive null coalescing — callers typically pass "" but this
        // is a public API so we protect against null
        if (systemMessage == null) {
            systemMessage = "";
        }

        String level = config.getLevel() != null ? config.getLevel() : "normal";

        // Strict downgrade for scheduled/batch agents
        if ("strict".equalsIgnoreCase(level) && SCHEDULED_CHANNEL_TAG.equalsIgnoreCase(channelTag)) {
            LOGGER.debug("counterweight.strict.downgraded.scheduled");
            strictDowngradedCounter.increment();
            level = "cautious";
        }

        String injection = resolveInjection(config, level);
        if (injection == null || injection.isBlank()) {
            activationNormalCounter.increment();
            return systemMessage;
        }

        // Track activation
        switch (level.toLowerCase()) {
            case "cautious" -> activationCautiousCounter.increment();
            case "strict" -> activationStrictCounter.increment();
            default -> activationNormalCounter.increment();
        }

        // Apply placement
        String placement = config.getPlacement() != null ? config.getPlacement() : "suffix";
        if ("prefix".equalsIgnoreCase(placement)) {
            return injection + "\n\n" + systemMessage;
        } else {
            return systemMessage + "\n\n" + injection;
        }
    }

    /**
     * Resolve the injection text. Priority order:
     * <ol>
     * <li>Custom instructions (override everything)</li>
     * <li>Prompt Snippet ({@code counterweight-cautious} /
     * {@code counterweight-strict})</li>
     * <li>Built-in fallback preset</li>
     * </ol>
     */
    private String resolveInjection(CounterweightConfig config, String level) {
        // Custom instructions override presets entirely
        if (config.getCustomInstructions() != null && !config.getCustomInstructions().isEmpty()) {
            StringBuilder sb = new StringBuilder("## BEHAVIORAL GUIDELINES (engine-injected)\n");
            for (String instruction : config.getCustomInstructions()) {
                sb.append("- ").append(instruction).append("\n");
            }
            return sb.toString().trim();
        }

        return switch (level.toLowerCase()) {
            case "cautious" -> resolveFromSnippetsOrFallback(SNIPPET_KEY_CAUTIOUS, CAUTIOUS_FALLBACK);
            case "strict" -> resolveFromSnippetsOrFallback(SNIPPET_KEY_STRICT, STRICT_FALLBACK);
            default -> null; // normal = no injection
        };
    }

    /**
     * Try to resolve preset text from Prompt Snippets; fall back to built-in
     * default if the snippet does not exist.
     */
    private String resolveFromSnippetsOrFallback(String snippetKey, String fallback) {
        Map<String, Object> snippets = promptSnippetService.getAll();
        Object snippetValue = snippets.get(snippetKey);
        if (snippetValue instanceof String text && !text.isBlank()) {
            LOGGER.debugf("Counterweight preset resolved from Prompt Snippet '%s'", snippetKey);
            return text;
        }
        return fallback;
    }
}
