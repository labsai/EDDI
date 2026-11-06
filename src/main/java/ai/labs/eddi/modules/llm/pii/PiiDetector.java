/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import java.util.List;
import java.util.Map;

/**
 * Finds personal data in a text. Implementations are {@code @ApplicationScoped}
 * CDI beans selected by {@link #type()}, the same pattern as
 * {@code ContentGuardrail}.
 * <h3>Contract</h3>
 * <ul>
 * <li>Stateless: everything per call arrives in {@link DetectorContext}.</li>
 * <li>Report, do not rewrite: return the spans; overlapping spans, from this
 * detector or several, are resolved by {@link PiiSpanMerger}.</li>
 * <li>Finish by {@link DetectorContext#deadlineNanos()}. A detector that cannot
 * decide throws {@link DetectorUnavailableException}; it never returns an empty
 * list for a text it did not check.</li>
 * </ul>
 */
public interface PiiDetector {

    /** The {@code type} a configuration names this detector by. */
    String type();

    /**
     * The spans of personal data in {@code text}, in any order; they may overlap.
     */
    List<PiiSpan> detect(String text, DetectorContext context) throws DetectorUnavailableException;

    /**
     * Checks the detector's parameters at deploy time: a message naming the
     * problem, or null when they are usable.
     */
    default String validate(Map<String, String> parameters) {
        return null;
    }

    /**
     * Whether the detector sends the text out of this process — the caller treats
     * it as a data processor and budgets its latency.
     */
    default boolean isRemote() {
        return false;
    }
}
