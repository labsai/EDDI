/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
import ai.labs.eddi.engine.memory.model.ConversationOutput;

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

    /**
     * Multiple output items become one line of history, so they are joined with a
     * space rather than the newline {@link ConversationOutputExtractor} uses for
     * its own callers.
     */
    private static final String ITEM_DELIMITER = " ";

    private ConversationOutputUtils() {
        // utility class
    }

    /**
     * Extract the agent's output text from a ConversationOutput map.
     * <p>
     * Every item in the output list is inspected, not just the first: a turn gated
     * by HITL starts its list with a plain String, and reading the list's shape
     * from element zero used to discard such turns wholesale — removing them from
     * the agent's own chat history, the rolling summary, the recall tool and the
     * REST log. Multiple text entries are joined with a single space.
     *
     * @param output
     *            the conversation output to extract from
     * @return the joined text, or null if no output is present
     */
    public static String extractOutputText(ConversationOutput output) {
        return ConversationOutputExtractor.extractText(output, ITEM_DELIMITER);
    }
}
