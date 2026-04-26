/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CharacterUtilitiesTest {

    // --- isStringInteger ---

    @Test
    void isStringInteger_withDigits_returnsTrue() {
        assertTrue(CharacterUtilities.isStringInteger("12345"));
    }

    @Test
    void isStringInteger_withMixed_returnsFalse() {
        assertFalse(CharacterUtilities.isStringInteger("123abc"));
    }

    @Test
    void isStringInteger_withSingleDigit_returnsTrue() {
        assertTrue(CharacterUtilities.isStringInteger("0"));
    }

    // --- isNumber ---

    @Test
    void isNumber_withNull_returnsFalse() {
        assertFalse(CharacterUtilities.isNumber(null, false));
    }

    @Test
    void isNumber_withEmpty_returnsFalse() {
        assertFalse(CharacterUtilities.isNumber("", false));
    }

    @Test
    void isNumber_withInteger_returnsTrue() {
        assertTrue(CharacterUtilities.isNumber("42", false));
    }

    @Test
    void isNumber_withDecimalDot_returnsTrue() {
        assertTrue(CharacterUtilities.isNumber("3.14", false));
    }

    @Test
    void isNumber_withDecimalComma_returnsTrue() {
        assertTrue(CharacterUtilities.isNumber("3,14", false));
    }

    @Test
    void isNumber_mustContainComma_withoutComma_returnsFalse() {
        assertFalse(CharacterUtilities.isNumber("42", true));
    }

    @Test
    void isNumber_mustContainComma_withComma_returnsTrue() {
        assertTrue(CharacterUtilities.isNumber("3.14", true));
    }

    @Test
    void isNumber_withMultipleDots_returnsFalse() {
        assertFalse(CharacterUtilities.isNumber("3.1.4", false));
    }

    @Test
    void isNumber_withTrailingDot_returnsFalse() {
        assertFalse(CharacterUtilities.isNumber("3.", false));
    }

    @Test
    void isNumber_withLetters_returnsFalse() {
        assertFalse(CharacterUtilities.isNumber("abc", false));
    }

    // --- deleteUndefinedChars ---

    @Test
    void deleteUndefinedChars_removesNonPatternChars() {
        String result = CharacterUtilities.deleteUndefinedChars("hello123!", "abcdefghijklmnopqrstuvwxyz");
        assertEquals("hello", result);
    }

    @Test
    void deleteUndefinedChars_allAllowed_returnsUnchanged() {
        String result = CharacterUtilities.deleteUndefinedChars("abc", "abcdefg");
        assertEquals("abc", result);
    }

    @Test
    void deleteUndefinedChars_stringBuilder_mutatesInPlace() {
        StringBuilder sb = new StringBuilder("a1b2c3");
        CharacterUtilities.deleteUndefinedChars(sb, "abc");
        assertEquals("abc", sb.toString());
    }

    // --- convertSpecialCharacter ---

    @Test
    void convertSpecialCharacter_umlaut_ae_converts() {
        String result = CharacterUtilities.convertSpecialCharacter("ä");
        assertEquals("ae", result);
    }

    @Test
    void convertSpecialCharacter_umlaut_oe_converts() {
        String result = CharacterUtilities.convertSpecialCharacter("ö");
        assertEquals("oe", result);
    }

    @Test
    void convertSpecialCharacter_umlaut_ue_converts() {
        String result = CharacterUtilities.convertSpecialCharacter("ü");
        assertEquals("ue", result);
    }

    @Test
    void convertSpecialCharacter_eszett_converts() {
        String result = CharacterUtilities.convertSpecialCharacter("ß");
        assertEquals("ss", result);
    }

    @Test
    void convertSpecialCharacter_curlyQuote_convertedToStraight() {
        String result = CharacterUtilities.convertSpecialCharacter("\u2018"); // left single curly quote
        assertEquals("'", result);
    }

    @Test
    void convertSpecialCharacter_regularChars_unchanged() {
        String result = CharacterUtilities.convertSpecialCharacter("hello");
        assertEquals("hello", result);
    }

    @Test
    void convertSpecialCharacter_accent_e_convertsToE() {
        String result = CharacterUtilities.convertSpecialCharacter("é");
        assertEquals("e", result);
    }

    @Test
    void convertSpecialCharacter_accent_a_convertsToA() {
        String result = CharacterUtilities.convertSpecialCharacter("á");
        assertEquals("a", result);
    }
}
