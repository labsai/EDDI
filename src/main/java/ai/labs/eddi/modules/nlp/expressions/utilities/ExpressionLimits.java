/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions.utilities;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The two bounds on text the expression parser accepts: how long it may be and
 * how deeply its parentheses may nest.
 * <p>
 * The parser used to recurse once per nesting level with no cap at all, and any
 * chatting user reaches it — a {@code context} entry of type
 * {@code expressions} is parsed by {@code PropertySetterTask} and by every
 * behavior rule with a {@code contextmatcher}. Twenty thousand nested
 * parentheses overflowed the stack, which is an {@code Error} the pipeline's
 * task-failure accounting did not catch, and a 3 MB payload cost ten seconds of
 * CPU per request because every level copied the remainder of the string. Both
 * bounds are checked in one linear pass BEFORE parsing starts, so an over-long
 * or over-deep payload costs one scan and nothing else.
 * <p>
 * A value of {@code 0} or less disables that bound. The defaults are generous
 * for anything a dictionary or a client legitimately sends — the shipped agent
 * configs stay below a few hundred characters and three levels.
 */
@ApplicationScoped
public class ExpressionLimits {
    public static final String MAX_LENGTH_PROPERTY = "eddi.expressions.max-length";
    public static final String MAX_DEPTH_PROPERTY = "eddi.expressions.max-depth";
    public static final int DEFAULT_MAX_LENGTH = 32_768;
    public static final int DEFAULT_MAX_DEPTH = 32;

    private static final ExpressionLimits DEFAULTS = new ExpressionLimits(DEFAULT_MAX_LENGTH, DEFAULT_MAX_DEPTH);

    private final int maxLength;
    private final int maxDepth;

    @Inject
    public ExpressionLimits(@ConfigProperty(name = MAX_LENGTH_PROPERTY, defaultValue = "32768") int maxLength,
            @ConfigProperty(name = MAX_DEPTH_PROPERTY, defaultValue = "32") int maxDepth) {
        this.maxLength = maxLength;
        this.maxDepth = maxDepth;
    }

    /**
     * The shipped defaults — what a directly constructed parser in a unit test
     * gets.
     */
    public static ExpressionLimits defaults() {
        return DEFAULTS;
    }

    public int maxLength() {
        return maxLength;
    }

    public int maxDepth() {
        return maxDepth;
    }

    /**
     * Refuses {@code expressions} when it is longer than {@link #maxLength()} or
     * its parentheses nest deeper than {@link #maxDepth()}. Null and empty input
     * pass. One linear scan, no allocation.
     *
     * @throws ExpressionLimitExceededException
     *             naming the bound, the value measured and the property that sets
     *             the bound
     */
    public void check(String expressions) {
        if (expressions == null) {
            return;
        }
        int length = expressions.length();
        if (maxLength > 0 && length > maxLength) {
            throw new ExpressionLimitExceededException("Expressions are " + length + " characters long; at most " + maxLength
                    + " are accepted (" + MAX_LENGTH_PROPERTY + ")", length, maxLength);
        }
        if (maxDepth <= 0) {
            return;
        }
        int depth = 0;
        int deepest = 0;
        for (int i = 0; i < length; i++) {
            char c = expressions.charAt(i);
            if (c == '(') {
                depth++;
                if (depth > deepest) {
                    deepest = depth;
                    if (deepest > maxDepth) {
                        throw tooDeep(deepest);
                    }
                }
            } else if (c == ')' && depth > 0) {
                depth--;
            }
        }
    }

    /**
     * The in-parser guard for the level the parser is about to descend into — the
     * same bound as {@link #check(String)}, enforced again where the recursion
     * actually happens so that no entry point can bypass it.
     */
    void requireDepth(int depth) {
        if (maxDepth > 0 && depth > maxDepth) {
            throw tooDeep(depth);
        }
    }

    private ExpressionLimitExceededException tooDeep(int depth) {
        return new ExpressionLimitExceededException("Expressions nest " + depth + " levels deep; at most " + maxDepth
                + " are accepted (" + MAX_DEPTH_PROPERTY + ")", depth, maxDepth);
    }

    /**
     * An expression string is over one of the bounds. An
     * {@link IllegalArgumentException}, so a pipeline task that parses it fails the
     * turn like any other bad input, and the REST boundary turns it into a client
     * error before a turn starts.
     */
    public static final class ExpressionLimitExceededException extends IllegalArgumentException {
        private final int measured;
        private final int limit;

        public ExpressionLimitExceededException(String message, int measured, int limit) {
            super(message);
            this.measured = measured;
            this.limit = limit;
        }

        /** The length or depth that was measured. */
        public int getMeasured() {
            return measured;
        }

        /** The bound it exceeded. */
        public int getLimit() {
            return limit;
        }
    }
}
