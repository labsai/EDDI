/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions.utilities;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits.ExpressionLimitExceededException;
import ai.labs.eddi.modules.nlp.model.Unknown;
import org.jboss.logging.Logger;

/**
 * Reads back the expression string a parser stored on a conversation step
 * ({@code expressions:parsed}), and builds the bounded {@code unknown}
 * expression that stands in for a text too long to parse.
 * <p>
 * That string is built from user text, so it can be longer than
 * {@link ExpressionLimits} allow even though the input itself was accepted. A
 * consumer must not fail the whole turn for it, and must not read it as "the
 * user said nothing" either (an {@code empty} rule would fire): an over-long
 * stored result is read as the same single bounded {@code unknown} the parser
 * itself degrades to.
 */
public final class StoredExpressions {
    private static final Logger LOGGER = Logger.getLogger(StoredExpressions.class);

    /**
     * How much of a text a degraded or pass-through {@code unknown(...)} expression
     * carries.
     */
    public static final int MAX_UNKNOWN_PAYLOAD_CHARS = 1000;

    private StoredExpressions() {
    }

    /**
     * @return the parsed expressions, or, when {@code stored} is over an
     *         {@link ExpressionLimits} bound, one bounded {@code unknown}
     *         expression made from its beginning
     */
    public static Expressions parseOrEmpty(IExpressionProvider provider, String stored) {
        try {
            Expressions parsed = provider.parseExpressions(stored);
            return parsed != null ? parsed : new Expressions();
        } catch (ExpressionLimitExceededException e) {
            LOGGER.debugf("Stored expressions are over a limit, read as one bounded unknown: %s", e.getMessage());
            return boundedUnknown(stored, provider.maxExpressionLength());
        }
    }

    /**
     * One {@code unknown} expression for {@code text}: the notation characters
     * removed, at most {@link #MAX_UNKNOWN_PAYLOAD_CHARS} characters, never cut
     * inside a surrogate pair (a lone surrogate cannot be stored as JSON by every
     * database).
     */
    public static Expressions boundedUnknown(String text) {
        return boundedUnknown(text, 0);
    }

    /**
     * Like {@link #boundedUnknown(String)}, and the whole {@code unknown(...)}
     * expression fits {@code maxExpressionLength} ({@code <= 0}: no limit), so the
     * expression provider can read back what is stored. The payload shrinks by the
     * length of the wrapper; notation characters removed from it shrink it further.
     */
    public static Expressions boundedUnknown(String text, int maxExpressionLength) {
        Expressions expressions = new Expressions();
        if (text == null) {
            return expressions;
        }
        int budget = MAX_UNKNOWN_PAYLOAD_CHARS;
        if (maxExpressionLength > 0) {
            budget = Math.min(budget, maxExpressionLength - UNKNOWN_WRAPPER_CHARS);
        }
        if (budget <= 0) {
            return expressions;
        }
        int end = Math.min(text.length(), budget);
        if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        expressions.addAll(new Unknown(text.substring(0, end)).getExpressions());
        return expressions;
    }

    /** {@code unknown(} and {@code )}. */
    private static final int UNKNOWN_WRAPPER_CHARS = "unknown()".length();
}
