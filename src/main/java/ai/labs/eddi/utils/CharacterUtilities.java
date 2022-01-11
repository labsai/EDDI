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

    public static boolean isStringInteger(String lookup) {
        for (int i = 0; i < lookup.length(); i++)
            if (!Character.isDigit(lookup.charAt(i))) {
                return false;
            }

        return true;
    }

    public static boolean isNumber(String lookup, boolean mustContainComma) {
        if (isNullOrEmpty(lookup)) {
            return false;
        }

        if (mustContainComma && !(lookup.contains(",") || lookup.contains("."))) {
            return false;
        }

        boolean firstComma = true;
        for (int i = 0; i < lookup.length(); i++) {
            if (!Character.isDigit(lookup.charAt(i))) {
                if ((lookup.charAt(i) == '.' || lookup.charAt(i) == ',') && i + 1 != lookup.length() && firstComma) {
                    firstComma = false;
                } else {
                    return false;
                }
            }
        }

        return true;
    }


    public static String deleteUndefinedChars(String sentence, String pattern) {
        StringBuilder inputBuilder = new StringBuilder(sentence);
        deleteUndefinedChars(inputBuilder, pattern);
        return inputBuilder.toString();
    }

    /**
     * Methode deletes all characters which are not defined in pattern
     * to make sure that there are no unexpected chars when processing input.
     *
     * @param sentence unprocessed String with any characters
     * @param pattern  String containing all allowed chars
     */
    public static void deleteUndefinedChars(StringBuilder sentence, String pattern) {
        boolean isProhibited;
        char[] patternChars = pattern.toCharArray();

        for (int i = 0; i < sentence.length(); ) {
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
                    case 'ä':
                        input.replace(i, i + 1, "ae");
                        break;
                    case 'ö':
                        input.replace(i, i + 1, "oe");
                        break;
                    case 'ü':
                        input.replace(i, i + 1, "ue");
                        break;
                    case 'ß':
                        input.replace(i, i + 1, "ss");
                        break;
                    case 'é':
                        input.replace(i, i + 1, "e");
                        break;
                    case 'á':
                        input.replace(i, i + 1, "a");
                }
            }
        }
    }
}
