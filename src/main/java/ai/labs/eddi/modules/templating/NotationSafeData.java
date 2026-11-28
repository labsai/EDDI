/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;

/**
 * What a value loses when it is substituted into expression notation — the
 * {@code expressions} of a quick reply or of an output item: the characters the
 * expression parser gives a meaning to ({@code (}, {@code )} and {@code ,}),
 * and the whole value when it would read as the wildcard {@code *}.
 * <p>
 * The field is templated with data (a property, an API value) and the result
 * becomes a trusted temporary dictionary phrase on the next turn. Data that
 * carries {@code x), intent(admin} would therefore close the author's
 * expression and add one of its own. The templating engine applies this to
 * every value an expression emits while it renders such a field
 * ({@link ITemplatingEngine#processExpressionsTemplate}); conditions, loops and
 * the literal text of the template see the data and the text as they are. The
 * quick reply's value, which the user sees and types, is rendered by the
 * ordinary engine and keeps the data as it is.
 * <p>
 * Both rules ask the parser ({@link ExpressionProvider#isNotationCharacter},
 * {@link ExpressionProvider#readsAsWildcard}) rather than keep a list of their
 * own, so what is stripped here is by construction what the parser would read
 * as notation.
 */
public final class NotationSafeData {

    private NotationSafeData() {
    }

    /**
     * The text without the notation characters; a text the parser would read as the
     * wildcard {@code *} — the star with any whitespace the parser skips around it,
     * U+3000 and the other Unicode spaces included — becomes empty, because an
     * expression named {@code *} equals every other expression, so
     * {@code choose({properties.item})} with item {@code *} would match any
     * {@code choose(x)} rule.
     * <p>
     * A data value cannot contain a delimiter after this, so whatever expression it
     * lands in, the value lies entirely inside one name; that name is the wildcard
     * only if the value alone reads as {@code *} (or the author's own text supplies
     * the star).
     */
    public static String stripNotation(String text) {
        if (text == null) {
            return null;
        }
        String clean = text;
        int first = firstNotationCharacter(text);
        if (first >= 0) {
            StringBuilder builder = new StringBuilder(text.length());
            builder.append(text, 0, first);
            for (int i = first + 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!ExpressionProvider.isNotationCharacter(c)) {
                    builder.append(c);
                }
            }
            clean = builder.toString();
        }
        return ExpressionProvider.readsAsWildcard(clean) ? "" : clean;
    }

    private static int firstNotationCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (ExpressionProvider.isNotationCharacter(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
