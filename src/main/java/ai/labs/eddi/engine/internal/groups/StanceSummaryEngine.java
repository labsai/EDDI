/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.StanceSummaryConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.MemberStance;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import ai.labs.eddi.modules.llm.impl.TokenPricing;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the one-line "where this member stands" summaries that the overview
 * dashboard renders in its "who thinks what" band.
 * <p>
 * Two producers, and which one ran is carried through to the UI rather than
 * hidden:
 * <ul>
 * <li><b>Lead-sentence extraction</b> — the default. Takes the first sentence
 * of the member's newest substantive contribution. Costs nothing, needs no
 * configuration, and is the member's <em>own words</em>.</li>
 * <li><b>LLM summarization</b> — opt-in via
 * {@link StanceSummaryConfig#hasSummarizer()}. Reads everything the member has
 * said this discussion and states their position. Better, and billable.</li>
 * </ul>
 * A summarizer failure degrades to extraction rather than to nothing: the band
 * is the dashboard's headline content, and an empty roster is a worse outcome
 * than a rougher one. Failures are WARN and never propagate — a display
 * projection must not be able to fail a discussion.
 * <p>
 * <b>Stateless</b>, like every other engine in this package: all state lives on
 * the {@link GroupConversation} passed in.
 *
 * @author ginccc
 */
public final class StanceSummaryEngine {

    private static final Logger LOGGER = Logger.getLogger(StanceSummaryEngine.class);

    /**
     * Kept deliberately blunt. The band gives each member one line, so anything
     * that invites a preamble ("Here is a summary of...") wastes the whole budget
     * before reaching the position.
     */
    static final String STANCE_INSTRUCTIONS = """
            You are summarizing one participant's position in a multi-agent discussion.
            Reply with ONE sentence stating what this participant argues for and their main condition or caveat.
            Write in third person, present tense. Do not name the participant.
            Do not add a preamble, a label, quotes, or any text beyond that one sentence.
            """;

    /**
     * Entry types that carry a member's position. Everything else is either
     * bookkeeping (SKIPPED, ERROR, CONVERGENCE, FACILITATION) or not the member
     * speaking for themselves (QUESTION is the user's, SYNTHESIS is the moderator's
     * summary of everyone).
     * <p>
     * ABSTAINED is excluded on purpose even though it is a real member act: its
     * content is a refusal to add anything, so extracting a stance from it would
     * replace a member's actual last position with "I have nothing to add".
     */
    private static final Set<TranscriptEntryType> STANCE_BEARING = EnumSet.of(
            TranscriptEntryType.OPINION, TranscriptEntryType.CRITIQUE, TranscriptEntryType.REVISION,
            TranscriptEntryType.CHALLENGE, TranscriptEntryType.DEFENSE, TranscriptEntryType.ARGUMENT,
            TranscriptEntryType.REBUTTAL, TranscriptEntryType.PLAN, TranscriptEntryType.TASK_RESULT,
            TranscriptEntryType.VERIFICATION, TranscriptEntryType.DISSENT, TranscriptEntryType.VOTE,
            TranscriptEntryType.PROPOSAL, TranscriptEntryType.BARGAIN, TranscriptEntryType.HUMAN_INPUT,
            TranscriptEntryType.BID, TranscriptEntryType.FOLLOW_UP, TranscriptEntryType.RETRO);

    private StanceSummaryEngine() {
    }

    /**
     * One member's freshly computed stance, plus what it cost.
     *
     * @param agentId
     *            the member it belongs to
     * @param stance
     *            the stored value
     * @param cost
     *            USD spent producing it — {@code 0.0} for extraction, and for a
     *            summarizer whose config carries no prices
     */
    public record StanceResult(String agentId, MemberStance stance, double cost) {
    }

    /**
     * Recomputes stances for every member with something new to say, and writes
     * them onto {@code gc}.
     * <p>
     * Call at phase boundaries. Members whose stored stance already covers the
     * whole transcript are skipped, so a member silent through a phase costs
     * nothing — that skip is the difference between one summarizer call per member
     * per discussion and one per member per phase.
     *
     * @param config
     *            may be {@code null}, which means extraction for everyone (the
     *            documented default, not a degraded mode)
     * @param summarizationService
     *            may be {@code null} (not available), which also means extraction
     * @return one result per member whose stance changed, in stable member order,
     *         for the caller to emit as {@code stance_updated} events. Never
     *         {@code null}
     */
    public static List<StanceResult> updateStances(GroupConversation gc, StanceSummaryConfig config,
                                                   SummarizationService summarizationService) {
        if (gc == null) {
            return List.of();
        }
        List<TranscriptEntry> transcript;
        synchronized (gc.getTranscript()) {
            transcript = List.copyOf(gc.getTranscript());
        }
        if (transcript.isEmpty()) {
            return List.of();
        }

        int maxChars = config != null ? config.maxChars() : StanceSummaryConfig.DEFAULT_MAX_CHARS;
        boolean useLlm = config != null && config.hasSummarizer() && summarizationService != null;

        var results = new ArrayList<StanceResult>();
        for (var contribution : groupBySpeaker(transcript).entrySet()) {
            String agentId = contribution.getKey();
            List<TranscriptEntry> entries = contribution.getValue();

            MemberStance existing = gc.getMemberStances().get(agentId);
            // The whole transcript length, not this member's entry count: the
            // stored index is compared against the transcript the stance was
            // computed from, and a member who said nothing this phase has the
            // same entries as before and therefore nothing to recompute.
            if (existing != null && existing.upToTranscriptIndex() >= transcript.size()) {
                continue;
            }

            StanceResult result = useLlm
                    ? summarize(gc, agentId, entries, transcript.size(), config, summarizationService, maxChars)
                    : new StanceResult(agentId, extract(entries, transcript.size(), maxChars), 0.0);
            if (result == null || result.stance() == null) {
                continue;
            }
            // Unchanged text still counts as covered — storing the refreshed
            // index is what stops the next boundary paying to learn the same
            // thing again.
            gc.getMemberStances().put(agentId, result.stance());
            if (existing == null || !result.stance().text().equals(existing.text())) {
                results.add(result);
            }
        }
        return results;
    }

    /**
     * Runs the summarizer for one member, falling back to extraction on any failure
     * or on an empty/blank response.
     * <p>
     * Spend is booked to the discussion's I1 ledger under
     * {@code system:stance:<agentId>:<coverage>} — keyed per member <i>and</i>
     * coverage boundary so a re-run of the same computation replaces (idempotent,
     * per {@code GroupCostLedger}'s record-by-replacement contract) while a later
     * boundary's recomputation adds.
     */
    private static StanceResult summarize(GroupConversation gc, String agentId, List<TranscriptEntry> entries,
                                          int coverage, StanceSummaryConfig config,
                                          SummarizationService summarizationService, int maxChars) {
        String content = renderForSummarizer(entries);
        if (content.isBlank()) {
            return null;
        }
        try {
            var result = summarizationService.summarizeWithUsage(content, STANCE_INSTRUCTIONS,
                    config.llmProvider(), config.llmModel());
            String text = clean(result.summary(), maxChars);
            if (text == null) {
                LOGGER.warnf("Group %s: stance summarization returned nothing for member %s — extracting instead",
                        LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(agentId));
                return new StanceResult(agentId, extract(entries, coverage, maxChars), 0.0);
            }
            double cost = TokenPricing.cost(config.inputPricePer1M(), config.outputPricePer1M(),
                    Map.of("inputTokens", result.inputTokens(), "outputTokens", result.outputTokens()));
            GroupCostLedger.recordSystemCost(gc, "system:stance:" + agentId + ":" + coverage, cost);
            return new StanceResult(agentId, new MemberStance(text, coverage, true, Instant.now()), cost);
        } catch (Exception e) {
            LOGGER.warnf("Group %s: stance summarization failed for member %s (%s) — extracting instead",
                    LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(agentId), e.getMessage());
            return new StanceResult(agentId, extract(entries, coverage, maxChars), 0.0);
        }
    }

    /**
     * The zero-cost producer: the lead sentence of the member's newest
     * stance-bearing contribution, which is their own wording rather than a
     * paraphrase.
     */
    private static MemberStance extract(List<TranscriptEntry> entries, int coverage, int maxChars) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            String text = clean(leadSentence(entries.get(i).content()), maxChars);
            if (text != null) {
                return new MemberStance(text, coverage, false, Instant.now());
            }
        }
        return null;
    }

    /**
     * First sentence of {@code content}, or the whole thing when it has no
     * terminator.
     * <p>
     * Skips a terminator that is part of a decimal ("$1.50 per seat"), an ellipsis,
     * or an abbreviation-shaped single letter ("e.g."), because cutting at one of
     * those produces a fragment rather than a sentence. The test is deliberately
     * shallow: it reads the next non-space character, and a terminator that ends a
     * sentence is followed by whitespace then an upper-case letter, a digit or
     * nothing at all.
     */
    static String leadSentence(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c != '.' && c != '!' && c != '?') {
                continue;
            }
            if (c == '.' && i + 1 < trimmed.length() && Character.isDigit(trimmed.charAt(i + 1))
                    && i > 0 && Character.isDigit(trimmed.charAt(i - 1))) {
                continue;
            }
            if (i + 1 < trimmed.length() && (trimmed.charAt(i + 1) == '.')) {
                continue;
            }
            // "e.g." / "i.e." — a single letter standing alone before the dot.
            if (c == '.' && i >= 1 && Character.isLetter(trimmed.charAt(i - 1))
                    && (i == 1 || !Character.isLetterOrDigit(trimmed.charAt(i - 2)))) {
                continue;
            }
            int next = i + 1;
            while (next < trimmed.length() && Character.isWhitespace(trimmed.charAt(next))) {
                next++;
            }
            if (next >= trimmed.length() || !Character.isLowerCase(trimmed.charAt(next))) {
                return trimmed.substring(0, i + 1);
            }
        }
        return trimmed;
    }

    /**
     * Collapses whitespace, strips the wrapping quotes a model sometimes adds, and
     * truncates to {@code maxChars} on a word boundary with an ellipsis.
     *
     * @return {@code null} when nothing usable is left — callers treat that as "no
     *         stance", never as an empty one
     */
    static String clean(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip().replaceAll("\\s+", " ");
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() <= maxChars) {
            return text;
        }
        // maxChars is the hard cap INCLUDING the ellipsis — a caller that sized a
        // layout to maxChars must not be handed maxChars + 1.
        String cut = text.substring(0, Math.max(1, maxChars - 1));
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > maxChars / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.strip() + "…";
    }

    /** Renders one member's contributions as summarizer input, newest last. */
    private static String renderForSummarizer(List<TranscriptEntry> entries) {
        var sb = new StringBuilder();
        for (var e : entries) {
            if (e.content() == null || e.content().isBlank()) {
                continue;
            }
            sb.append("[").append(e.phaseName() == null ? "" : e.phaseName()).append("] ")
                    .append(e.content().strip()).append("\n\n");
        }
        return sb.toString().strip();
    }

    /**
     * Buckets stance-bearing entries by speaker, preserving first-appearance order
     * so the dashboard's roster does not reshuffle between boundaries.
     */
    private static Map<String, List<TranscriptEntry>> groupBySpeaker(List<TranscriptEntry> transcript) {
        var bySpeaker = new LinkedHashMap<String, List<TranscriptEntry>>();
        for (var entry : transcript) {
            if (entry == null || entry.speakerAgentId() == null || entry.type() == null
                    || !STANCE_BEARING.contains(entry.type())) {
                continue;
            }
            bySpeaker.computeIfAbsent(entry.speakerAgentId(), k -> new ArrayList<>()).add(entry);
        }
        return bySpeaker;
    }
}
