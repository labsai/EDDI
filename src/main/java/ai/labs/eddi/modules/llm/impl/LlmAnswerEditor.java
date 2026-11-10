/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stores an approver's edit in place of the LLM task's draft
 * ({@code hitlConfig.review.allowAnswerEdits}): the task's answer data and,
 * when the answer was added to the output, the one output item it wrote.
 * Nothing after it is cut back and nothing runs again — the edit is the answer,
 * not a new turn. The text is data: it is never template source, never a
 * property, context value or action.
 *
 * @since 6.7.0
 */
final class LlmAnswerEditor {

    /**
     * The answer data key prefix, as LlmTask writes it
     * ({@code langchain:<type>:<taskId>}).
     */
    private static final String KEY_LANGCHAIN = "langchain";

    private LlmAnswerEditor() {
    }

    /**
     * The task the candidate's answer came from, or null when the configuration
     * changed since the pause (index out of range or a different task id).
     */
    static Task taskOf(LlmConfiguration config, HitlRevisionCandidate candidate) {
        var tasks = config != null ? config.tasks() : null;
        int index = candidate.getLlmTaskIndex();
        Task task = tasks != null && index >= 0 && index < tasks.size() ? tasks.get(index) : null;
        return task != null && Objects.equals(task.getId(), candidate.getLlmTaskId()) ? task : null;
    }

    /** Replaces the draft with {@code text} in the step. */
    static void apply(IWritableConversationStep step, Task task, HitlRevisionCandidate candidate, String text, IDataFactory dataFactory) {
        step.storeData(dataFactory.createData(KEY_LANGCHAIN + ":" + task.getType() + ":" + task.getId(), text));
        if (!candidate.isAddedToOutput()) {
            return;
        }
        step.storeData(dataFactory.createData(LlmTask.LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.getType(), text));
        replaceOutputItem(step, candidate.getOutputMark(), text);
    }

    /**
     * Replaces the {@code output} item at {@code index} — the one the draft wrote —
     * keeping its shape (an item, or its stored map form) and every other item.
     */
    static void replaceOutputItem(IWritableConversationStep step, int index, String text) {
        var output = step.getConversationOutput();
        if (index < 0 || output == null || !(output.get(LlmTask.MEMORY_OUTPUT_IDENTIFIER) instanceof List<?> items) || index >= items.size()) {
            return;
        }
        Object current = items.get(index);
        Object replacement;
        if (current instanceof TextOutputItem item) {
            replacement = new TextOutputItem(text, item.getDelay());
        } else if (current instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<Object, Object>(map);
            copy.put("text", text);
            replacement = copy;
        } else {
            replacement = new TextOutputItem(text, 0);
        }
        List<Object> copy = new ArrayList<>(items);
        copy.set(index, replacement);
        output.put(LlmTask.MEMORY_OUTPUT_IDENTIFIER, copy);
    }
}
