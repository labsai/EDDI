package ai.labs.eddi.engine.utilities;


import ai.labs.eddi.utils.CharacterUtilities;
import ai.labs.eddi.utils.LanguageUtilities;

/**
 * @author ginccc
 */
public class WordSplitter {
    private StringBuilder lookup;

    public WordSplitter(StringBuilder lookup) {
        this.lookup = lookup;
    }

    public void splitWords() {
        splitPunctuationFromWords();
    }

    public void splitPunctuationFromWords() {
        String punctuation = "!?:.,;";
        boolean doNotInsertBlank = false;
        for (int i = 0; i < lookup.length(); i++) {
            doNotInsertBlank = false;
            for (int n = 0; n < punctuation.length(); n++) {
                if (lookup.charAt(i) == punctuation.charAt(n)) {
                    if (punctuation.charAt(n) == '.')
                        if (n > 0 && n - 1 < lookup.length() && Character.isDigit(lookup.charAt(i - 1)) && Character.isDigit(lookup.charAt(i + 1)))
                            doNotInsertBlank = true;

                    if (!doNotInsertBlank) {
                        if (i > 0 && lookup.charAt(i - 1) != ' ')
                            lookup.insert(i, ' ');

                        if (i + 1 < lookup.length() && lookup.charAt(i + 1) != ' ')
                            lookup.insert(i + 1, ' ');
                    }

                    break;
                }
            }
        }
    }

    public void notNumeric() {
        String tmp;
        int lastPos = 0;
        for (int i = 0; i < lookup.length(); i++) {
            tmp = lookup.substring(lastPos, i);
            if (CharacterUtilities.isStringInteger(tmp))
                continue;
            else if (LanguageUtilities.isOrdinalNumber(lookup.substring(lastPos, i + 1)) != null) {
                lookup.insert(i + 1, " ");
                i++;
                lastPos = i + 1;
                i = lastPos;
            } else if (CharacterUtilities.isStringInteger(String.valueOf(lookup.charAt(i))) &&
                    lookup.charAt(i - 1) != ' ' &&
                    lookup.charAt(i - 1) != ':' &&
                    lookup.charAt(i - 1) != '.' &&
                    !CharacterUtilities.isStringInteger(String.valueOf(lookup.charAt(i - 1)))) {
                lookup.insert(i, " ");
                lastPos = ++i;
            }

            lookup.trimToSize();
        }
    }

    public void notAlphabetic() {
        String alphabeticalChars = "abcdefghijklmnopqrstuvwxyz 1234567890:.h";

        boolean isNonAlphabeticalChar;
        for (int i = 0; i < lookup.length(); i++) {
            isNonAlphabeticalChar = true;
            for (int n = 0; n < alphabeticalChars.length(); n++) {

                if (Character.toLowerCase(lookup.charAt(i)) == alphabeticalChars.charAt(n)) {
                    isNonAlphabeticalChar = false;
                    break;
                }
            }

            if (isNonAlphabeticalChar) {
                lookup.insert(i + 1, " ");
                lookup.insert(i, " ");
                i = i + 2;
            }
        }
    }

    public void capitalizedWords() {
        for (int i = 0; i < lookup.length(); i++) {
            if (Character.isUpperCase(lookup.charAt(i))) {
                lookup.insert(i, " ");
                i++;
            }
        }
    }

    public void isPunctuation() {
        for (int i = 0; i < lookup.length(); i++) {
            if (lookup.charAt(i) == '.')
                if (i > 0 && !Character.isDigit(lookup.charAt(i - 1)) && lookup.charAt(i - 1) != 'm' && lookup.charAt(i - 1) != 'a' && lookup.charAt(i - 1) != 'p') {
                    lookup.insert(i, " ");
                    i++;
                }
        }
    }
}
