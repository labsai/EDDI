/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

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
                        // The bounds must guard i (the index into the text), not n (the
                        // index into "!?:.,;", which is always 3 for '.'). They guarded n,
                        // so a '.' at either end of the input read charAt(-1) or
                        // charAt(length) and threw — "The answer is 42." was enough.
                        if (i > 0 && i + 1 < lookup.length() && Character.isDigit(lookup.charAt(i - 1))
                                && Character.isDigit(lookup.charAt(i + 1)))
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
            else if (LanguageUtilities.extractOrdinalValue(lookup.substring(lastPos, i + 1)).isPresent()) {
                lookup.insert(i + 1, " ");
                i++;
                lastPos = i + 1;
                i = lastPos;
                // i > 0 is explicit now. This branch reads charAt(i - 1), and it used to
                // be unreachable at i == 0 only because isStringInteger("") answered
                // true for the empty substring above and skipped the iteration — a
                // bounds check standing on an unrelated method's wrong answer.
            } else if (i > 0 && CharacterUtilities.isStringInteger(String.valueOf(lookup.charAt(i))) && lookup.charAt(i - 1) != ' '
                    && lookup.charAt(i - 1) != ':' && lookup.charAt(i - 1) != '.'
                    && !CharacterUtilities.isStringInteger(String.valueOf(lookup.charAt(i - 1)))) {
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
                if (i > 0 && !Character.isDigit(lookup.charAt(i - 1)) && lookup.charAt(i - 1) != 'm' && lookup.charAt(i - 1) != 'a'
                        && lookup.charAt(i - 1) != 'p') {
                    lookup.insert(i, " ");
                    i++;
                }
        }
    }
}
