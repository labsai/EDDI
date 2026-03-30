package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
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

    private final List<ConversationOutput> conversationOutputs;
    private final int summaryThroughStep;
    private final int maxRecallTurns;

    /**
     * @param conversationOutputs
     *            immutable copy of the conversation outputs
     * @param summaryThroughStep
     *            step boundary — turns [0, summaryThroughStep) are summarized
     * @param maxRecallTurns
     *            maximum number of turns to return per invocation
     */
    public ConversationRecallTool(List<ConversationOutput> conversationOutputs, int summaryThroughStep, int maxRecallTurns) {
        this.conversationOutputs = conversationOutputs;
        this.summaryThroughStep = summaryThroughStep;
        this.maxRecallTurns = maxRecallTurns;
    }

    @Tool("Look back at earlier parts of this conversation that have been summarized. "
            + "Use when the conversation summary mentions something you need more detail about, "
            + "or when the user refers to something from earlier in the conversation. "
            + "Specify a turn range like 'turns 3-7' to get those specific turns, " + "or describe what you're looking for.")
    public String recallConversationDetail(
            @P("What to look for — describe the topic or question, " + "or specify a turn range like 'turns 3-7'") String query) {

        if (summaryThroughStep <= 0) {
            return "No conversation summary is active — all turns are already in context.";
        }

        LOGGER.debugf("[RECALL] Recalling conversation detail: query='%s', summaryThroughStep=%d", query, summaryThroughStep);

        // Try to parse a turn range from the query
        Matcher matcher = TURN_RANGE_PATTERN.matcher(query);
        int fromTurn, toTurn;

        if (matcher.find()) {
            fromTurn = Integer.parseInt(matcher.group(1));
            toTurn = Integer.parseInt(matcher.group(2));

            // Convert from 1-indexed (human) to 0-indexed (internal)
            fromTurn = Math.max(1, fromTurn) - 1;
            toTurn = Math.min(toTurn, summaryThroughStep);

            // Ensure range is within summarized section
            fromTurn = Math.min(fromTurn, summaryThroughStep - 1);
        } else {
            // No range specified — return the last N summarized turns
            toTurn = summaryThroughStep;
            fromTurn = Math.max(0, toTurn - maxRecallTurns);
        }

        // Enforce max recall limit
        if (toTurn - fromTurn > maxRecallTurns) {
            toTurn = fromTurn + maxRecallTurns;
        }

        // Render the requested turns
        var sb = new StringBuilder();
        sb.append("**Recalled conversation turns ").append(fromTurn + 1).append("-").append(toTurn).append(":**\n\n");

        for (int i = fromTurn; i < toTurn && i < conversationOutputs.size(); i++) {
            var output = conversationOutputs.get(i);
            var input = output.get("input", String.class);
            var outputText = extractOutputText(output);

            if (input != null) {
                sb.append("**Turn ").append(i + 1).append(" — User:** ").append(input).append('\n');
            }
            if (outputText != null && !outputText.isEmpty()) {
                sb.append("**Turn ").append(i + 1).append(" — Agent:** ").append(outputText).append('\n');
            }
            sb.append('\n');
        }

        if (toTurn < summaryThroughStep) {
            sb.append("_... ").append(summaryThroughStep - toTurn).append(" more summarized turns available. ")
                    .append("Call again with a different range to see them._");
        }

        return sb.toString();
    }

    /**
     * Extract the agent's output text from a ConversationOutput map.
     */
    @SuppressWarnings("unchecked")
    private static String extractOutputText(ConversationOutput output) {
        Object outputObj = output.get("output");
        if (outputObj instanceof List<?> outputList && !outputList.isEmpty()) {
            if (outputList.getFirst() instanceof Map) {
                var mapList = (List<Map<String, Object>>) outputList;
                return mapList.stream().filter(m -> m.get("text") != null).map(m -> m.get("text").toString()).reduce((a, b) -> a + " " + b)
                        .orElse("");
            }
        }
        return null;
    }
}
