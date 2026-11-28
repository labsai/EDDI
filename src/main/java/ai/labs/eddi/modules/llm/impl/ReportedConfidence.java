/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.util.Locale;

/**
 * The one reading of a confidence a model reports about itself or about
 * another's answer — the cascade's judge and structured-output wrapper, a
 * task-force bid.
 * <p>
 * The contract is a number from 0.0 to 1.0. Clamping a larger value to 1.0
 * would count "85" as certain, so instead:
 * <ul>
 * <li>0.0 to 1.0 is the score, and {@code 1} is 1.0;</li>
 * <li>a <b>whole number</b> from 2 to 100, or any number written with a
 * trailing {@code %} up to 100%, is a percentage (85 and 85% are 0.85);</li>
 * <li>everything else — a fraction above 1 ({@code 1.5}, {@code 1.2}: a 1-5
 * scale or a slip, not a percentage), a negative, above 100, NaN, infinite, not
 * a number ({@code "high"}), JSON {@code null} — is <em>no usable
 * confidence</em> ({@code null}), and the caller fails towards escalation.</li>
 * </ul>
 * A JSON number and a numeric string read the same; so does a decimal comma
 * ({@code "0,85"}). The one ambiguity left is a whole number from 2 to 10
 * written on a 1-10 scale ({@code 7}): it reads as 7 % and escalates. The
 * prompts ask for 0.0-1.0, and reading 2..100 as a percentage is what keeps
 * {@code 85} from passing as certain; {@value #UNUSABLE_METER} counts every
 * unusable value, so a model that answers on another scale shows up there.
 */
public final class ReportedConfidence {

    /**
     * Counter of confidences that could not be used and made the caller fail
     * towards escalation (or a bid towards 0.0); tag {@code source}.
     */
    public static final String UNUSABLE_METER = "eddi.llm.confidence.unusable";

    /** A structured-output wrapper carried a confidence that is not one. */
    public static final String SOURCE_STRUCTURED_OUTPUT = "structured_output";
    /** The judge answered without a usable confidence. */
    public static final String SOURCE_JUDGE_VALUE = "judge_value";
    /** The judge call failed. */
    public static final String SOURCE_JUDGE_FAILED = "judge_failed";
    /** No judge model could be built (none configured, no type, a build error). */
    public static final String SOURCE_JUDGE_UNAVAILABLE = "judge_unavailable";
    /** A task-force bid carried no usable confidence and bids 0.0. */
    public static final String SOURCE_BID = "bid";

    private static final Logger LOGGER = Logger.getLogger(ReportedConfidence.class);

    private ReportedConfidence() {
    }

    /**
     * Counts one unusable confidence under {@link #UNUSABLE_METER}. Never throws: a
     * metric must not change the outcome.
     */
    public static void countUnusable(String source) {
        try {
            Metrics.counter(UNUSABLE_METER, "source", source).increment();
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not count an unusable confidence: %s", e.getClass().getSimpleName());
        }
    }

    /** A reported number, or null when unusable. */
    public static Double of(double reported) {
        if (Double.isNaN(reported) || Double.isInfinite(reported) || reported < 0) {
            return null;
        }
        if (reported <= 1.0) {
            return reported;
        }
        boolean whole = reported == Math.rint(reported);
        return whole && reported <= 100.0 ? Double.valueOf(reported / 100.0) : null;
    }

    /**
     * A reported text — {@code 0.85}, {@code 85}, {@code 85%}, {@code 8.5e-1} — or
     * null when unusable.
     */
    public static Double of(String reported) {
        if (reported == null) {
            return null;
        }
        String text = reported.trim().toLowerCase(Locale.ROOT);
        boolean percent = text.endsWith("%");
        if (percent) {
            text = text.substring(0, text.length() - 1).trim();
        }
        if (text.indexOf(',') >= 0 && text.indexOf('.') < 0 && text.indexOf(',') == text.lastIndexOf(',')) {
            text = text.replace(',', '.'); // a decimal comma: "0,85"
        }
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
        if (!percent) {
            return of(value);
        }
        return Double.isFinite(value) && value >= 0 && value <= 100 ? Double.valueOf(value / 100.0) : null;
    }

    /** A JSON value: a number, or a numeric text; null for anything else. */
    public static Double of(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNumber()) {
            return of(node.asDouble());
        }
        return node.isTextual() ? of(node.asText()) : null;
    }
}
