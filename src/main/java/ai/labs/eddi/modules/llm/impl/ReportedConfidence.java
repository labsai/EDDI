/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;

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
 * a number — is <em>no usable confidence</em> ({@code null}), and the caller
 * fails towards escalation.</li>
 * </ul>
 */
public final class ReportedConfidence {

    private ReportedConfidence() {
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
