/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import java.util.Objects;

/**
 * One finding: the characters {@code [start, end)} of the screened text are a
 * value of {@code entityType}.
 *
 * @param start
 *            first character of the value
 * @param end
 *            one past its last character
 * @param entityType
 *            e.g. {@link PiiEntities#IBAN}, or a remote detector's own type
 * @param score
 *            how sure the detector is, 0 to 1; checksum-validated values are 1
 * @param detectorId
 *            the detector that reported it, e.g. {@code builtin}
 */
public record PiiSpan(int start, int end, String entityType, double score, String detectorId) {

    public PiiSpan {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid span [" + start + ", " + end + ")");
        }
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(detectorId, "detectorId");
    }

    public int length() {
        return end - start;
    }

    /** Whether this span shares at least one character with {@code [from, to)}. */
    public boolean overlaps(int from, int to) {
        return start < to && from < end;
    }
}
