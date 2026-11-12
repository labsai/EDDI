/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;

/**
 * A token no dictionary knew. It is carried as {@code unknown(<token>)}.
 * <p>
 * The parser stores its expressions as one string that rule matching, the
 * property setter and the append merge parse again, so the token text becomes
 * expression syntax there: a pasted list with {@code a)}, {@code (It} or
 * {@code 3))} contributed unbalanced parentheses, which add up across the
 * message until the nesting passes {@code eddi.expressions.max-depth} and the
 * whole turn fails. The token is user text, i.e. data, so the expression keeps
 * its letters and drops only the three characters the notation gives meaning
 * to: {@code (}, {@code )} and {@code ,}. The word itself ({@link #getValue()},
 * match details, the conversation input) is untouched.
 *
 * @author ginccc
 */
public class Unknown extends Word {
    public Unknown(String value) {
        super(value, new Expressions(unknownExpression(value)), null);
    }

    private static Expression unknownExpression(String value) {
        String data = stripNotation(value);
        return data.isEmpty() ? new Expression("unknown") : new Expression("unknown", new Expression(data));
    }

    /** The token without the characters that are expression notation. */
    static String stripNotation(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder data = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '(' && c != ')' && c != ',') {
                data.append(c);
            }
        }
        return data.toString().trim();
    }
}
