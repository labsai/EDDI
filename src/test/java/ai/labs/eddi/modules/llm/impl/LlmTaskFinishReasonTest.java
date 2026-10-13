/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The abnormal-finish signal LlmTask leaves on the step for the
 * OpenAI-compatible adapter's {@code finish_reason}.
 */
class LlmTaskFinishReasonTest {

    private static Map<String, Object> metadata(String key, Object value) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(key, value);
        return metadata;
    }

    @Test
    void truncationWarning_isLength() {
        assertEquals("length", LlmTask.abnormalFinishReason(metadata("warning", "truncated")));
    }

    @Test
    void rawLengthFinishReason_isLength() {
        assertEquals("length", LlmTask.abnormalFinishReason(metadata("finishReason", "LENGTH")));
    }

    @Test
    void contentFilter_isContentFilter() {
        assertEquals("content_filter", LlmTask.abnormalFinishReason(metadata("warning", "content_filter")));
        assertEquals("content_filter", LlmTask.abnormalFinishReason(metadata("finishReason", "CONTENT_FILTER")));
    }

    @Test
    void aNormalStop_writesNothing() {
        assertNull(LlmTask.abnormalFinishReason(metadata("finishReason", "STOP")));
        assertNull(LlmTask.abnormalFinishReason(new HashMap<>()));
        assertNull(LlmTask.abnormalFinishReason(null));
    }
}
