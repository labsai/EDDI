/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.modules.llm.impl.ConversationOutputUtils;
import ai.labs.eddi.modules.llm.impl.SummaryCitations;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in LLM tool for drilling back into summarized conversation turns.
 * <p>
 * When a rolling summary is active, older turns are condensed. This tool allows
 * the LLM to retrieve the original verbatim text of specific turns from the
 * summarized section, enabling detailed recall on demand.
 * <p>
 * Constructed per-invocation by {@code AgentOrchestrator} — NOT a CDI bean.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Vetoed // Instantiated per-invocation by AgentOrchestrator — must NOT be a CDI bean
public class ConversationRecallTool {

    private static final Logger LOGGER = Logger.getLogger(ConversationRecallTool.class);

    /** Matches patterns like "3-7", "turns 3 to 7", "3–7", "turn 3 - turn 7" */
    private static final Pattern TURN_RANGE_PATTERN = Pattern.compile("(?:turns?\\s+)?(\\d+)\\s*[-–—to]+\\s*(?:turns?\\s+)?(\\d+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * How much of {@code query} the turn patterns read. The model writes it, so its
     * length is whatever a prompt injection asks for, and the range pattern
     * backtracks quadratically over a long run of digits and separators: 10.9 s of
     * turn thread at 40,000 characters (security review 2026-10-17, H3). A turn
     * spec is a few characters at the start of the query; nothing past this point
     * can be one.
     */
    static final int MAX_QUERY_LENGTH = 500;

    /** Matches single turn patterns like "turn 5", "5" */
    private static final Pattern SINGLE_TURN_PATTERN = Pattern.compile("(?:turns?\\s+)?(\\d+)\\b", Pattern.CASE_INSENSITIVE);

    // Shallow copy is sufficient because ConversationOutput entries are immutable
    // after creation.
    private final List<ConversationOutput> conversationOutputs;
    private final int summaryThroughStep;
    private final int maxRecallTurns;
    private final UnaryOperator<String> inputScreen;

    /**
     * @param conversationOutputs
     *            immutable copy of the conversation outputs
     * @param summaryThroughStep
     *            step boundary — turns [0, summaryThroughStep) are summarized
     * @param maxRecallTurns
     *            maximum number of turns to return per invocation
     */
    public ConversationRecallTool(List<ConversationOutput> conversationOutputs, int summaryThroughStep, int maxRecallTurns) {
        this(conversationOutputs, summaryThroughStep, maxRecallTurns, null);
    }

    /**
     * @param inputScreen
     *            applied to each returned user text — the task's input guardrails
     *            re-applied, so a turn a guardrail redacted for the model does not
     *            come back verbatim; null returns the stored text. Applied only to
     *            the turns a call returns, not to the whole conversation up front
     */
    public ConversationRecallTool(List<ConversationOutput> conversationOutputs, int summaryThroughStep, int maxRecallTurns,
            UnaryOperator<String> inputScreen) {
        this.conversationOutputs = conversationOutputs;
        this.summaryThroughStep = summaryThroughStep;
        this.maxRecallTurns = maxRecallTurns;
        this.inputScreen = inputScreen;
    }

    /**
     * The patterns capture digits only, so the one way parsing fails is a number
     * beyond {@code int}. Such a turn is later than any turn there is, so it
     * saturates, and the caller's clamp turns it into the last summarized turn.
     */
    static int parseTurn(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    @Tool("Look back at earlier parts of this conversation that have been summarized. "
            + "Use when the conversation summary mentions something you need more detail about, "
            + "or when the user refers to something from earlier in the conversation. "
            + "Specify a turn range like 'turns 3-7' or a single turn like 'turn 5', " + "or describe what you're looking for. "
            + "A citation copied from the summary, like '[turns 3-7, 9]', returns every turn it names.")
    public String recallConversationDetail(
                                           @P("What to look for — describe the topic or question, "
                                                   + "or specify a turn range like 'turns 3-7' or 'turn 5', "
                                                   + "or paste a summary citation like '[turns 3-7, 9]'") String query) {

        if (summaryThroughStep <= 0) {
            return "No conversation summary is active — all turns are already in context.";
        }

        if (query == null) {
            query = "";
        } else if (query.length() > MAX_QUERY_LENGTH) {
            query = query.substring(0, MAX_QUERY_LENGTH);
        }

        LOGGER.debugf("[RECALL] Recalling conversation detail: query='%s', summaryThroughStep=%d", query, summaryThroughStep);

        // A citation copied from the summary — "[turns 4-7, 9]" — names every turn it
        // means, so all of its ranges are returned, not just the first.
        List<String> malformed = new ArrayList<>();
        List<SummaryCitations.Range> cited = SummaryCitations.citations(query, malformed);
        if (!cited.isEmpty()) {
            return recallCited(cited);
        }
        if (!malformed.isEmpty()) {
            // A bracketed citation that names no turn ("[turn -1]", "[turn abc]") is no
            // source — not a free-text query to fall back on, which would pick a turn.
            LOGGER.debugf("[RECALL] Malformed citation ignored (%d); summarized turns 0-%d", malformed.size(), summaryThroughStep - 1);
            return "None of the cited turns is among the summarized turns 0-" + (summaryThroughStep - 1) + ".";
        }

        // Try to parse a turn range from the query
        Matcher rangeMatcher = TURN_RANGE_PATTERN.matcher(query);
        int fromTurn;
        int toTurn;

        // Turn N is conversation step N: turn 0 is the opening (CONVERSATION_START)
        // step, turn 1 the user's first message. Labels used to be step + 1, so the
        // user's first message was "turn 2" — the model and the user never agreed on
        // what "turn 3" meant. Ranges are inclusive; toTurn is kept exclusive below.
        if (rangeMatcher.find()) {
            int first = parseTurn(rangeMatcher.group(1));
            int last = parseTurn(rangeMatcher.group(2));
            if (last < first) {
                int swap = first;
                first = last;
                last = swap;
            }
            fromTurn = Math.min(first, summaryThroughStep - 1);
            toTurn = Math.min(last, summaryThroughStep - 1) + 1;
        } else {
            // Try single-turn pattern: "turn 5" or just "5"
            Matcher singleMatcher = SINGLE_TURN_PATTERN.matcher(query);
            if (singleMatcher.find()) {
                int turn = parseTurn(singleMatcher.group(1));
                fromTurn = Math.min(turn, summaryThroughStep - 1);
                toTurn = fromTurn + 1;
            } else {
                // No range or turn specified — return the last N summarized turns
                toTurn = summaryThroughStep;
                fromTurn = Math.max(0, toTurn - maxRecallTurns);
            }
        }

        // Enforce max recall limit
        if (toTurn - fromTurn > maxRecallTurns) {
            toTurn = fromTurn + maxRecallTurns;
        }

        // Render the requested turns
        var sb = new StringBuilder();
        sb.append("**Recalled conversation turns ").append(fromTurn).append("-").append(toTurn - 1).append(":**\n\n");

        for (int i = fromTurn; i < toTurn && i < conversationOutputs.size(); i++) {
            appendTurn(sb, i);
        }

        if (toTurn < summaryThroughStep) {
            sb.append("_... ").append(summaryThroughStep - toTurn).append(" more summarized turns available. ")
                    .append("Call again with a different range to see them._");
        }

        return sb.toString();
    }

    /**
     * The turns of every cited range, in order and each once, clamped to the
     * summarized turns like a plain range (a range wholly past them names no turn
     * and is ignored, never clamped onto the last one) and capped at
     * {@code maxRecallTurns}.
     */
    private String recallCited(List<SummaryCitations.Range> cited) {
        int last = summaryThroughStep - 1;
        TreeSet<Integer> turns = new TreeSet<>();
        int skipped = 0;
        for (SummaryCitations.Range range : cited) {
            if (range.from() > last) {
                // Not a summarized turn: no such source. Clamping it would hand back the
                // last summarized turn under a citation that does not name it.
                LOGGER.debugf("[RECALL] Cited turns %s are beyond the summarized turns 0-%d; ignored", range, last);
                continue;
            }
            int from = range.from();
            int to = Math.min(range.to(), last);
            for (int i = from; i <= to; i++) {
                if (turns.contains(i)) {
                    continue;
                }
                if (turns.size() >= maxRecallTurns) {
                    skipped++;
                    continue;
                }
                turns.add(i);
            }
        }

        if (turns.isEmpty()) {
            return "None of the cited turns is among the summarized turns 0-" + last + ".";
        }
        var sb = new StringBuilder();
        sb.append("**Recalled conversation turns ").append(describe(turns)).append(":**\n\n");
        for (int i : turns) {
            if (i < conversationOutputs.size()) {
                appendTurn(sb, i);
            }
        }
        if (skipped > 0) {
            sb.append("_... ").append(skipped).append(" more cited turns were not returned (at most ").append(maxRecallTurns)
                    .append(" per call). Call again with the rest of the citation._");
        }
        return sb.toString();
    }

    /** {@code 4-7, 9} for the turns 4, 5, 6, 7 and 9. */
    private static String describe(TreeSet<Integer> turns) {
        var sb = new StringBuilder();
        Integer start = null;
        Integer previous = null;
        for (int turn : turns) {
            if (start == null) {
                start = turn;
            } else if (turn != previous + 1) {
                appendRange(sb, start, previous);
                start = turn;
            }
            previous = turn;
        }
        if (start != null) {
            appendRange(sb, start, previous);
        }
        return sb.toString();
    }

    private static void appendRange(StringBuilder sb, int from, int to) {
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        sb.append(from);
        if (to != from) {
            sb.append('-').append(to);
        }
    }

    private void appendTurn(StringBuilder sb, int i) {
        var output = conversationOutputs.get(i);
        var input = output.get("input", String.class);
        var outputText = ConversationOutputUtils.extractOutputText(output);
        if (input != null && !input.isBlank() && inputScreen != null) {
            input = inputScreen.apply(input);
        }

        if (input != null && !input.isBlank()) {
            sb.append("**Turn ").append(i).append(" — User:** ").append(input).append('\n');
        }
        if (outputText != null && !outputText.isEmpty()) {
            sb.append("**Turn ").append(i).append(" — Agent:** ").append(outputText).append('\n');
        }
        sb.append('\n');
    }
}
