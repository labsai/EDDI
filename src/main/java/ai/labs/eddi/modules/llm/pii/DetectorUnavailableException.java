/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

/**
 * A detector could not check the text: its time budget ran out, or its backing
 * service was unreachable or answered nonsense. The caller decides what that
 * means (its fail mode); it must never be read as "nothing found".
 * <p>
 * The reason is EDDI-written and never contains the screened text. No stack
 * trace is captured: this is an expected outcome, not a bug.
 */
public class DetectorUnavailableException extends Exception {

    private final String detectorType;
    private final String reason;

    public DetectorUnavailableException(String detectorType, String reason) {
        super(detectorType + ": " + reason, null, false, false);
        this.detectorType = detectorType;
        this.reason = reason;
    }

    /** The {@link PiiDetector#type()} that gave up. */
    public String detectorType() {
        return detectorType;
    }

    /** The audit reason, e.g. {@code timeout}. */
    public String reason() {
        return reason;
    }
}
