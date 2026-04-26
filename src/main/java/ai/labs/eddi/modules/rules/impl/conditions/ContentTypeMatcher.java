/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.modules.rules.impl.Rule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENTS;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.*;

/**
 * Behavior rule condition that matches based on attachment MIME types in the
 * current conversation step.
 * <p>
 * Supports wildcard patterns: "image/*" matches "image/png", "image/jpeg", etc.
 * <p>
 * Config keys:
 * <ul>
 * <li>{@code mimeType} — required MIME type pattern (e.g., "image/*",
 * "application/pdf")</li>
 * <li>{@code minCount} — minimum number of matching attachments (default:
 * 1)</li>
 * </ul>
 *
 * Example behavior.json:
 *
 * <pre>
 * {
 *   "type": "contentTypeMatcher",
 *   "config": {
 *     "mimeType": "image/*",
 *     "minCount": "1"
 *   }
 * }
 * </pre>
 *
 * @since 6.0.0
 */
public class ContentTypeMatcher implements IRuleCondition {
    public static final String ID = "contentTypeMatcher";
    private static final String KEY_MIME_TYPE = "mimeType";
    private static final String KEY_MIN_COUNT = "minCount";

    private String mimeType;
    private int minCount = 1;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();
        if (mimeType != null) {
            configs.put(KEY_MIME_TYPE, mimeType);
        }
        configs.put(KEY_MIN_COUNT, String.valueOf(minCount));
        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null) {
            mimeType = configs.get(KEY_MIME_TYPE);
            if (configs.containsKey(KEY_MIN_COUNT)) {
                try {
                    minCount = Integer.parseInt(configs.get(KEY_MIN_COUNT));
                    if (minCount < 1) {
                        minCount = 1;
                    }
                } catch (NumberFormatException e) {
                    minCount = 1;
                }
            }
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) {
        if (mimeType == null || mimeType.isBlank()) {
            return FAIL;
        }

        IData<List<?>> data = memory.getCurrentStep().getLatestData(ATTACHMENTS);
        if (data == null || data.getResult() == null) {
            return FAIL;
        }

        List<?> rawAttachments = data.getResult();
        long matchCount = rawAttachments.stream()
                .filter(obj -> obj instanceof Attachment)
                .map(obj -> (Attachment) obj)
                .filter(att -> att.matchesMimeType(mimeType))
                .count();

        return matchCount >= minCount ? SUCCESS : FAIL;
    }

    @Override
    public IRuleCondition clone() {
        ContentTypeMatcher clone = new ContentTypeMatcher();
        clone.setConfigs(this.getConfigs());
        return clone;
    }
}
