/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import ai.labs.eddi.utils.RegexDeadline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pattern-and-checksum recognizers behind {@link BuiltinPiiDetector} and
 * the {@code pii-redaction} guardrail — one per entity type, so both find
 * exactly the same values.
 * <p>
 * Every repetition in these patterns is bounded, so a scan stays linear in the
 * text, and every match runs under the caller's deadline
 * ({@link RegexDeadline}): past it, {@link #find} throws
 * {@link RegexDeadline.MatchDeadlineExceededException} and the caller treats
 * the text as undecided, never as clean.
 * <p>
 * {@link #entities()} lists the types in the order the {@code pii-redaction}
 * guardrail applies them: {@code EMAIL} first, so an address is redacted whole
 * even when its local part is an IBAN or a tax ID; then the structured
 * identifiers, so the looser {@code PHONE} pattern cannot eat the digit groups
 * of an IBAN or a tax ID before those are recognised; card before phone for the
 * same reason.
 */
public final class BuiltinRecognizers {

    /** The {@link PiiSpan#detectorId()} of every span found here. */
    public static final String DETECTOR_ID = "builtin";

    /**
     * A deadline that never passes, for callers that rely on the patterns being
     * linear rather than on a budget.
     */
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    /** How far before a value its keyword ("geb.", "Ausweis") may stand. */
    static final int TRIGGER_WINDOW = 40;

    /** Score of a value that passed a check digit. */
    private static final double CHECKED = 1.0;
    /** Score of a value found by pattern and keyword only. */
    private static final double CONTEXTUAL = 0.85;

    /** Finds the values of one entity type. */
    @FunctionalInterface
    private interface Recognizer {
        List<PiiSpan> find(String text, long deadline);
    }

    /** Decides on one raw match: the value's {@code [start, end)}, or null. */
    @FunctionalInterface
    private interface Acceptor {
        int[] accept(String text, int start, int end, long deadline);
    }

    /** Where the scan resumes after a rejected match; must be past its start. */
    @FunctionalInterface
    private interface Resume {
        int after(String text, int start, int end);
    }

    private static final Resume AFTER_MATCH = (text, start, end) -> end;

    private static final Pattern EMAIL = Pattern
            .compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){0,8}\\.[A-Za-z]{2,63}");
    private static final Pattern US_SSN = Pattern.compile("\\b(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");
    // Grouped national and international forms, plus the unspaced international
    // (E.164) form "+15551234567". An unspaced number without "+" is left alone:
    // it cannot be told apart from an order or account number.
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w+(])(?:\\+\\d{1,3}[ -]?)?(?:\\(\\d{2,4}\\)[ -]?|\\d{2,4}[ -])\\d{3,4}[ -]?\\d{3,4}\\b" + "|(?<![\\w+])\\+\\d{8,15}\\b");
    // Upper-case, as printed; single spaces between characters allowed (groups of
    // four). A run may swallow a following upper-case word; the acceptor tries the
    // shorter windows.
    private static final Pattern IBAN = Pattern.compile("(?<![A-Za-z0-9])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}(?![A-Za-z0-9])");
    private static final Pattern DE_TAX_ID = Pattern.compile("(?<![\\d+])[1-9]\\d ?\\d{3} ?\\d{3} ?\\d{3}(?!\\d)");
    // Serial alphabet of German ID cards and passports: digits and the consonants
    // C F G H J K L M N P R T V W X Y Z; nine characters, optionally the ICAO check
    // digit as a tenth.
    private static final Pattern DE_ID_CARD = Pattern.compile("(?<![A-Za-z0-9])[CFGHJKLMNPRTVWXYZ][CFGHJKLMNPRTVWXYZ0-9]{8}[0-9]?(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DE_ID_CARD_TRIGGER = Pattern.compile(
            "\\b(?:personalausweis|ausweis|reisepass|passnummer|pass\\b|pass-nr|passport|id[ -]?card|identity card|dokumentennummer"
                    + "|document number|seriennummer)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DE_SOCIAL_SECURITY = Pattern
            .compile("(?<![A-Za-z0-9])\\d{2} ?\\d{6} ?[A-Za-z] ?\\d{2} ?\\d(?![A-Za-z0-9])");
    private static final Pattern DATE = Pattern.compile("(?<![\\d.])(?:(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4}|\\d{2})" //
            + "|(\\d{4})-(\\d{2})-(\\d{2})" //
            + "|(\\d{1,2})\\.? ?(jan(?:uar|uary)?|feb(?:ruar|ruary)?|m(?:ä|ae)rz|mar(?:ch)?|apr(?:il)?|mai|may|jun[ei]?|jul[iy]?"
            + "|aug(?:ust)?|sep(?:t(?:ember)?)?|okt(?:ober)?|oct(?:ober)?|nov(?:ember)?|de[zc](?:ember)?)\\.? (\\d{4}))(?![\\d])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BIRTH_TRIGGER = Pattern.compile(
            "\\b(?:geb\\.|geboren|geburtsdatum|geburtstag|born\\b|date of birth|birth ?date|birthday|dob\\b|d\\.o\\.b\\.)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Map<String, Recognizer> RECOGNIZERS = new LinkedHashMap<>();

    static {
        RECOGNIZERS.put(PiiEntities.EMAIL, scanner(PiiEntities.EMAIL, EMAIL, CHECKED, (t, s, e, d) -> new int[]{s, e}, AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.IBAN, scanner(PiiEntities.IBAN, IBAN, CHECKED, BuiltinRecognizers::acceptIban, (t, s, e) -> s + 1));
        RECOGNIZERS.put(PiiEntities.DE_TAX_ID,
                scanner(PiiEntities.DE_TAX_ID, DE_TAX_ID, CHECKED, (t, s, e, d) -> PiiChecksums.deTaxId(compact(t, s, e)) ? new int[]{s, e} : null,
                        AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.DE_ID_CARD,
                scanner(PiiEntities.DE_ID_CARD, DE_ID_CARD, CONTEXTUAL, BuiltinRecognizers::acceptIdCard, AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.DE_SOCIAL_SECURITY, scanner(PiiEntities.DE_SOCIAL_SECURITY, DE_SOCIAL_SECURITY, CHECKED,
                (t, s, e, d) -> PiiChecksums.deSocialSecurity(compact(t, s, e)) ? new int[]{s, e} : null, AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.DATE_OF_BIRTH,
                scanner(PiiEntities.DATE_OF_BIRTH, DATE, CONTEXTUAL, BuiltinRecognizers::acceptBirthDate, AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.US_SSN, scanner(PiiEntities.US_SSN, US_SSN, CHECKED, (t, s, e, d) -> new int[]{s, e}, AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.CREDIT_CARD,
                scanner(PiiEntities.CREDIT_CARD, CREDIT_CARD, CHECKED, (t, s, e, d) -> cardWithin(t, s, e), BuiltinRecognizers::afterFirstSeparator));
        RECOGNIZERS.put(PiiEntities.PHONE, scanner(PiiEntities.PHONE, PHONE, CONTEXTUAL, (t, s, e, d) -> new int[]{s, e}, AFTER_MATCH));
    }

    private BuiltinRecognizers() {
    }

    /** Every supported entity type, in application order. */
    public static List<String> entities() {
        return List.copyOf(RECOGNIZERS.keySet());
    }

    public static boolean supports(String entity) {
        return RECOGNIZERS.containsKey(entity);
    }

    /**
     * The values of {@code entity} in {@code text}, left to right and not
     * overlapping each other.
     *
     * @throws IllegalArgumentException
     *             for an entity type this class does not support
     * @throws RegexDeadline.MatchDeadlineExceededException
     *             once {@code deadline} ({@link System#nanoTime()}) has passed
     */
    public static List<PiiSpan> find(String entity, String text, long deadline) {
        Recognizer recognizer = RECOGNIZERS.get(entity);
        if (recognizer == null) {
            throw new IllegalArgumentException("unsupported entity type " + entity);
        }
        return recognizer.find(text, deadline);
    }

    private static Recognizer scanner(String entity, Pattern pattern, double score, Acceptor acceptor, Resume resume) {
        return (text, deadline) -> {
            Matcher matcher = RegexDeadline.matcher(pattern, text, deadline);
            List<PiiSpan> spans = new ArrayList<>();
            int from = 0;
            while (from <= text.length() && matcher.find(from)) {
                int start = matcher.start();
                int end = matcher.end();
                from = end;
                int[] value = acceptor.accept(text, start, end, deadline);
                if (value == null) {
                    from = Math.max(resume.after(text, start, end), start + 1);
                    continue;
                }
                spans.add(new PiiSpan(value[0], value[1], entity, score, DETECTOR_ID));
            }
            return spans;
        };
    }

    // ---- IBAN ----

    /**
     * The longest window of the run, from its start to its end or to one of its
     * inner spaces, that is a valid IBAN: a run may have swallowed a following
     * upper-case word ("… 0130 00 OK").
     */
    private static int[] acceptIban(String text, int start, int end, long deadline) {
        for (int e = end; e > start; e--) {
            if (e != end && text.charAt(e) != ' ') {
                continue;
            }
            if (PiiChecksums.iban(compact(text, start, e))) {
                return new int[]{start, e};
            }
        }
        return null;
    }

    // ---- German ID card ----

    private static int[] acceptIdCard(String text, int start, int end, long deadline) {
        String value = text.substring(start, end).toUpperCase(Locale.ROOT);
        if (value.chars().noneMatch(Character::isDigit)) {
            return null;
        }
        if (value.length() == 10 && PiiChecksums.icaoCheckDigit(value.substring(0, 9)) != value.charAt(9) - '0') {
            return null;
        }
        return hasTriggerBefore(DE_ID_CARD_TRIGGER, text, start, deadline) ? new int[]{start, end} : null;
    }

    // ---- date of birth ----

    private static int[] acceptBirthDate(String text, int start, int end, long deadline) {
        Matcher date = DATE.matcher(text.substring(start, end));
        if (!date.matches() || !plausibleDate(date)) {
            return null;
        }
        return hasTriggerBefore(BIRTH_TRIGGER, text, start, deadline) ? new int[]{start, end} : null;
    }

    private static boolean plausibleDate(Matcher date) {
        if (date.group(1) != null) {
            int a = Integer.parseInt(date.group(1));
            int b = Integer.parseInt(date.group(2));
            // Day-month or month-day: either reading must be a date.
            return a >= 1 && b >= 1 && (a <= 31 && b <= 12 || a <= 12 && b <= 31) && plausibleYear(date.group(3));
        }
        if (date.group(4) != null) {
            int month = Integer.parseInt(date.group(5));
            int day = Integer.parseInt(date.group(6));
            return month >= 1 && month <= 12 && day >= 1 && day <= 31 && plausibleYear(date.group(4));
        }
        int day = Integer.parseInt(date.group(7));
        return day >= 1 && day <= 31 && plausibleYear(date.group(9));
    }

    private static boolean plausibleYear(String year) {
        if (year.length() == 2) {
            return true;
        }
        int y = Integer.parseInt(year);
        return y >= 1900 && y <= 2100;
    }

    private static boolean hasTriggerBefore(Pattern trigger, String text, int start, long deadline) {
        int from = Math.max(0, start - TRIGGER_WINDOW);
        return RegexDeadline.matcher(trigger, text.substring(from, start), deadline).find();
    }

    // ---- payment card ----

    /**
     * The card number inside a digit run the card pattern matched, as
     * {@code [start, end)} in {@code text}, or {@code null} if there is none.
     * <p>
     * The pattern is greedy, so a card followed by its CVV or expiry ("4111 1111
     * 1111 1111 123") matches as one run, which fails the Luhn check as a whole.
     * The card is then looked for among the shorter windows of the run that start
     * at its beginning or just after a separator and end at its end or just before
     * one; the longest Luhn-valid window of 13 to 19 digits wins, the earliest
     * among equals. Windows never split a digit group, so an unseparated long
     * number (an order or account number) is still only ever checked as a whole and
     * cannot become a card by chance.
     */
    static int[] cardWithin(String text, int runStart, int runEnd) {
        if (PiiChecksums.luhn(text.substring(runStart, runEnd))) {
            return new int[]{runStart, runEnd};
        }
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        starts.add(runStart);
        for (int i = runStart; i < runEnd; i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '-') {
                ends.add(i);
                starts.add(i + 1);
            }
        }
        ends.add(runEnd);
        int[] best = null;
        int bestDigits = 0;
        for (int s : starts) {
            for (int e : ends) {
                if (e <= s) {
                    continue;
                }
                int digits = countDigits(text, s, e);
                if (digits < PiiChecksums.CARD_MIN_DIGITS || digits > PiiChecksums.CARD_MAX_DIGITS || digits <= bestDigits) {
                    continue;
                }
                if (PiiChecksums.luhn(text.substring(s, e))) {
                    best = new int[]{s, e};
                    bestDigits = digits;
                }
            }
        }
        return best;
    }

    /**
     * The greedy run may have started on digit groups in front of the card ("ref
     * 1234 4111 1111 1111 1111" is 20 digits, so the run stops short of the card's
     * last group). Look again from the next group; each retry starts further right,
     * so the scan stays linear.
     */
    private static int afterFirstSeparator(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '-') {
                return i + 1;
            }
        }
        return end;
    }

    private static int countDigits(String text, int start, int end) {
        int digits = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            }
        }
        return digits;
    }

    /** {@code text[start, end)} without spaces. */
    private static String compact(String text, int start, int end) {
        StringBuilder out = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c != ' ') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
