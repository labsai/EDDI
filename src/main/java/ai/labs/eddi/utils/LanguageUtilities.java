/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.sql.Time;
import java.util.Date;
import java.util.Optional;
import java.util.regex.Pattern;

public class LanguageUtilities {
    private static final Pattern ORDINAL_SUFFIX_PATTERN = Pattern.compile("[0-9]+[a-z]+"); // e.g. 21st
    private static final Pattern ORDINAL_DOT_PATTERN = Pattern.compile("(\\d{0,2})(\\.)"); // e.g. 3.

    private interface TimeRecognition {
        Pattern p1 = Pattern.compile("^(([0-1]?[0-9]|[2]?2[0-3])(h)([0-5][0-9]))$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL); // e.g. 12h10
        Pattern p2 = Pattern.compile("^(([0-1]?[0-9]|[2]?2[0-3])(h))$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL); // e.g. 15h
        Pattern p3 = Pattern.compile("^([0-1]?[0-9]|[2][0-3]):([0-5][0-9])$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL); // e.g. 19:50
        Pattern p4 = Pattern.compile("(([0-1][0-9])|([2][0-3])):([0-5][0-9]):([0-5][0-9])", Pattern.CASE_INSENSITIVE | Pattern.DOTALL); // e.g.
                                                                                                                                        // 13:50:12
    }

    public static Date isTimeExpression(String value) {
        value = value.toLowerCase();

        if (value.contains("h")) {
            if (TimeRecognition.p1.matcher(value).matches()) {
                value = value.replace('h', ':');
            } else if (TimeRecognition.p2.matcher(value).matches()) {
                value = value.substring(0, value.indexOf("h")) + ":00";
            }
        }

        if (value.contains("24:00")) {
            value = "00:00";
        }

        if (TimeRecognition.p3.matcher(value).matches()) {
            return Time.valueOf(value + ":00");
        }

        if (TimeRecognition.p4.matcher(value).matches()) {
            return Time.valueOf(value);
        }

        return null;
    }

    /**
     * Extracts the numeric value of an ordinal number, either in suffix notation
     * (e.g. {@code 21st -> 21}) or in dot notation (e.g. {@code 3. -> 3}).
     *
     * @param value
     *            the token to inspect
     * @return the ordinal value, or {@link Optional#empty()} if the token is not an
     *         ordinal number
     */
    public static Optional<Integer> extractOrdinalValue(String value) {
        if (value.length() > 2 && ORDINAL_SUFFIX_PATTERN.matcher(value).matches()) {
            String suffix = value.substring(value.length() - 2);
            if (suffix.equals("st") || suffix.equals("nd") || suffix.equals("rd") || suffix.equals("th")) {
                return parseOrdinal(value.substring(0, value.length() - 2));
            }
        } else {
            var dotMatcher = ORDINAL_DOT_PATTERN.matcher(value);
            if (dotMatcher.matches()) {
                // group 1 is empty for a bare dot, which is not an ordinal number
                return parseOrdinal(dotMatcher.group(1));
            }
        }

        return Optional.empty();
    }

    private static Optional<Integer> parseOrdinal(String digits) {
        try {
            return Optional.of(Integer.parseInt(digits));
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
    }

    public LanguageUtilities() {
    }
}
