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

import java.util.Locale;
import java.util.Map;

/**
 * Prompt safety presets injected into LLM system prompts. Counterweights temper
 * agent behavior (overconfident refactors, false claims, identity leakage)
 * without editing prompt templates.
 * <p>
 * <strong>Important:</strong> These are prompt-level presets, not engine-level
 * guarantees. The LLM may or may not follow the injected instructions. For
 * engine-enforced controls (tool iteration caps), see the strict-mode handling
 * in {@code ToolLoopRunner}.
 * <p>
 * Preset text is resolved from {@link PromptSnippetService} first (snippet
 * names: {@code counterweight_cautious}, {@code counterweight_strict}). If no
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
    // Snippet names must match [a-z0-9_]+ (PromptSnippetStore), so the keys use
    // underscores. The hyphenated names are read as a legacy fallback only; they
    // cannot be created through the REST API.
    static final String SNIPPET_KEY_CAUTIOUS = "counterweight_cautious";
    static final String SNIPPET_KEY_STRICT = "counterweight_strict";
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
    private Counter activationUnknownCounter;
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
        activationUnknownCounter = meterRegistry.counter("eddi.counterweight.activation.count", "level", "unknown");
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
        return apply(systemMessage, config, channelTag, null);
    }

    /**
     * As {@link #apply(String, CounterweightConfig, String)}, resolving the preset
     * snippets the way a render for {@code agentId} does
     * ({@link PromptSnippetService#getForAgent}): under enforced workspaces only a
     * snippet in the agent's own space, its owner's own snippet (personal-space
     * agents only) or a legacy snippet can replace the built-in preset. A snippet
     * granted or published from another workspace never can, so another workspace
     * cannot override this agent's safety text by naming a snippet
     * {@code counterweight_strict}. A {@code null} agent gets the legacy snippets
     * only.
     *
     * @param agentId
     *            the agent whose system message this is
     */
    public String apply(String systemMessage, CounterweightConfig config, String channelTag, String agentId) {
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

        // Classify the level before resolving: an unknown level resolves no preset, so
        // deciding it after the "no injection" return counted every typo as normal.
        String normalizedLevel = level.toLowerCase(Locale.ROOT);
        boolean knownLevel = "normal".equals(normalizedLevel) || "cautious".equals(normalizedLevel) || "strict".equals(normalizedLevel);
        if (!knownLevel) {
            LOGGER.warnf("Unknown counterweight level '%s' — treating as normal (no preset)", level);
            activationUnknownCounter.increment();
        }

        String injection = resolveInjection(config, normalizedLevel, agentId);
        if (injection == null || injection.isBlank()) {
            if (knownLevel) {
                activationNormalCounter.increment();
            }
            return systemMessage;
        }

        // Track activation (an unknown level was counted above)
        switch (normalizedLevel) {
            case "cautious" -> activationCautiousCounter.increment();
            case "strict" -> activationStrictCounter.increment();
            case "normal" -> activationNormalCounter.increment();
            default -> {
            }
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
     * <li>Prompt Snippet ({@code counterweight_cautious} /
     * {@code counterweight_strict})</li>
     * <li>Built-in fallback preset</li>
     * </ol>
     */
    private String resolveInjection(CounterweightConfig config, String level, String agentId) {
        // Custom instructions override presets entirely
        if (config.getCustomInstructions() != null && !config.getCustomInstructions().isEmpty()) {
            StringBuilder sb = new StringBuilder("## BEHAVIORAL GUIDELINES (engine-injected)\n");
            for (String instruction : config.getCustomInstructions()) {
                sb.append("- ").append(instruction).append("\n");
            }
            return sb.toString().trim();
        }

        return switch (level.toLowerCase(Locale.ROOT)) {
            case "cautious" -> resolveFromSnippetsOrFallback(SNIPPET_KEY_CAUTIOUS, CAUTIOUS_FALLBACK, agentId);
            case "strict" -> resolveFromSnippetsOrFallback(SNIPPET_KEY_STRICT, STRICT_FALLBACK, agentId);
            default -> null; // normal = no injection
        };
    }

    /**
     * Try to resolve preset text from Prompt Snippets; fall back to built-in
     * default if the snippet does not exist.
     */
    private String resolveFromSnippetsOrFallback(String snippetKey, String fallback, String agentId) {
        Map<String, Object> snippets = promptSnippetService.getForAgent(agentId);
        Object snippetValue = snippets.get(snippetKey);
        if (!(snippetValue instanceof String s && !s.isBlank())) {
            snippetValue = snippets.get(snippetKey.replace('_', '-'));
        }
        if (snippetValue instanceof String text && !text.isBlank()) {
            LOGGER.debugf("Counterweight preset resolved from Prompt Snippet '%s'", snippetKey);
            return text;
        }
        return fallback;
    }
}
