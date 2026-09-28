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

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.List;
import java.util.Map;

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

    /**
     * The new turns must get at least {@code 1/MIN_TURN_SHARE_DIVISOR} of
     * {@code maxCharsPerUpdate}; a previous summary that leaves less skips the
     * update rather than being cut.
     */
    private static final int MIN_TURN_SHARE_DIVISOR = 4;

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
        updateIfNeeded(memory, config, propertiesContext, null);
    }

    /**
     * As
     * {@link #updateIfNeeded(IConversationMemory, ConversationSummaryConfig, String)},
     * but inheriting the calling task's resolved model parameters.
     * <p>
     * Finding F13: resolving the summarizer's provider and model is not enough on
     * its own — without the parent task's {@code apiKey} and {@code baseUrl} the
     * summarizer cannot authenticate, the failure is swallowed as a WARN, and the
     * rolling summary silently never materialises. The inherited parameters must
     * travel all the way from {@code LlmTask} to {@link SummarizationService}, so
     * this overload exists purely to carry them across that gap.
     *
     * @param inheritedParameters
     *            the parent task's resolved parameters (apiKey, baseUrl, …),
     *            already reduced by
     *            {@code LlmTask.resolveInheritedSummaryParameters} to what may
     *            travel to {@code config.getLlmProvider()} — credentials and
     *            endpoint coordinates are only present when the summarizer runs on
     *            the parent's own provider. May be null, in which case only the
     *            model name reaches the registry
     */
    public void updateIfNeeded(IConversationMemory memory, ConversationSummaryConfig config, String propertiesContext,
                               Map<String, String> inheritedParameters) {
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

        LOGGER.infof("[SUMMARY] Updating rolling summary for conversation='%s': steps %d→%d (recent window=%d)", sanitize(memory.getConversationId()),
                alreadySummarized, summarizeThroughStep, recentWindow);

        // Bound the batch (M-L3). An unbounded backlog eventually outgrew the
        // summarizer's context window, and from then on every turn made a failing,
        // billed summarizer call. Catch up at most maxTurnsPerUpdate turns at a time,
        // then shrink the batch until the whole request fits maxCharsPerUpdate.
        summarizeThroughStep = Math.min(summarizeThroughStep, alreadySummarized + config.getMaxTurnsPerUpdate());
        int maxChars = config.getMaxCharsPerUpdate();

        // The budget covers the complete request, not just the new turns: the
        // previous summary and its section headings are sent too. Reserve their
        // share up front. The headings are measured at the batch's largest end
        // step, so shortening the batch can only make them shorter.
        String existingSummary = readSummary(memory);
        boolean hasSummary = existingSummary != null && !existingSummary.isEmpty();
        int reserved = hasSummary ? composeContent(existingSummary, alreadySummarized, summarizeThroughStep, "").length() : 0;
        int turnBudget = maxChars - reserved;

        String newTurnsText = renderTurns(memory.getConversationOutputs(), alreadySummarized, summarizeThroughStep);
        while (newTurnsText.length() > turnBudget && summarizeThroughStep > alreadySummarized + 1) {
            summarizeThroughStep--;
            newTurnsText = renderTurns(memory.getConversationOutputs(), alreadySummarized, summarizeThroughStep);
        }

        // Guard: don't call the LLM for a window with no text (e.g. malformed
        // outputs). Checked on the new turns alone — combined with a previous
        // summary the content is never blank, and re-summarizing an unchanged
        // summary would cost a call and, on an empty reply, retry the same blank
        // window every turn.
        if (newTurnsText.isBlank()) {
            // Nothing to condense in this window — but still move past it. Now that a
            // batch is bounded, returning without advancing would re-read the same
            // blank window on every turn and never reach the later turns that do
            // have text. No LLM call, and the summary is left untouched.
            LOGGER.debugf("[SUMMARY] No renderable content for turns %d-%d, advancing past them.", alreadySummarized, summarizeThroughStep);
            memory.getConversationProperties().put(PROP_SUMMARY_THROUGH_STEP,
                    new Property(PROP_SUMMARY_THROUGH_STEP, summarizeThroughStep, Scope.conversation));
            return;
        }

        if (hasSummary && turnBudget < maxChars / MIN_TURN_SHARE_DIVISOR) {
            // The previous summary alone leaves too little room for new turns. It is
            // never cut to make room: it is the only record of the turns it covers, and
            // the reply replaces it. Skip the update instead, with no model call: the
            // boundary stays put, so the turns past it keep reaching the model verbatim
            // (the same fallback as a failed summarizer call) until maxCharsPerUpdate is
            // raised. Lowering maxSummaryTokens cannot release it: that limit only shapes
            // the NEXT summary, and no next summary is produced while this summary is
            // stored — it only keeps summaries small once updates resume.
            LOGGER.warnf("[SUMMARY] Skipping the rolling summary update for conversation='%s': the previous summary and its headings "
                    + "take %d of maxCharsPerUpdate=%d chars, leaving less than a quarter for new turns. "
                    + "Raise maxCharsPerUpdate to at least %d so updates resume (the stored summary is not shortened by lowering "
                    + "maxSummaryTokens, which only bounds summaries written after that).", sanitize(memory.getConversationId()), reserved,
                    maxChars, reserved * MIN_TURN_SHARE_DIVISOR / (MIN_TURN_SHARE_DIVISOR - 1) + 1);
            return;
        }

        if (newTurnsText.length() > turnBudget) {
            // A single turn larger than the whole budget: summarize its head.
            // The notice counts toward the budget too, so the input stays within it.
            String notice = "\n[... the rest of this turn was cut to fit the summarizer's input budget ...]";
            notice = notice.substring(0, Math.min(notice.length(), turnBudget));
            newTurnsText = newTurnsText.substring(0, turnBudget - notice.length()) + notice;
        }

        // Build content to summarize: previous summary + new unsummarized turns
        String contentToSummarize = hasSummary
                ? composeContent(existingSummary, alreadySummarized, summarizeThroughStep, newTurnsText)
                : newTurnsText;

        String instructions = buildPrompt(config, propertiesContext);
        String summary = summarizationService.summarize(contentToSummarize, instructions, config.getLlmProvider(), config.getLlmModel(),
                inheritedParameters);

        if (summary.isEmpty()) {
            LOGGER.warnf("[SUMMARY] Summarization returned empty for conversation='%s'. Will retry next turn.", sanitize(memory.getConversationId()));
            return;
        }

        // Store as conversation properties (persisted with the memory, O(1) lookup)
        var props = memory.getConversationProperties();
        props.put(PROP_RUNNING_SUMMARY, new Property(PROP_RUNNING_SUMMARY, summary, Scope.conversation));
        props.put(PROP_SUMMARY_THROUGH_STEP, new Property(PROP_SUMMARY_THROUGH_STEP, summarizeThroughStep, Scope.conversation));

        LOGGER.infof("[SUMMARY] Updated rolling summary for conversation='%s': covers steps 1-%d, summary length=%d chars",
                sanitize(memory.getConversationId()), summarizeThroughStep, summary.length());
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
     * The request sent when a previous summary exists: the summary and the new
     * turns under their section headings.
     */
    private static String composeContent(String existingSummary, int alreadySummarized, int summarizeThroughStep, String newTurnsText) {
        return "## Previous Summary (turns 1-" + alreadySummarized + "):\n" + existingSummary + "\n\n## New Turns (turns " + (alreadySummarized + 1)
                + "-" + summarizeThroughStep + "):\n" + newTurnsText;
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
