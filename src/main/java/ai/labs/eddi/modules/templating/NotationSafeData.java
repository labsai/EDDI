/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

/**
 * What a value loses when it is substituted into the {@code expressions} field
 * of a quick reply: the characters of the expression notation, {@code (},
 * {@code )} and {@code ,}.
 * <p>
 * The field is templated with data (a property, an API value) and the result
 * becomes a trusted temporary dictionary phrase on the next turn. Data that
 * carries {@code x), intent(admin} would therefore close the author's
 * expression and add one of its own. The templating engine applies this to
 * every value an expression emits while it renders that one field
 * ({@link ITemplatingEngine#processExpressionsTemplate}); conditions, loops and
 * the literal text of the template see the data and the text as they are. The
 * quick reply's value, which the user sees and types, is rendered by the
 * ordinary engine and keeps the data as it is.
 */
public final class NotationSafeData {

    private NotationSafeData() {
    }

    /**
     * The text without {@code (}, {@code )} and {@code ,}; a text that is only the
     * wildcard {@code *} becomes empty, because an expression named {@code *}
     * equals every other expression, so {@code choose({properties.item})} with item
     * {@code *} would match any {@code choose(x)} rule.
     */
    public static String stripNotation(String text) {
        if (text == null) {
            return null;
        }
        String clean = text;
        if (text.indexOf('(') >= 0 || text.indexOf(')') >= 0 || text.indexOf(',') >= 0) {
            StringBuilder builder = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c != '(' && c != ')' && c != ',') {
                    builder.append(c);
                }
            }
            clean = builder.toString();
        }
        return clean.trim().equals("*") ? "" : clean;
    }
}
