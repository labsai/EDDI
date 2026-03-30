package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.model.ConversationOutput;

import java.util.List;
import java.util.Map;

/**
 * Shared utility for extracting text from conversation outputs.
 * <p>
 * Consolidates output-text extraction logic that was previously duplicated in
 * {@code ConversationSummarizer}, {@code ConversationRecallTool}, and
 * {@code ConversationHistoryBuilder}.
 *
 * @author ginccc
 * @since 6.0.0
 */
public final class ConversationOutputUtils {

    private ConversationOutputUtils() {
        // utility class
    }

    /**
     * Extract the agent's output text from a ConversationOutput map.
     * <p>
     * Handles the standard output format: a List of Maps with "text" keys. Multiple
     * text entries are joined with a single space.
     *
     * @param output
     *            the conversation output to extract from
     * @return the joined text, or null if no output is present
     */
    @SuppressWarnings("unchecked")
    public static String extractOutputText(ConversationOutput output) {
        Object outputObj = output.get("output");
        if (outputObj instanceof List<?> outputList && !outputList.isEmpty()) {
            if (outputList.getFirst() instanceof Map) {
                var mapList = (List<Map<String, Object>>) outputList;
                String result = mapList.stream().filter(m -> m.get("text") != null).map(m -> m.get("text").toString())
                        .collect(java.util.stream.Collectors.joining(" "));
                return result.isEmpty() ? null : result;
            }
        }
        return null;
    }
}
