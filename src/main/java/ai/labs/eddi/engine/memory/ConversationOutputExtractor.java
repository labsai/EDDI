/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.utils.RuntimeUtilities;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared utility for extracting human-readable text from a conversation memory
 * snapshot. Consolidates logic that was duplicated across
 * {@code GroupConversationService}, {@code CreateSubAgentTool}, and
 * {@code ConverseWithAgentTool}.
 *
 * <p>
 * Handles multiple output formats:
 * <ol>
 * <li>Nested {@code output} array — items may be plain Strings,
 * {@link TextOutputItem} POJOs, or Maps with a {@code text} key</li>
 * <li>Plain {@code output} value — String or Map with {@code text} key</li>
 * <li>Flat keys like {@code output:text:*} — legacy format</li>
 * <li>{@code reply} key — String or List of Strings</li>
 * </ol>
 * Returns {@code null} when no recognizable text is found (e.g., the output map
 * contains only pipeline metadata like actions, context, or expressions).
 *
 * @since 6.0.0
 */
public final class ConversationOutputExtractor {

    private static final Logger LOGGER = Logger.getLogger(ConversationOutputExtractor.class);

    private ConversationOutputExtractor() {
        // Utility class
    }

    /**
     * Extracts the human-readable response text from the last conversation output
     * in a snapshot.
     *
     * @param snapshot
     *            the conversation memory snapshot (may be null)
     * @return the extracted text, or {@code null} if no meaningful output is found
     *         (e.g., pipeline metadata only)
     */
    public static String extractResponse(SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == null || RuntimeUtilities.isNullOrEmpty(snapshot.getConversationOutputs())) {
            return null;
        }
        List<ConversationOutput> outputs = snapshot.getConversationOutputs();
        ConversationOutput lastOutput = outputs.get(outputs.size() - 1);
        if (lastOutput == null) {
            return null;
        }

        var texts = new ArrayList<String>();

        // Format 1: Nested "output" array — may contain TextOutputItem POJOs or Maps
        Object outputValue = lastOutput.get("output");
        if (outputValue instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof String s && hasText(s)) {
                    texts.add(s);
                } else if (item instanceof TextOutputItem toi && hasText(toi.getText())) {
                    texts.add(toi.getText());
                } else if (item instanceof Map<?, ?> map
                        && map.get("text") instanceof String s && hasText(s)) {
                    texts.add(s);
                }
            }
            if (!texts.isEmpty()) {
                return String.join("\n", texts);
            }
        } else if (outputValue instanceof String s && hasText(s)) {
            // Plain string written via addConversationOutputString("output", ...)
            return s;
        } else if (outputValue instanceof Map<?, ?> map
                && map.get("text") instanceof String s && hasText(s)) {
            return s;
        }

        // Format 2: Flat keys like "output:text:*"
        for (var entry : lastOutput.entrySet()) {
            if (entry.getKey() instanceof String key && key.startsWith("output:text:")) {
                Object val = entry.getValue();
                if (val instanceof String s && hasText(s)) {
                    texts.add(s);
                } else if (val instanceof List<?> list) {
                    for (var item : list) {
                        if (item instanceof String s && hasText(s))
                            texts.add(s);
                        else if (item instanceof Map<?, ?> map
                                && map.get("text") instanceof String s && hasText(s))
                            texts.add(s);
                    }
                } else if (val instanceof Map<?, ?> map
                        && map.get("text") instanceof String s && hasText(s)) {
                    texts.add(s);
                }
            }
        }

        if (!texts.isEmpty()) {
            return String.join("\n", texts);
        }

        // Format 3: "reply" key — used by some task extensions
        Object replyValue = lastOutput.get("reply");
        if (replyValue instanceof String s && hasText(s)) {
            return s;
        } else if (replyValue instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof String s && hasText(s))
                    texts.add(s);
            }
            if (!texts.isEmpty()) {
                return String.join("\n", texts);
            }
        }

        // No recognizable text found in any standard format.
        // The output map may only contain pipeline metadata
        // (e.g. "actions", "input", "context", or "output" with null items).
        // Return null to avoid serializing raw metadata as a group
        // discussion response — the toString() fallback produced
        // unreadable Java map dumps in the UI.
        LOGGER.debugf("No extractable text from conversation output keys: %s",
                lastOutput.keySet());
        return null;
    }

    /**
     * Returns {@code true} if the string is non-null, non-empty, and not
     * purely whitespace. Combines {@link RuntimeUtilities#isNullOrEmpty}
     * with an additional blank check to avoid treating whitespace-only
     * content as meaningful agent output.
     */
    private static boolean hasText(String s) {
        return !RuntimeUtilities.isNullOrEmpty(s) && !s.isBlank();
    }
}
