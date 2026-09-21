/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal;

/**
 * Safety limits for the semantic parser.
 * <p>
 * Parsing enumerates the cartesian product of all dictionary matches per input
 * token. With dictionaries that yield two or more matches per token this grows
 * exponentially with the number of tokens, so the enumeration has to be
 * bounded. The bounds are part of the parser's workflow configuration
 * ({@code maxInputTokens}, {@code maxSuggestions}, {@code maxSolutions}) so an
 * agent designer can widen or narrow them; the defaults below are generous
 * enough that ordinary conversational input is never affected.
 *
 * @param maxInputTokens
 *            maximum number of whitespace separated tokens taken from one user
 *            input; anything beyond is ignored
 * @param maxSuggestions
 *            maximum number of match combinations ("suggestions") evaluated for
 *            one input
 * @param maxSolutions
 *            maximum number of solutions collected for one input
 */
public record Limits(int maxInputTokens, int maxSuggestions, int maxSolutions) {

    public static final int DEFAULT_MAX_INPUT_TOKENS = 200;
    public static final int DEFAULT_MAX_SUGGESTIONS = 1000;
    public static final int DEFAULT_MAX_SOLUTIONS = 100;

    public static final Limits DEFAULT = new Limits(DEFAULT_MAX_INPUT_TOKENS, DEFAULT_MAX_SUGGESTIONS, DEFAULT_MAX_SOLUTIONS);

    public Limits {
        maxInputTokens = atLeastOne(maxInputTokens, DEFAULT_MAX_INPUT_TOKENS);
        maxSuggestions = atLeastOne(maxSuggestions, DEFAULT_MAX_SUGGESTIONS);
        maxSolutions = atLeastOne(maxSolutions, DEFAULT_MAX_SOLUTIONS);
    }

    private static int atLeastOne(int value, int fallback) {
        return value < 1 ? fallback : value;
    }
}
