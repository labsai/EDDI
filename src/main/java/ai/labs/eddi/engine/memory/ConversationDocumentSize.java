/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * An estimate of the stored size of a conversation document, in bytes, taken
 * without serialising it.
 * <p>
 * The estimate walks the snapshot and counts what both backends store for it:
 * every string as UTF-8, every map key, a fixed few bytes per number, flag,
 * date and container (BSON's type tag, field name terminator and length prefix;
 * JSON's quotes and separators). It errs towards too large rather than too
 * small, so a limit set below the database's own (MongoDB: 16 MB) is hit first.
 * A value of a type the walk does not know is measured by serialising only that
 * value to a counting sink; the conversation model has few of them (a pending
 * tool-call batch, a rendered approval context).
 * <p>
 * Pure and thread-safe; never throws.
 */
public final class ConversationDocumentSize {

    /**
     * Type tag, field-name terminator, length prefix: a conservative per-entry
     * cost.
     */
    private static final int ENTRY_OVERHEAD = 8;
    /** A number or a boolean: BSON stores at most 8 bytes for each. */
    private static final int SCALAR_SIZE = 8;
    /**
     * A date or time: stored as an ISO-8601 string
     * ({@code 2026-11-18T11:47:19.620+00:00}).
     */
    private static final int DATE_SIZE = 40;
    /**
     * The fixed part of one stored step result: its field names ({@code key},
     * {@code result}, {@code possibleResults}, {@code timestamp},
     * {@code originWorkflowId}, {@code committed}, {@code public}), their type tags
     * and terminators, the timestamp string, the two booleans, the string length
     * prefixes, the embedded document's own header and its array index name.
     * Calibrated against the BSON the MongoDB store writes
     * ({@code ConversationDocumentSizeTest}).
     */
    private static final int RESULT_OVERHEAD = 112;
    /**
     * A step's own field names ({@code conversationOutput}, {@code workflows}) and
     * headers.
     */
    private static final int STEP_OVERHEAD = 64;
    /** A workflow run's field name ({@code lifecycleTasks}) and header. */
    private static final int WORKFLOW_OVERHEAD = 32;
    /** Fixed fields of the document (ids, revisions, state, bookmarks). */
    private static final int DOCUMENT_OVERHEAD = 1024;
    /** Container nesting the walk follows before it stops counting. */
    private static final int MAX_DEPTH = 64;

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private ConversationDocumentSize() {
    }

    /**
     * The estimated stored size of {@code snapshot}, in bytes; {@code 0} when it
     * cannot be estimated (the write is then left to the database's own limit).
     */
    public static long estimate(ConversationMemorySnapshot snapshot) {
        if (snapshot == null) {
            return 0;
        }
        try {
            return walk(snapshot);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long walk(ConversationMemorySnapshot snapshot) {
        Walk walk = new Walk();
        long size = DOCUMENT_OVERHEAD;
        size += walk.value(snapshot.getConversationId(), 0);
        size += walk.value(snapshot.getAgentId(), 0);
        size += walk.value(snapshot.getUserId(), 0);
        size += walk.value(snapshot.getEndReason(), 0);
        size += walk.value(snapshot.getHitlPauseReason(), 0);
        size += walk.value(snapshot.getHitlPendingToolCalls(), 0);
        size += walk.value(snapshot.getHitlApprovalContext(), 0);
        size += walk.value(snapshot.getConversationOutputs(), 0);
        size += walk.value(snapshot.getPendingLongTermWrites(), 0);
        Map<String, Property> properties = snapshot.getConversationProperties();
        if (properties != null) {
            for (Map.Entry<String, Property> entry : properties.entrySet()) {
                size += ENTRY_OVERHEAD + utf8Length(entry.getKey()) + walk.property(entry.getValue());
            }
        }
        size += walk.steps(snapshot.getConversationSteps());
        size += walk.steps(snapshot.getRedoCache());
        return size;
    }

    /** UTF-8 length of {@code text}, counted without encoding it. */
    static long utf8Length(CharSequence text) {
        if (text == null) {
            return 0;
        }
        long bytes = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isSurrogate(c)) {
                // Two surrogates encode one 4-byte code point; a lone one is replaced.
                bytes += 2;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * One estimate's walk. A value referenced from two places is stored twice and
     * counted twice; only the containers on the current path are remembered, so a
     * cycle ends the walk instead of overflowing it.
     */
    private static final class Walk {
        private final Set<Object> onPath = Collections.newSetFromMap(new IdentityHashMap<>());

        long steps(Collection<ConversationStepSnapshot> steps) {
            if (steps == null) {
                return 0;
            }
            long size = ENTRY_OVERHEAD;
            for (ConversationStepSnapshot step : steps) {
                if (step == null) {
                    continue;
                }
                size += STEP_OVERHEAD + value(step.getConversationOutput(), 1);
                if (step.getWorkflows() == null) {
                    continue;
                }
                for (WorkflowRunSnapshot workflow : step.getWorkflows()) {
                    if (workflow == null || workflow.getLifecycleTasks() == null) {
                        continue;
                    }
                    size += WORKFLOW_OVERHEAD;
                    for (ResultSnapshot result : workflow.getLifecycleTasks()) {
                        if (result == null) {
                            continue;
                        }
                        size += RESULT_OVERHEAD + utf8Length(result.getKey()) + utf8Length(result.getOriginWorkflowId())
                                + value(result.getResult(), 1) + value(result.getPossibleResults(), 1);
                    }
                }
            }
            return size;
        }

        long property(Property property) {
            if (property == null) {
                return SCALAR_SIZE;
            }
            // field names (name, valueString, scope, visibility, …), headers, scalar slots
            return 6 * ENTRY_OVERHEAD + 6 * SCALAR_SIZE + utf8Length(property.getName()) + value(property.getValueString(), 1)
                    + value(property.getValueObject(), 1) + value(property.getValueList(), 1);
        }

        long value(Object value, int depth) {
            if (value == null) {
                return 1;
            }
            if (value instanceof CharSequence text) {
                return ENTRY_OVERHEAD + utf8Length(text);
            }
            if (value instanceof Date || value instanceof Temporal) {
                return DATE_SIZE;
            }
            if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
                return SCALAR_SIZE;
            }
            if (value instanceof Enum<?> constant) {
                return ENTRY_OVERHEAD + constant.name().length();
            }
            if (depth > MAX_DEPTH || !onPath.add(value)) {
                // Deeper than any stored structure, or a cycle: nothing the store could
                // write either.
                return SCALAR_SIZE;
            }
            try {
                return container(value, depth);
            } finally {
                onPath.remove(value);
            }
        }

        private long container(Object value, int depth) {
            if (value instanceof Map<?, ?> map) {
                long size = ENTRY_OVERHEAD;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    size += ENTRY_OVERHEAD + utf8Length(String.valueOf(entry.getKey())) + value(entry.getValue(), depth + 1);
                }
                return size;
            }
            if (value instanceof Collection<?> collection) {
                long size = ENTRY_OVERHEAD;
                int index = 0;
                for (Object element : collection) {
                    // BSON names array elements "0", "1", …
                    size += ENTRY_OVERHEAD + Integer.toString(index++).length() + value(element, depth + 1);
                }
                return size;
            }
            if (value instanceof Property property) {
                return property(property);
            }
            if (value instanceof Object[] array) {
                long size = ENTRY_OVERHEAD;
                for (Object element : array) {
                    size += ENTRY_OVERHEAD + value(element, depth + 1);
                }
                return size;
            }
            return serialized(value);
        }

        private static long serialized(Object value) {
            CountingSink sink = new CountingSink();
            try {
                MAPPER.writeValue(sink, value);
                return sink.count;
            } catch (Exception | StackOverflowError e) {
                return ENTRY_OVERHEAD + utf8Length(String.valueOf(value));
            }
        }
    }

    /** Counts what is written to it and keeps nothing. */
    private static final class CountingSink extends OutputStream {
        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }
    }
}
