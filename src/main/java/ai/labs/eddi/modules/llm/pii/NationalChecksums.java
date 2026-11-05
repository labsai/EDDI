/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import java.util.Set;

/**
 * Check digits and structural rules of national identifiers. Every method takes
 * the compact value — upper-case, separators removed — and returns false for
 * anything malformed rather than throwing.
 */
public final class NationalChecksums {

    private static final int[] IT_ODD = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23};
    private static final String ES_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";
    private static final String FI_CHECK = "0123456789ABCDEFHJKLMNPRSTUVWXY";
    private static final String FI_CENTURY = "+-ABCDEFUVWXY";
    private static final String IE_CHECK = "WABCDEFGHIJKLMNOPQRSTUV";
    private static final String MX_ALPHABET = "0123456789ABCDEFGHIJKLMNÑOPQRSTUVWXYZ";
    private static final String CN_CHECK = "10X98765432";
    private static final int[] CN_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final Set<String> GB_NINO_BAD_PREFIXES = Set.of("BG", "GB", "NK", "KN", "TN", "NT", "ZZ");
    private static final int[][] VERHOEFF_D = {{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1}, {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4}, {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] VERHOEFF_P = {{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1}, {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    private NationalChecksums() {
    }

    // ---- Americas ----

    /** Canadian SIN: nine digits, Luhn. */
    public static boolean caSin(String v) {
        return v.length() == 9 && allDigits(v) && luhn(v);
    }

    /** Brazilian CPF: eleven digits, not all equal, two mod-11 check digits. */
    public static boolean brCpf(String v) {
        if (v.length() != 11 || !allDigits(v) || v.chars().distinct().count() == 1) {
            return false;
        }
        for (int k = 9; k <= 10; k++) {
            int sum = 0;
            for (int i = 0; i < k; i++) {
                sum += digit(v, i) * (k + 1 - i);
            }
            int check = sum * 10 % 11 % 10;
            if (check != digit(v, k)) {
                return false;
            }
        }
        return true;
    }

    /** Mexican CURP: 18 characters, a date, and the final check digit. */
    public static boolean mxCurp(String v) {
        if (v.length() != 18 || !date(v.substring(6, 8), v.substring(8, 10))) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int value = MX_ALPHABET.indexOf(v.charAt(i));
            if (value < 0) {
                return false;
            }
            sum += value * (18 - i);
        }
        return (10 - sum % 10) % 10 == digit(v, 17);
    }

    // ---- Europe ----

    /**
     * UK National Insurance number: a valid prefix (the pattern checks the rest).
     */
    public static boolean gbNino(String v) {
        return v.length() == 9 && !GB_NINO_BAD_PREFIXES.contains(v.substring(0, 2));
    }

    /** NHS number: ten digits, mod-11 with weights 10 to 2. */
    public static boolean gbNhs(String v) {
        if (v.length() != 10 || !allDigits(v)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += digit(v, i) * (10 - i);
        }
        int check = 11 - sum % 11;
        if (check == 11) {
            check = 0;
        }
        return check != 10 && check == digit(v, 9);
    }

    /**
     * Irish PPS number: seven digits, a check letter, optionally a second letter.
     */
    public static boolean iePps(String v) {
        if (v.length() < 8 || v.length() > 9 || !allDigits(v.substring(0, 7))) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            sum += digit(v, i) * (8 - i);
        }
        if (v.length() == 9 && v.charAt(8) != 'W') {
            sum += (v.charAt(8) - 'A' + 1) * 9;
        }
        return IE_CHECK.charAt(sum % 23) == v.charAt(7);
    }

    /**
     * French NIR: 13 characters and a two-digit key, 97 minus the number mod 97.
     * Corsican departments 2A and 2B count as 19 and 18.
     */
    public static boolean frNir(String v) {
        if (v.length() != 15) {
            return false;
        }
        String body = v.substring(0, 13);
        String department = body.substring(5, 7);
        if (department.equals("2A")) {
            body = body.substring(0, 5) + "19" + body.substring(7);
        } else if (department.equals("2B")) {
            body = body.substring(0, 5) + "18" + body.substring(7);
        }
        String key = v.substring(13);
        if (!allDigits(body) || !allDigits(key)) {
            return false;
        }
        return 97 - value(body) % 97 == (int) value(key);
    }

    /** Italian codice fiscale: 16 characters, the last a check letter. */
    public static boolean itFiscalCode(String v) {
        if (v.length() != 16) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 15; i++) {
            char c = v.charAt(i);
            int index;
            if (c >= '0' && c <= '9') {
                index = c - '0';
            } else if (c >= 'A' && c <= 'Z') {
                index = c - 'A';
            } else {
                return false;
            }
            // Positions are counted from 1, so index 0 is an odd position.
            sum += i % 2 == 0 ? IT_ODD[index] : index;
        }
        return v.charAt(15) == 'A' + sum % 26;
    }

    /**
     * Spanish DNI (eight digits) or NIE (X, Y or Z and seven digits), check letter.
     */
    public static boolean esDni(String v) {
        if (v.length() != 9) {
            return false;
        }
        String number = v.substring(0, 8);
        int prefix = "XYZ".indexOf(number.charAt(0));
        if (prefix >= 0) {
            number = prefix + number.substring(1);
        }
        return allDigits(number) && ES_LETTERS.charAt((int) value(number) % 23) == v.charAt(8);
    }

    /** Portuguese NIF: nine digits, mod-11 with weights 9 to 2. */
    public static boolean ptNif(String v) {
        if (v.length() != 9 || !allDigits(v) || v.charAt(0) == '0' || v.charAt(0) == '4') {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += digit(v, i) * (9 - i);
        }
        int remainder = sum % 11;
        return (remainder < 2 ? 0 : 11 - remainder) == digit(v, 8);
    }

    /**
     * Dutch BSN: nine digits passing the eleven test (the last digit weighted -1).
     */
    public static boolean nlBsn(String v) {
        if (v.length() != 9 || !allDigits(v) || v.equals("000000000")) {
            return false;
        }
        int sum = -digit(v, 8);
        for (int i = 0; i < 8; i++) {
            sum += digit(v, i) * (9 - i);
        }
        return sum % 11 == 0;
    }

    /**
     * Belgian national number: eleven digits, the last two 97 minus the first nine
     * mod 97 — with a 2 prepended for people born from 2000.
     */
    public static boolean beNationalNumber(String v) {
        if (v.length() != 11 || !allDigits(v)) {
            return false;
        }
        long body = value(v.substring(0, 9));
        int key = (int) value(v.substring(9));
        return 97 - body % 97 == key || 97 - (2_000_000_000L + body) % 97 == key;
    }

    /**
     * Austrian social insurance number: a three-digit serial, a check digit, then
     * the birth date DDMMYY; weights 3 7 9 _ 5 8 4 2 1 6, mod 11.
     */
    public static boolean atSocialSecurity(String v) {
        if (v.length() != 10 || !allDigits(v) || v.charAt(0) == '0') {
            return false;
        }
        int[] weights = {3, 7, 9, 0, 5, 8, 4, 2, 1, 6};
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += digit(v, i) * weights[i];
        }
        int check = sum % 11;
        return check != 10 && check == digit(v, 3) && date(v.substring(6, 8), v.substring(4, 6));
    }

    /** Swiss AHV number: 13 digits starting 756, EAN-13 check digit. */
    public static boolean chAhv(String v) {
        if (v.length() != 13 || !allDigits(v) || !v.startsWith("756")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += digit(v, i) * (i % 2 == 0 ? 1 : 3);
        }
        return (10 - sum % 10) % 10 == digit(v, 12);
    }

    /**
     * Polish PESEL: eleven digits, a birth date (century in the month), check
     * digit.
     */
    public static boolean plPesel(String v) {
        if (v.length() != 11 || !allDigits(v)) {
            return false;
        }
        int month = (int) value(v.substring(2, 4)) % 20;
        int day = (int) value(v.substring(4, 6));
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return false;
        }
        int[] weights = {1, 3, 7, 9, 1, 3, 7, 9, 1, 3};
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += digit(v, i) * weights[i];
        }
        return (10 - sum % 10) % 10 == digit(v, 10);
    }

    /**
     * Swedish personnummer, ten digits YYMMDDNNNC: a date (day plus 60 for a
     * coordination number) and a Luhn check digit.
     */
    public static boolean sePersonnummer(String v) {
        if (v.length() != 10 || !allDigits(v)) {
            return false;
        }
        int day = (int) value(v.substring(4, 6));
        String dd = String.format("%02d", day > 60 ? day - 60 : day);
        return date(v.substring(2, 4), dd) && luhn(v);
    }

    /**
     * Danish CPR number, ten digits DDMMYYSSSS: a date (no check digit since 2007).
     */
    public static boolean dkCpr(String v) {
        return v.length() == 10 && allDigits(v) && date(v.substring(2, 4), v.substring(0, 2));
    }

    /**
     * Finnish henkilötunnus DDMMYYCNNNQ: a date, a century sign, a mod-31 check
     * character.
     */
    public static boolean fiHetu(String v) {
        if (v.length() != 11 || FI_CENTURY.indexOf(v.charAt(6)) < 0) {
            return false;
        }
        String number = v.substring(0, 6) + v.substring(7, 10);
        if (!allDigits(number) || !date(v.substring(2, 4), v.substring(0, 2))) {
            return false;
        }
        return FI_CHECK.charAt((int) value(number) % 31) == v.charAt(10);
    }

    // ---- Asia-Pacific ----

    /**
     * Indian Aadhaar: twelve digits, not starting with 0 or 1, Verhoeff check
     * digit.
     */
    public static boolean inAadhaar(String v) {
        if (v.length() != 12 || !allDigits(v) || v.charAt(0) < '2') {
            return false;
        }
        int c = 0;
        for (int i = 0; i < 12; i++) {
            c = VERHOEFF_D[c][VERHOEFF_P[i % 8][digit(v, 11 - i)]];
        }
        return c == 0;
    }

    /**
     * Chinese resident ID: 17 digits with a birth date, then a mod-11 check (0-9 or
     * X).
     */
    public static boolean cnResidentId(String v) {
        if (v.length() != 18 || !allDigits(v.substring(0, 17))) {
            return false;
        }
        if (!date(v.substring(10, 12), v.substring(12, 14)) || !v.startsWith("19", 6) && !v.startsWith("20", 6)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += digit(v, i) * CN_WEIGHTS[i];
        }
        return CN_CHECK.charAt(sum % 11) == v.charAt(17);
    }

    /** Japanese My Number: twelve digits, mod-11 check digit. */
    public static boolean jpMyNumber(String v) {
        if (v.length() != 12 || !allDigits(v)) {
            return false;
        }
        int sum = 0;
        for (int n = 1; n <= 11; n++) {
            int p = digit(v, 11 - n);
            int q = n <= 6 ? n + 1 : n - 5;
            sum += p * q;
        }
        int remainder = sum % 11;
        return (remainder <= 1 ? 0 : 11 - remainder) == digit(v, 11);
    }

    /**
     * Korean RRN, YYMMDD then seven digits: a date and a sex/century digit 1-8.
     * Numbers issued since October 2020 carry no check digit, so none is tested.
     */
    public static boolean krRrn(String v) {
        return v.length() == 13 && allDigits(v) && v.charAt(6) >= '1' && v.charAt(6) <= '8'
                && date(v.substring(2, 4), v.substring(4, 6));
    }

    /**
     * Singapore NRIC or FIN (S, T, F or G series), weighted mod-11 check letter.
     */
    public static boolean sgNric(String v) {
        if (v.length() != 9 || !allDigits(v.substring(1, 8))) {
            return false;
        }
        char series = v.charAt(0);
        int[] weights = {2, 7, 6, 5, 4, 3, 2};
        int sum = series == 'T' || series == 'G' ? 4 : 0;
        for (int i = 0; i < 7; i++) {
            sum += digit(v, i + 1) * weights[i];
        }
        String table = switch (series) {
            case 'S', 'T' -> "JZIHGFEDCBA";
            case 'F', 'G' -> "XWUTRQPNMLK";
            default -> null;
        };
        return table != null && table.charAt(sum % 11) == v.charAt(8);
    }

    /** Australian TFN: nine digits, weighted sum divisible by 11. */
    public static boolean auTfn(String v) {
        if (v.length() != 9 || !allDigits(v)) {
            return false;
        }
        int[] weights = {1, 4, 3, 7, 5, 8, 6, 9, 10};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += digit(v, i) * weights[i];
        }
        return sum % 11 == 0;
    }

    /**
     * Australian Medicare number: ten digits (an eleventh, the individual
     * reference, is optional), first 2-6, the ninth a weighted mod-10 check.
     */
    public static boolean auMedicare(String v) {
        if (v.length() < 10 || v.length() > 11 || !allDigits(v) || v.charAt(0) < '2' || v.charAt(0) > '6') {
            return false;
        }
        int[] weights = {1, 3, 7, 9, 1, 3, 7, 9};
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += digit(v, i) * weights[i];
        }
        return sum % 10 == digit(v, 8);
    }

    /**
     * New Zealand IRD number: eight or nine digits in range, mod-11 with fallback
     * weights.
     */
    public static boolean nzIrd(String v) {
        if (v.length() < 8 || v.length() > 9 || !allDigits(v)) {
            return false;
        }
        String nine = v.length() == 8 ? "0" + v : v;
        long number = value(nine);
        if (number <= 10_000_000L || number >= 150_000_000L) {
            return false;
        }
        int check = irdCheck(nine, new int[]{3, 2, 7, 6, 5, 4, 3, 2});
        if (check == 10) {
            check = irdCheck(nine, new int[]{7, 4, 3, 2, 5, 2, 7, 6});
        }
        return check != 10 && check == digit(nine, 8);
    }

    private static int irdCheck(String nine, int[] weights) {
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += digit(nine, i) * weights[i];
        }
        int remainder = sum % 11;
        return remainder == 0 ? 0 : 11 - remainder;
    }

    // ---- Africa ----

    /** South African ID: thirteen digits, YYMMDD, citizenship 0-2, Luhn. */
    public static boolean zaId(String v) {
        return v.length() == 13 && allDigits(v) && v.charAt(10) <= '2' && date(v.substring(2, 4), v.substring(4, 6))
                && luhn(v);
    }

    // ---- universal ----

    /**
     * The second line of a TD3 machine-readable zone (44 characters): the check
     * digits of the document number, the birth date, the expiry date and the whole
     * line.
     */
    public static boolean passportMrz(String v) {
        if (v.length() != 44) {
            return false;
        }
        return mrzCheck(v, 0, 9) && mrzCheck(v, 13, 19) && mrzCheck(v, 21, 27)
                && mrzDigit(v.substring(0, 10) + v.substring(13, 20) + v.substring(21, 43)) == v.charAt(43) - '0';
    }

    private static boolean mrzCheck(String v, int from, int checkAt) {
        return mrzDigit(v.substring(from, checkAt)) == v.charAt(checkAt) - '0';
    }

    /**
     * ICAO 9303 check digit, the filler {@code <} counting as 0; -1 on anything
     * else.
     */
    static int mrzDigit(String value) {
        int[] weights = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int v;
            if (c >= '0' && c <= '9') {
                v = c - '0';
            } else if (c >= 'A' && c <= 'Z') {
                v = c - 'A' + 10;
            } else if (c == '<') {
                v = 0;
            } else {
                return -1;
            }
            sum += v * weights[i % 3];
        }
        return sum % 10;
    }

    /** An IPv6 address in full or compressed ({@code ::}) form. */
    public static boolean ipv6(String v) {
        int compressed = v.indexOf("::");
        if (compressed >= 0 && v.indexOf("::", compressed + 1) >= 0) {
            return false;
        }
        String[] groups = v.split(":", -1);
        int nonEmpty = 0;
        for (String group : groups) {
            if (group.length() > 4) {
                return false;
            }
            if (!group.isEmpty()) {
                nonEmpty++;
            }
        }
        if (compressed < 0) {
            return groups.length == 8 && nonEmpty == 8;
        }
        return nonEmpty >= 1 && nonEmpty <= 7 && groups.length <= 9;
    }

    // ---- helpers ----

    /** Luhn over all of {@code digits}, any length. */
    static boolean luhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digit(digits, i);
            if (doubleIt) {
                d = d * 2 > 9 ? d * 2 - 9 : d * 2;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    /** A plausible month and day (two digits each); years are not range-checked. */
    static boolean date(String month, String day) {
        if (!allDigits(month) || !allDigits(day)) {
            return false;
        }
        int m = (int) value(month);
        int d = (int) value(day);
        return m >= 1 && m <= 12 && d >= 1 && d <= 31;
    }

    /**
     * The number {@code digits} spells. Callers pass ASCII digits (a pattern or
     * {@link #allDigits} guarantees them); computed digit by digit, so nothing here
     * can throw on odd input.
     */
    static long value(String digits) {
        long n = 0;
        for (int i = 0; i < digits.length(); i++) {
            n = n * 10 + digit(digits, i);
        }
        return n;
    }

    private static int digit(String v, int i) {
        return v.charAt(i) - '0';
    }

    static boolean allDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
