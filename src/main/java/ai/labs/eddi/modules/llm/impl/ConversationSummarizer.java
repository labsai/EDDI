/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ConversationSummaryConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Rolling conversation summary engine.
 * <p>
 * Maintains an incrementally updated summary of older conversation turns. The
 * summary is stored as a conversation property ({@link Scope#conversation}) so
 * it persists across turns and is available via O(1) property lookup.
 * <p>
 * <strong>Algorithm:</strong>
 * <ol>
 * <li>Determine how many turns should be summarized (totalSteps -
 * recentWindow)</li>
 * <li>If unsummarized turns exist beyond what's already covered, generate a new
 * summary by re-summarizing the previous summary + the new unsummarized
 * turns</li>
 * <li>Store the updated summary and step boundary as conversation
 * properties</li>
 * </ol>
 * <p>
 * The summary is incremental and self-correcting: if a summarization call
 * fails, the next turn will catch up by summarizing a larger batch.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class ConversationSummarizer {

    private static final Logger LOGGER = Logger.getLogger(ConversationSummarizer.class);

    /** Conversation property key for the running summary text. */
    static final String PROP_RUNNING_SUMMARY = "conversation:running_summary";

    /**
     * Conversation property key for the step index the summary covers through
     * (exclusive).
     */
    static final String PROP_SUMMARY_THROUGH_STEP = "conversation:summary_through_step";

    private static final String DEFAULT_SUMMARIZATION_PROMPT = """
            Summarize the conversation below. You MUST preserve:
            1. The user's stated goals, requirements, and constraints
            2. Decisions made and their reasoning (especially WHY alternatives were rejected)
            3. The sequence of exploration — what was tried, in what order
            4. Important corrections or clarifications the user made
            5. Any agreements, action items, or commitments
            6. The conversational tone and rapport established

            %s

            Format as concise bullet points grouped by topic.
            Keep under %d tokens.""";

    private static final String PROPERTIES_EXCLUSION_BLOCK = """
            The following facts are ALREADY stored as persistent properties.
            Do NOT repeat these — focus only on context they don't capture:
            %s""";

    private final SummarizationService summarizationService;

    @Inject
    public ConversationSummarizer(SummarizationService summarizationService) {
        this.summarizationService = summarizationService;
    }

    /**
     * Check if the rolling summary needs updating and generate it if so. Called
     * from {@code LlmTask} after the LLM response has been generated and stored in
     * memory.
     *
     * @param memory
     *            the conversation memory (read + write)
     * @param config
     *            the summary configuration from the LLM task
     * @param propertiesContext
     *            formatted properties string for exclusion, or null if property
     *            exclusion is disabled
     */
    public void updateIfNeeded(IConversationMemory memory, ConversationSummaryConfig config, String propertiesContext) {
        config.validate();
        int totalSteps = memory.getConversationOutputs().size();
        int recentWindow = config.getRecentWindowSteps();
        int summarizeThroughStep = totalSteps - recentWindow;

        // Not enough turns to warrant summarization
        if (summarizeThroughStep <= 0) {
            return;
        }

        // Check if we already summarized up to this point
        int alreadySummarized = readSummaryThroughStep(memory);
        if (summarizeThroughStep <= alreadySummarized) {
            return;
        }

        LOGGER.infof("[SUMMARY] Updating rolling summary for conversation='%s': steps %d→%d (recent window=%d)", memory.getConversationId(),
                alreadySummarized, summarizeThroughStep, recentWindow);

        // Build content to summarize: previous summary + new unsummarized turns
        String existingSummary = readSummary(memory);
        String newTurnsText = renderTurns(memory.getConversationOutputs(), alreadySummarized, summarizeThroughStep);

        String contentToSummarize;
        if (existingSummary != null && !existingSummary.isEmpty()) {
            contentToSummarize = "## Previous Summary (turns 1-" + alreadySummarized + "):\n" + existingSummary + "\n\n## New Turns (turns "
                    + (alreadySummarized + 1) + "-" + summarizeThroughStep + "):\n" + newTurnsText;
        } else {
            contentToSummarize = newTurnsText;
        }

        // Guard: don't call LLM with empty content (e.g., malformed outputs with no
        // text)
        if (contentToSummarize.isBlank()) {
            LOGGER.debugf("[SUMMARY] No renderable content for turns %d-%d, skipping.", alreadySummarized, summarizeThroughStep);
            return;
        }

        String instructions = buildPrompt(config, propertiesContext);
        String summary = summarizationService.summarize(contentToSummarize, instructions, config.getLlmProvider(), config.getLlmModel());

        if (summary.isEmpty()) {
            LOGGER.warnf("[SUMMARY] Summarization returned empty for conversation='%s'. Will retry next turn.", memory.getConversationId());
            return;
        }

        // Store as conversation properties (persisted with the memory, O(1) lookup)
        var props = memory.getConversationProperties();
        props.put(PROP_RUNNING_SUMMARY, new Property(PROP_RUNNING_SUMMARY, summary, Scope.conversation));
        props.put(PROP_SUMMARY_THROUGH_STEP, new Property(PROP_SUMMARY_THROUGH_STEP, summarizeThroughStep, Scope.conversation));

        LOGGER.infof("[SUMMARY] Updated rolling summary for conversation='%s': covers steps 1-%d, summary length=%d chars",
                memory.getConversationId(), summarizeThroughStep, summary.length());
    }

    /**
     * Read the current running summary from conversation properties.
     *
     * @param memory
     *            the conversation memory
     * @return the summary text, or null if no summary exists
     */
    public static String readSummary(IConversationMemory memory) {
        Property prop = memory.getConversationProperties().get(PROP_RUNNING_SUMMARY);
        return prop != null ? prop.getValueString() : null;
    }

    /**
     * Read how many steps the current summary covers (exclusive boundary).
     *
     * @param memory
     *            the conversation memory
     * @return the step count covered, or 0 if no summary exists
     */
    public static int readSummaryThroughStep(IConversationMemory memory) {
        Property prop = memory.getConversationProperties().get(PROP_SUMMARY_THROUGH_STEP);
        if (prop == null) {
            return 0;
        }
        Integer value = prop.getValueInt();
        return value != null ? value : 0;
    }

    /**
     * Render conversation turns in range [fromStep, toStep) as readable text. Uses
     * the conversation outputs (the same data that ConversationLogGenerator uses).
     */
    static String renderTurns(List<ConversationOutput> outputs, int fromStep, int toStep) {
        var sb = new StringBuilder();
        int effectiveTo = Math.min(toStep, outputs.size());

        for (int i = fromStep; i < effectiveTo; i++) {
            var output = outputs.get(i);
            var input = output.get("input", String.class);
            var outputText = ConversationOutputUtils.extractOutputText(output);

            if (input != null) {
                sb.append("Turn ").append(i + 1).append(" — User: ").append(input).append('\n');
            }
            if (outputText != null && !outputText.isEmpty()) {
                sb.append("Turn ").append(i + 1).append(" — Agent: ").append(outputText).append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * Build the summarization prompt, optionally with property exclusion context.
     */
    private String buildPrompt(ConversationSummaryConfig config, String propertiesContext) {
        if (config.getSummarizationPrompt() != null) {
            return config.getSummarizationPrompt();
        }

        String propertiesBlock = "";
        if (config.isExcludePropertiesFromSummary() && propertiesContext != null && !propertiesContext.isEmpty()) {
            propertiesBlock = String.format(PROPERTIES_EXCLUSION_BLOCK, propertiesContext);
        }

        return String.format(DEFAULT_SUMMARIZATION_PROMPT, propertiesBlock, config.getMaxSummaryTokens());
    }
}
