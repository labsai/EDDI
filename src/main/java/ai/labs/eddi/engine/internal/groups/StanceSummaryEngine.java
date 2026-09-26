/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
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

    /**
     * Stance-bearing types whose content is a <b>JSON contract</b> rather than
     * prose — a ballot ({@code VoteTallyEngine}), a bid ({@code TaskBidEngine}),
     * harvested lessons ({@code RetroEngine}), a task plan and its results.
     * <p>
     * They stay in {@link #STANCE_BEARING} because they are legitimate input for
     * the summarizer, which can read them. They are excluded from
     * <em>extraction</em>, because the lead "sentence" of a ballot is
     * <code>{"choice":"pgvector","confidence":0.8,"reasoning":"It is cheaper.</code>
     * — displayed to the reader as that member's own words. Worse, being the newest
     * entry it would replace the member's real prose position after every VOTE or
     * RETRO phase.
     */
    private static final Set<TranscriptEntryType> JSON_CONTRACT = EnumSet.of(
            TranscriptEntryType.VOTE, TranscriptEntryType.BID, TranscriptEntryType.RETRO,
            TranscriptEntryType.PLAN, TranscriptEntryType.TASK_RESULT, TranscriptEntryType.VERIFICATION);

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
     * @param textChanged
     *            whether the stance's text actually differs from the one stored
     *            before. A result can be reported with {@code false} here purely
     *            because it cost money: {@code stance_updated} keys off this,
     *            {@code cost_updated} keys off {@link #cost()}, and conflating them
     *            made every paid re-summary emit a stance change that had not
     *            happened
     */
    public record StanceResult(String agentId, MemberStance stance, double cost, boolean textChanged) {
    }

    /**
     * Recomputes stances for every member with something new to say, and writes
     * them onto {@code gc}.
     * <p>
     * Call at phase boundaries. A member whose stored stance already covers all of
     * their own contributions is skipped, so a member who stayed silent through a
     * phase costs nothing — that skip is the difference between one summarizer call
     * per member per discussion and one per member per phase.
     *
     * @param config
     *            may be {@code null}, which means extraction for everyone (the
     *            documented default, not a degraded mode)
     * @param protocol
     *            the discussion's protocol, for the I1 cost ceiling. A blown budget
     *            downgrades the LLM path to extraction rather than stopping the
     *            phase — declining optional work is not the same event as running
     *            out of budget mid-phase
     * @param summarizationService
     *            may be {@code null} (not available), which also means extraction
     * @return one result per member whose stance changed, in stable member order,
     *         for the caller to emit as {@code stance_updated} events. Never
     *         {@code null}
     */
    public static List<StanceResult> updateStances(GroupConversation gc, StanceSummaryConfig config,
                                                   ProtocolConfig protocol, SummarizationService summarizationService) {
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
        // I1: the summarizer is OPTIONAL spend, so it obeys the discussion's
        // ceiling the same way the I9 window summarizer, the convergence judge
        // and the dissent round do. Without this gate the boundary runs one
        // priced call per member AFTER the budget is gone and before the next
        // phase's pre-wave check can fire. wouldExceedCeiling (not
        // enforceCeiling) is the right question: declining optional work is not
        // the same event as a phase running out of budget, and must not append a
        // SKIPPED entry or set the outcome flag.
        // Re-checked per member inside the loop, not once up front: each call
        // adds to the ledger, so a boundary that starts just under the ceiling
        // would otherwise run the LLM for every remaining member after the
        // first one blew it.
        boolean summarizerAvailable = config != null && config.hasSummarizer() && summarizationService != null;

        var results = new ArrayList<StanceResult>();
        for (var contribution : groupBySpeaker(transcript).entrySet()) {
            String agentId = contribution.getKey();
            List<TranscriptEntry> entries = contribution.getValue();

            MemberStance existing = gc.getMemberStances().get(agentId);
            // Coverage counts THIS MEMBER's own contributions, not the transcript
            // length. Keying it to the transcript meant any member speaking
            // invalidated every member's stance, so a 6-member discussion paid
            // for 6 summarizer calls at every boundary and the documented
            // "a member who stayed silent costs nothing" was never true.
            int covered = entries.size();
            if (existing != null && existing.coveredContributions() >= covered) {
                continue;
            }

            boolean useLlm = summarizerAvailable && !GroupCostLedger.wouldExceedCeiling(gc, protocol);
            StanceResult result = useLlm
                    ? summarize(gc, agentId, entries, covered, config, summarizationService, maxChars)
                    : new StanceResult(agentId, extract(entries, covered, maxChars), 0.0, false);
            if (result == null || result.stance() == null) {
                continue;
            }
            // Unchanged text still counts as covered — storing the refreshed
            // count is what stops the next boundary paying to learn the same
            // thing again.
            boolean textChanged = existing == null || !result.stance().text().equals(existing.text());
            gc.putMemberStance(agentId, result.stance());
            // Returned when the text changed OR the call cost something, and the
            // result says which. The two are genuinely independent: a re-summary
            // landing on the same wording still bills the ledger (so it needs a
            // cost_updated frame, or the live total drifts below it), while
            // `stance_updated` must fire only on a real change, as its contract
            // says. Collapsing them made every paid re-summary emit a spurious
            // stance_updated.
            if (textChanged || result.cost() > 0.0) {
                results.add(new StanceResult(result.agentId(), result.stance(), result.cost(), textChanged));
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
                return new StanceResult(agentId, extract(entries, coverage, maxChars), 0.0, false);
            }
            double cost = TokenPricing.cost(config.inputPricePer1M(), config.outputPricePer1M(),
                    Map.of("inputTokens", result.inputTokens(), "outputTokens", result.outputTokens()));
            GroupCostLedger.recordSystemCost(gc, "system:stance:" + agentId + ":" + coverage, cost);
            return new StanceResult(agentId, new MemberStance(text, coverage, true, Instant.now()), cost, false);
        } catch (Exception e) {
            LOGGER.warnf("Group %s: stance summarization failed for member %s (%s) — extracting instead",
                    LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(agentId), e.getMessage());
            return new StanceResult(agentId, extract(entries, coverage, maxChars), 0.0, false);
        }
    }

    /**
     * The zero-cost producer: the lead sentence of the member's newest
     * stance-bearing contribution, which is their own wording rather than a
     * paraphrase.
     */
    private static MemberStance extract(List<TranscriptEntry> entries, int coverage, int maxChars) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            // Skip the JSON-contract types and fall through to the newest prose
            // entry, rather than quoting a ballot's opening brace at the reader.
            if (entry.type() != null && JSON_CONTRACT.contains(entry.type())) {
                continue;
            }
            String text = clean(leadSentence(entry.content()), maxChars);
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
            // An abbreviation ("e.g.", "Dr.") — a lone letter before the dot AND
            // a lower-case continuation after it. The second half matters:
            // without it "Weigh option B. Option A is worse." cut nothing,
            // because the standalone B read as an abbreviation.
            if (c == '.' && i >= 1 && Character.isLetter(trimmed.charAt(i - 1))
                    && (i == 1 || !Character.isLetterOrDigit(trimmed.charAt(i - 2)))
                    && startsLowerCase(trimmed, i + 1)) {
                continue;
            }
            // A candidate sentence with no letter in it is a list marker, not a
            // sentence. "1. We should adopt pgvector." would otherwise be shown
            // to the reader as that member's own words, reading just "1." — and
            // LLM replies open with a numbered list constantly.
            if (!hasLetter(trimmed, 0, i)) {
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
     * Whether the next non-space character at or after {@code from} is lower case.
     */
    private static boolean startsLowerCase(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && Character.isLowerCase(text.charAt(i));
    }

    private static boolean hasLetter(String text, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
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
        // layout to maxChars must not be handed maxChars + 1. At a cap of 1
        // there is no room for both a character and the ellipsis, so the
        // ellipsis alone is the only truncation that honours it.
        if (maxChars == 1) {
            return "…";
        }
        String cut = text.substring(0, maxChars - 1);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > maxChars / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.strip() + "…";
    }

    /**
     * G3: the most one contribution contributes to summarizer input. A member's
     * turn can be arbitrarily long (a task result, a pasted document); the stance
     * is one sentence, and its lead paragraphs carry the position.
     */
    static final int MAX_SUMMARIZER_ENTRY_CHARS = 2_000;

    /**
     * G3: the whole summarizer input for one member. It used to be every
     * contribution the member made this discussion, concatenated — growing with the
     * discussion, re-sent at every boundary the member spoke at, and eventually
     * past the summarizer's context window, where every call fails (and bills). The
     * newest contributions are kept: a stance is where the member stands NOW.
     */
    static final int MAX_SUMMARIZER_INPUT_CHARS = 8_000;

    /**
     * Renders one member's contributions as summarizer input, newest last, bounded
     * by {@link #MAX_SUMMARIZER_INPUT_CHARS} (oldest dropped first, with a marker
     * saying how many) and {@link #MAX_SUMMARIZER_ENTRY_CHARS} per contribution.
     */
    static String renderForSummarizer(List<TranscriptEntry> entries) {
        var blocks = new ArrayList<String>();
        int used = 0;
        int omitted = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            var e = entries.get(i);
            if (e.content() == null || e.content().isBlank()) {
                continue;
            }
            if (used >= MAX_SUMMARIZER_INPUT_CHARS) {
                omitted++;
                continue;
            }
            String content = e.content().strip();
            if (content.length() > MAX_SUMMARIZER_ENTRY_CHARS) {
                content = content.substring(0, MAX_SUMMARIZER_ENTRY_CHARS) + " […]";
            }
            String block = "[" + (e.phaseName() == null ? "" : e.phaseName()) + "] " + content;
            int room = MAX_SUMMARIZER_INPUT_CHARS - used;
            if (block.length() > room) {
                if (room < 200) {
                    // Too little left for a meaningful slice — count it as omitted.
                    omitted++;
                    used = MAX_SUMMARIZER_INPUT_CHARS;
                    continue;
                }
                block = block.substring(0, room) + " […]";
            }
            blocks.add(0, block);
            used += block.length();
        }
        var sb = new StringBuilder();
        if (omitted > 0) {
            sb.append("[").append(omitted).append(" earlier contribution(s) omitted]\n\n");
        }
        for (String block : blocks) {
            sb.append(block).append("\n\n");
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
