/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import java.util.Map;

/**
 * The check digits that keep the built-in detectors from matching arbitrary
 * numbers. Every method takes the compact value (no spaces) and returns false
 * for anything malformed rather than throwing.
 */
public final class PiiChecksums {

    /** Fewest and most digits a payment card number has. */
    public static final int CARD_MIN_DIGITS = 13;
    public static final int CARD_MAX_DIGITS = 19;

    /** IBAN length per country (ISO 13616 registry). */
    private static final Map<String, Integer> IBAN_LENGTHS = Map.ofEntries(Map.entry("AD", 24), Map.entry("AE", 23), Map.entry("AL", 28),
            Map.entry("AT", 20), Map.entry("AZ", 28), Map.entry("BA", 20), Map.entry("BE", 16), Map.entry("BG", 22), Map.entry("BH", 22),
            Map.entry("BR", 29), Map.entry("BY", 28), Map.entry("CH", 21), Map.entry("CR", 22), Map.entry("CY", 28), Map.entry("CZ", 24),
            Map.entry("DE", 22), Map.entry("DK", 18), Map.entry("DO", 28), Map.entry("EE", 20), Map.entry("EG", 29), Map.entry("ES", 24),
            Map.entry("FI", 18), Map.entry("FO", 18), Map.entry("FR", 27), Map.entry("GB", 22), Map.entry("GE", 22), Map.entry("GI", 23),
            Map.entry("GL", 18), Map.entry("GR", 27), Map.entry("GT", 28), Map.entry("HR", 21), Map.entry("HU", 28), Map.entry("IE", 22),
            Map.entry("IL", 23), Map.entry("IQ", 23), Map.entry("IS", 26), Map.entry("IT", 27), Map.entry("JO", 30), Map.entry("KW", 30),
            Map.entry("KZ", 20), Map.entry("LB", 28), Map.entry("LC", 32), Map.entry("LI", 21), Map.entry("LT", 20), Map.entry("LU", 20),
            Map.entry("LV", 21), Map.entry("MC", 27), Map.entry("MD", 24), Map.entry("ME", 22), Map.entry("MK", 19), Map.entry("MR", 27),
            Map.entry("MT", 31), Map.entry("MU", 30), Map.entry("NL", 18), Map.entry("NO", 15), Map.entry("PK", 24), Map.entry("PL", 28),
            Map.entry("PS", 29), Map.entry("PT", 25), Map.entry("QA", 29), Map.entry("RO", 24), Map.entry("RS", 22), Map.entry("SA", 24),
            Map.entry("SC", 31), Map.entry("SE", 24), Map.entry("SI", 19), Map.entry("SK", 24), Map.entry("SM", 27), Map.entry("ST", 25),
            Map.entry("SV", 28), Map.entry("TL", 23), Map.entry("TN", 24), Map.entry("TR", 26), Map.entry("UA", 29), Map.entry("VA", 22),
            Map.entry("VG", 24), Map.entry("XK", 20));

    /**
     * Weights of the German pension insurance number check digit, applied to its
     * first 12 digits (the letter counted as its two-digit alphabet position).
     */
    private static final int[] RVNR_WEIGHTS = {2, 1, 2, 5, 7, 1, 2, 1, 2, 1, 2, 1};

    private PiiChecksums() {
    }

    /**
     * The Luhn checksum over the digits of {@code candidate} (other characters are
     * skipped); at least {@link #CARD_MIN_DIGITS} digits are required. Keeps order
     * numbers and other long digit runs from matching as cards.
     */
    public static boolean luhn(String candidate) {
        int sum = 0;
        boolean doubleIt = false;
        int digits = 0;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            int d = c - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
            digits++;
        }
        return digits >= CARD_MIN_DIGITS && sum % 10 == 0;
    }

    /**
     * ISO 13616: a known country, that country's length, and the mod-97 check
     * (remainder 1). {@code iban} is compact and upper-case.
     */
    public static boolean iban(String iban) {
        if (iban.length() < 5) {
            return false;
        }
        Integer expected = IBAN_LENGTHS.get(iban.substring(0, 2));
        if (expected == null || expected != iban.length()) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (c >= '0' && c <= '9') {
                remainder = (remainder * 10 + (c - '0')) % 97;
            } else if (c >= 'A' && c <= 'Z') {
                remainder = (remainder * 100 + (c - 'A' + 10)) % 97;
            } else {
                return false;
            }
        }
        return remainder == 1;
    }

    /**
     * The German tax identification number: 11 digits, no leading zero; among the
     * first ten exactly one digit occurs twice or three times (three not in a row)
     * and every other at most once; the last is an ISO 7064 MOD 11,10 check digit.
     */
    public static boolean deTaxId(String digits) {
        if (digits.length() != 11 || digits.charAt(0) == '0' || !allDigits(digits)) {
            return false;
        }
        int[] counts = new int[10];
        for (int i = 0; i < 10; i++) {
            counts[digits.charAt(i) - '0']++;
        }
        int repeated = 0;
        for (int count : counts) {
            if (count > 3) {
                return false;
            }
            if (count >= 2) {
                repeated++;
            }
        }
        if (repeated != 1) {
            return false;
        }
        for (int i = 2; i < 10; i++) {
            if (digits.charAt(i) == digits.charAt(i - 1) && digits.charAt(i) == digits.charAt(i - 2)) {
                return false;
            }
        }
        int product = 10;
        for (int i = 0; i < 10; i++) {
            int sum = (digits.charAt(i) - '0' + product) % 10;
            if (sum == 0) {
                sum = 10;
            }
            product = (sum * 2) % 11;
        }
        int check = 11 - product;
        if (check == 10) {
            check = 0;
        }
        return check == digits.charAt(10) - '0';
    }

    /**
     * The ICAO 9303 check digit (weights 7, 3, 1; letters A=10 … Z=35) of
     * {@code value} — the one German ID cards and passports carry after their
     * nine-character serial. {@code value} is upper-case; -1 if it holds anything
     * else.
     */
    public static int icaoCheckDigit(String value) {
        int[] weights = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int v;
            if (c >= '0' && c <= '9') {
                v = c - '0';
            } else if (c >= 'A' && c <= 'Z') {
                v = c - 'A' + 10;
            } else {
                return -1;
            }
            sum += v * weights[i % 3];
        }
        return sum % 10;
    }

    /**
     * The German pension insurance number (Rentenversicherungsnummer), compact: two
     * digits area, six digits birth date (DDMMYY), the birth-name initial, two
     * digits serial and a check digit — 12 characters.
     */
    public static boolean deSocialSecurity(String value) {
        if (value.length() != 12) {
            return false;
        }
        String head = value.substring(0, 8);
        char letter = Character.toUpperCase(value.charAt(8));
        String tail = value.substring(9);
        if (!allDigits(head) || !allDigits(tail) || letter < 'A' || letter > 'Z') {
            return false;
        }
        long day = NationalChecksums.value(head.substring(2, 4));
        long month = NationalChecksums.value(head.substring(4, 6));
        if (day < 1 || day > 31 || month < 1 || month > 12) {
            return false;
        }
        int position = letter - 'A' + 1;
        String digits = head + (position < 10 ? "0" : "") + position + tail.substring(0, 2);
        int sum = 0;
        for (int i = 0; i < RVNR_WEIGHTS.length; i++) {
            int product = (digits.charAt(i) - '0') * RVNR_WEIGHTS[i];
            sum += product / 10 + product % 10;
        }
        return sum % 10 == tail.charAt(2) - '0';
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return !s.isEmpty();
    }
}
