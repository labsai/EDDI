/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.util.HashMap;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

public class CharacterUtilities {
    private static final Map<Character, Character> specialCharacters = new HashMap<>();

    static {
        specialCharacters.put('’', '\'');
        specialCharacters.put('‘', '\'');
        specialCharacters.put('´', '\'');
        specialCharacters.put('`', '\'');
        specialCharacters.put('“', '"');
        specialCharacters.put('”', '"');
    }

    /**
     * True when every character is a decimal digit of any script (ASCII, fullwidth,
     * Arabic-Indic, Devanagari, Thai, …) — and therefore false for null and for the
     * empty string, which contain no digits at all. The number parsers downstream
     * read ASCII, so a caller that builds an expression or a value from such a text
     * converts it with {@link #toAsciiDigits(String)} first.
     * <p>
     * The empty string is explicitly not an integer, so callers need not guard it
     * themselves.
     */
    public static boolean isStringInteger(String lookup) {
        if (lookup == null || lookup.isEmpty()) {
            return false;
        }
        for (int i = 0; i < lookup.length(); i += Character.charCount(lookup.codePointAt(i))) {
            if (!Character.isDigit(lookup.codePointAt(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * The text with every decimal digit of another script replaced by its ASCII
     * digit ({@code ١٢} becomes {@code 12}); everything else, and a text that has
     * no such digit, is returned as it is.
     */
    public static String toAsciiDigits(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder converted = null;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (codePoint > '9' && Character.isDigit(codePoint)) {
                if (converted == null) {
                    converted = new StringBuilder(text.length()).append(text, 0, i);
                }
                converted.append((char) ('0' + Character.digit(codePoint, 10)));
            } else if (converted != null) {
                converted.appendCodePoint(codePoint);
            }
            i += width;
        }
        return converted == null ? text : converted.toString();
    }

    /**
     * True for decimal digits (of any script) with at most one decimal separator
     * ({@code .} or {@code ,}) that is not the last character.
     */
    public static boolean isNumber(String lookup, boolean mustContainComma) {
        if (isNullOrEmpty(lookup)) {
            return false;
        }

        if (mustContainComma && !(lookup.contains(",") || lookup.contains("."))) {
            return false;
        }

        boolean firstComma = true;
        for (int i = 0; i < lookup.length();) {
            int codePoint = lookup.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (!Character.isDigit(codePoint)) {
                if ((codePoint == '.' || codePoint == ',') && i + width != lookup.length() && firstComma) {
                    firstComma = false;
                } else {
                    return false;
                }
            }
            i += width;
        }

        return true;
    }

    public static String deleteUndefinedChars(String sentence, String pattern) {
        StringBuilder inputBuilder = new StringBuilder(sentence);
        deleteUndefinedChars(inputBuilder, pattern);
        return inputBuilder.toString();
    }

    /**
     * Deletes all characters which are not defined in pattern to make sure that
     * there are no unexpected chars when processing input.
     *
     * @param sentence
     *            unprocessed String with any characters
     * @param pattern
     *            String containing all allowed chars
     */
    public static void deleteUndefinedChars(StringBuilder sentence, String pattern) {
        boolean isProhibited;
        char[] patternChars = pattern.toCharArray();

        for (int i = 0; i < sentence.length();) {
            isProhibited = true;
            for (char allowedChar : patternChars) {
                if (sentence.charAt(i) == allowedChar) {
                    isProhibited = false;
                    break;
                }
            }

            if (isProhibited) {
                sentence.deleteCharAt(i);
            } else {
                i++;
            }
        }
    }

    public static String convertSpecialCharacter(String input) {
        StringBuilder inputBuilder = new StringBuilder(input);
        convertSpecialCharacter(inputBuilder);
        return inputBuilder.toString();
    }

    public static void convertSpecialCharacter(StringBuilder input) {
        for (int i = 0; i < input.length(); i++) {
            char lookup = input.charAt(i);
            if (specialCharacters.containsKey(lookup)) {
                input.replace(i, i + 1, specialCharacters.get(lookup).toString());
            } else {
                switch (Character.toLowerCase(lookup)) {
                    case 'ä' :
                        input.replace(i, i + 1, "ae");
                        break;
                    case 'ö' :
                        input.replace(i, i + 1, "oe");
                        break;
                    case 'ü' :
                        input.replace(i, i + 1, "ue");
                        break;
                    case 'ß' :
                        input.replace(i, i + 1, "ss");
                        break;
                    case 'é' :
                        input.replace(i, i + 1, "e");
                        break;
                    case 'á' :
                        input.replace(i, i + 1, "a");
                        break;
                    default :
                        break;
                }
            }
        }
    }
}
