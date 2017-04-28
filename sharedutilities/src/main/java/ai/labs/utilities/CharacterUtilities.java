package ai.labs.utilities;

import java.util.List;
import java.util.Random;
import java.util.StringTokenizer;

public class CharacterUtilities {
    private static final String REGULAR_CHARS = "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static boolean isStringPositiveInteger(String lookup) {
        for (int i = 0; i < lookup.length(); i++)
            if (!Character.isDigit(lookup.charAt(i))) {
                return false;
            }

        return true;
    }

    public static boolean isNumber(String lookup, boolean mustContainComma) {
        if (mustContainComma && !lookup.contains("") && !lookup.contains(",")) {
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

    public static String[] stringToArray(String s, String delim) {
        StringTokenizer token = new StringTokenizer(s, delim, false);
        String[] array = new String[token.countTokens()];

        int i = 0;
        while (token.hasMoreTokens()) {
            array[i] = token.nextToken().trim();
            i++;
        }

        return array;
    }

    public static String arrayToString(List list) {
        return arrayToString(list.toArray(), " ");
    }

    public static String arrayToString(List list, String delim) {
        return arrayToString(list.toArray(), delim);
    }

    private static String arrayToString(Object[] array, String delim) {
        StringBuilder ret = new StringBuilder();


        for (Object ary : array) {
            ret.append(ary != null ? ary.toString() : "");
            ret.append(delim);
        }

        if (ret.length() > 0) {
            ret.deleteCharAt(ret.length() - 1);
        }

        return ret.toString();
    }

    public static String capitalizeFirstLetter(String value) {
        StringBuilder sb = new StringBuilder(value);
        if (value.length() == 0) {
            return "";
        }

        char c = sb.charAt(0);
        sb.deleteCharAt(0);
        sb.insert(0, Character.toUpperCase(c));

        return sb.toString();
    }

    public static String[] arrayToLowerCase(String[] array) {
        for (int i = 0; i < array.length; i++)
            array[i] = array[i].toLowerCase();

        return array;
    }

    public static String replaceAllByKey(String text, String key, String value) {
        StringBuilder sb = new StringBuilder(text);

        return replaceAllByKey(sb, key, value);
    }

    private static String replaceAllByKey(StringBuilder text, String key, String value) {
        int i;
        while ((i = text.indexOf(key)) != -1) {
            text.replace(i, i + key.length(), value);
        }

        return text.toString();
    }

    public static String getBlanksByRemainingDistance(String s, String key) {
        return getBlanksByRemainingDistance(s, key, 120);
    }

    private static String getBlanksByRemainingDistance(String s, String key, int max) {
        StringBuilder blanks = new StringBuilder();
        int length = max - s.lastIndexOf(key);

        for (int i = 0; i < length; i++) {
            blanks.append(" ");
        }

        return blanks.toString();
    }

    public static String createHash(int length) {
        StringBuilder finalHash = new StringBuilder();
        char[] allowedChars = "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
        int random;

        for (int i = 0; i < length; i++) {
            random = new Random().nextInt(allowedChars.length - 1);
            finalHash.append(allowedChars[random]);
        }

        return finalHash.toString();
    }

    public static String removeAllBracketSequence(String value) {
        return value.replaceAll("\\(.*\\)|\\{.*\\}|\\[.*\\]|<.*>", "");
    }

    public static String convertSpecialCharacter(String input) {
        StringBuilder ret = new StringBuilder(input);
        convertSpecialCharacter(ret);
        return ret.toString();
    }

    public static void convertSpecialCharacter(StringBuilder input) {
        for (int i = 0; i < input.length(); i++) {
            switch (Character.toLowerCase(input.charAt(i))) {
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

    public static String createSemantic(String input, boolean replaceBlanks) {
        input = input.trim().toLowerCase();
        if (replaceBlanks) {
            input = input.replaceAll(" ", "_");
        }

        StringBuilder charSeq = new StringBuilder(input);
        convertSpecialCharacter(charSeq);
        deleteUndefinedChars(charSeq, REGULAR_CHARS + "_ ");
        return charSeq.toString();
    }

    public static String deleteAllWhitespacesWithinSquaredBrackets(String value) {
        StringBuilder sb = new StringBuilder(value);

        boolean isOpen = false;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '[') {
                isOpen = true;
            }
            if (sb.charAt(i) == ']') {
                isOpen = false;
            }

            if (isOpen && sb.charAt(i) == ' ') {
                sb.deleteCharAt(i);
                i--;
            }
        }

        return sb.toString();
    }

    public static String wrapStringWithSquaredBrackets(String value) {
        return "[" + value + "]";
    }
}
