/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions.utilities;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits.ExpressionLimitExceededException;
import org.jboss.logging.Logger;

/**
 * Reads back the expression string a parser stored on a conversation step
 * ({@code expressions:parsed}).
 * <p>
 * That string is built from user text, so it can be longer than
 * {@link ExpressionLimits} allow even though the input itself was accepted. A
 * consumer must not fail the whole turn for it: an over-long stored result is
 * read as "no expressions", exactly as if the parser had understood nothing.
 */
public final class StoredExpressions {
    private static final Logger LOGGER = Logger.getLogger(StoredExpressions.class);

    private StoredExpressions() {
    }

    /**
     * @return the parsed expressions, or an empty list when {@code stored} is over
     *         an {@link ExpressionLimits} bound
     */
    public static Expressions parseOrEmpty(IExpressionProvider provider, String stored) {
        try {
            Expressions parsed = provider.parseExpressions(stored);
            return parsed != null ? parsed : new Expressions();
        } catch (ExpressionLimitExceededException e) {
            LOGGER.debugf("Stored expressions are over a limit, read as none: %s", e.getMessage());
            return new Expressions();
        }
    }
}
