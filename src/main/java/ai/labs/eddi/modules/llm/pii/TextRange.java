/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

/**
 * The characters {@code [start, end)} of a text.
 */
public record TextRange(int start, int end) {

    public TextRange {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid range [" + start + ", " + end + ")");
        }
    }

    /** Whether {@code [from, to)} lies entirely inside this range. */
    public boolean contains(int from, int to) {
        return start <= from && to <= end;
    }
}
