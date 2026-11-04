/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import ai.labs.eddi.utils.RegexDeadline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
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
    // Birth keywords in the languages of the supported countries. Scripts Java's
    // ASCII \b cannot see (CJK) are listed apart from the word-boundary group.
    private static final Pattern BIRTH_TRIGGER = trigger(
            "geb\\.|geboren|geburtsdatum|geburtstag|born\\b|date of birth|birth ?date|birthday|dob\\b|d\\.o\\.b\\."
                    + "|née? le|date de naissance|fecha de nacimiento|nacid[oa]|data di nascita|nat[oa] il|geboortedatum"
                    + "|data de nascimento|nascid[oa]|data urodzenia|urodzon[ya]|födelsedatum|född|fødselsdato|født"
                    + "|syntymäaika|syntynyt",
            "生年月日|出生日期|생년월일");

    // ---- universal ----

    private static final Pattern IPV4 = Pattern
            .compile("(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?!\\d|\\.\\d)");
    private static final Pattern IPV6 = Pattern.compile("(?<![0-9A-Za-z:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?![0-9A-Za-z:])");
    private static final Pattern PASSPORT_MRZ = Pattern
            .compile("(?<![A-Z0-9<])[A-Z0-9<]{9}\\d[A-Z<]{3}\\d{6}\\d[MFX<]\\d{6}\\d[A-Z0-9<]{14}[0-9<]\\d(?![A-Z0-9<])");

    // ---- national ----

    private static final Pattern US_ITIN = bounded("9\\d{2}-(?:5\\d|6[0-5]|7\\d|8[0-8]|9[0-24-9])-\\d{4}");
    private static final Pattern CA_SIN = bounded("\\d{3}[ -]?\\d{3}[ -]?\\d{3}");
    private static final Pattern CA_SIN_TRIGGER = trigger("sin\\b|social insurance|nas\\b|assurance sociale", null);
    private static final Pattern BR_CPF = bounded("\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}");
    private static final Pattern MX_CURP = boundedCi("[A-Z][AEIOUX][A-Z]{2}\\d{6}[HMX](?:AS|BC|BS|CC|CL|CM|CS|CH|DF|CX|DG|GT|GR|HG|JC|MC|MN|MS|NT|NL"
            + "|OC|PL|QT|QR|SP|SL|SR|TC|TS|TL|VZ|YN|ZS|NE)[B-DF-HJ-NP-TV-Z]{3}[A-Z\\d]\\d");
    private static final Pattern GB_NINO = bounded("[A-CEGHJ-PR-TW-Z][A-CEGHJ-NPR-TW-Z] ?\\d{2} ?\\d{2} ?\\d{2} ?[A-D]");
    private static final Pattern GB_NHS = bounded("\\d{3}[ -]?\\d{3}[ -]?\\d{4}");
    private static final Pattern GB_NHS_TRIGGER = trigger("nhs", null);
    private static final Pattern IE_PPS = boundedCi("\\d{7}[A-W][A-W]?");
    private static final Pattern FR_NIR = boundedCi("[1-478] ?\\d{2} ?\\d{2} ?(?:\\d{2}|2[AB]) ?\\d{3} ?\\d{3} ?\\d{2}");
    private static final Pattern IT_FISCAL_CODE = boundedCi("[A-Z]{6}[0-9LMNP-V]{2}[ABCDEHLMPRST][0-9LMNP-V]{2}[A-Z][0-9LMNP-V]{3}[A-Z]");
    private static final Pattern ES_DNI = boundedCi("(?:\\d{8}|[XYZ][- ]?\\d{7})[- ]?[A-Z]");
    private static final Pattern PT_NIF = bounded("[1-35-9]\\d{2} ?\\d{3} ?\\d{3}");
    private static final Pattern PT_NIF_TRIGGER = trigger("nif\\b|contribuinte|número fiscal|numero fiscal|tax number", null);
    private static final Pattern NL_BSN = bounded("\\d{4}\\.\\d{2}\\.\\d{3}|\\d{3} \\d{3} \\d{3}|\\d{9}");
    private static final Pattern NL_BSN_TRIGGER = trigger("bsn|burgerservicenummer|sofi-?nummer|citizen service number", null);
    private static final Pattern BE_NATIONAL_NUMBER = bounded("\\d{2}\\.?\\d{2}\\.?\\d{2}[-. ]?\\d{3}\\.?\\d{2}");
    private static final Pattern AT_SOCIAL_SECURITY = bounded("\\d{4} ?\\d{6}");
    private static final Pattern AT_SOCIAL_SECURITY_TRIGGER = trigger(
            "svnr|sv-nr|sv-nummer|sozialversicherungsnummer|versicherungsnummer|social security", null);
    private static final Pattern CH_AHV = bounded("756\\.?\\d{4}\\.?\\d{4}\\.?\\d{2}");
    private static final Pattern PL_PESEL = bounded("\\d{11}");
    private static final Pattern PL_PESEL_TRIGGER = trigger("pesel", null);
    private static final Pattern SE_PERSONNUMMER = bounded("(?:19|20)?\\d{6}[-+]\\d{4}|(?:19|20)\\d{10}");
    private static final Pattern DK_CPR = bounded("\\d{6}-?\\d{4}");
    private static final Pattern DK_CPR_TRIGGER = trigger("cpr|personnummer", null);
    private static final Pattern FI_HETU = boundedCi("\\d{6}[-+A-FU-Y]\\d{3}[0-9A-Y]");
    private static final Pattern IN_AADHAAR = bounded("[2-9]\\d{3} ?\\d{4} ?\\d{4}");
    private static final Pattern IN_AADHAAR_TRIGGER = trigger("aadhaa?r|uidai|uid\\b", "आधार");
    private static final Pattern IN_PAN = bounded("[A-Z]{3}[ABCFGHJLPT][A-Z]\\d{4}[A-Z]");
    private static final Pattern CN_RESIDENT_ID = boundedCi("[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])\\d{2}\\d{3}[\\dX]");
    private static final Pattern JP_MY_NUMBER = bounded("\\d{4} ?\\d{4} ?\\d{4}");
    private static final Pattern JP_MY_NUMBER_TRIGGER = trigger("my ?number|individual number", "マイナンバー|個人番号");
    private static final Pattern KR_RRN = bounded("\\d{6}-[1-8]\\d{6}");
    private static final Pattern SG_NRIC = boundedCi("[STFG]\\d{7}[A-Z]");
    private static final Pattern AU_TFN = bounded("\\d{3} ?\\d{3} ?\\d{3}");
    private static final Pattern AU_TFN_TRIGGER = trigger("tfn|tax file", null);
    private static final Pattern AU_MEDICARE = bounded("[2-6]\\d{3} ?\\d{5} ?\\d{1,2}");
    private static final Pattern AU_MEDICARE_TRIGGER = trigger("medicare", null);
    private static final Pattern NZ_IRD = bounded("\\d{2,3}-?\\d{3}-?\\d{3}");
    private static final Pattern NZ_IRD_TRIGGER = trigger("ird|inland revenue", null);
    private static final Pattern ZA_ID = bounded("\\d{13}");

    private static final Map<String, Recognizer> RECOGNIZERS = new LinkedHashMap<>();

    static {
        // The pii-redaction guardrail applies these one after another in this order:
        // e-mail first, then the long and checked formats, then the shorter and
        // keyword-bound ones, and the loose card and phone patterns last.
        RECOGNIZERS.put(PiiEntities.EMAIL, scanner(PiiEntities.EMAIL, EMAIL, CHECKED, (t, s, e, d) -> new int[]{s, e}, AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.IBAN, scanner(PiiEntities.IBAN, IBAN, CHECKED, BuiltinRecognizers::acceptIban, (t, s, e) -> s + 1));
        RECOGNIZERS.put(PiiEntities.PASSPORT_MRZ, scanner(PiiEntities.PASSPORT_MRZ, PASSPORT_MRZ, CHECKED,
                (t, s, e, d) -> NationalChecksums.passportMrz(t.substring(s, e)) ? new int[]{s, e} : null, AFTER_MATCH));
        checked(PiiEntities.IT_FISCAL_CODE, IT_FISCAL_CODE, NationalChecksums::itFiscalCode);
        checked(PiiEntities.MX_CURP, MX_CURP, NationalChecksums::mxCurp);
        checked(PiiEntities.CN_RESIDENT_ID, CN_RESIDENT_ID, NationalChecksums::cnResidentId);
        checked(PiiEntities.FR_NIR, FR_NIR, NationalChecksums::frNir);
        checked(PiiEntities.CH_AHV, CH_AHV, NationalChecksums::chAhv);
        checked(PiiEntities.ZA_ID, ZA_ID, NationalChecksums::zaId);
        checked(PiiEntities.KR_RRN, KR_RRN, NationalChecksums::krRrn);
        checked(PiiEntities.BR_CPF, BR_CPF, NationalChecksums::brCpf);
        checked(PiiEntities.BE_NATIONAL_NUMBER, BE_NATIONAL_NUMBER, NationalChecksums::beNationalNumber);
        checked(PiiEntities.SE_PERSONNUMMER, SE_PERSONNUMMER, v -> NationalChecksums.sePersonnummer(v.length() == 12 ? v.substring(2) : v));
        // The century sign (-, +, A …) is part of the value, so it is not normalized
        // away.
        RECOGNIZERS.put(PiiEntities.FI_HETU, scanner(PiiEntities.FI_HETU, FI_HETU, CHECKED,
                (t, s, e, d) -> NationalChecksums.fiHetu(t.substring(s, e).toUpperCase(Locale.ROOT)) ? new int[]{s, e} : null, AFTER_MATCH));
        keyed(PiiEntities.PL_PESEL, PL_PESEL, PL_PESEL_TRIGGER, NationalChecksums::plPesel);
        RECOGNIZERS.put(PiiEntities.DE_TAX_ID,
                scanner(PiiEntities.DE_TAX_ID, DE_TAX_ID, CHECKED, (t, s, e, d) -> PiiChecksums.deTaxId(compact(t, s, e)) ? new int[]{s, e} : null,
                        AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.DE_ID_CARD,
                scanner(PiiEntities.DE_ID_CARD, DE_ID_CARD, CONTEXTUAL, BuiltinRecognizers::acceptIdCard, AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.DE_SOCIAL_SECURITY, scanner(PiiEntities.DE_SOCIAL_SECURITY, DE_SOCIAL_SECURITY, CHECKED,
                (t, s, e, d) -> PiiChecksums.deSocialSecurity(compact(t, s, e)) ? new int[]{s, e} : null, AFTER_MATCH));
        keyed(PiiEntities.DK_CPR, DK_CPR, DK_CPR_TRIGGER, NationalChecksums::dkCpr);
        keyed(PiiEntities.AT_SOCIAL_SECURITY, AT_SOCIAL_SECURITY, AT_SOCIAL_SECURITY_TRIGGER, NationalChecksums::atSocialSecurity);
        checked(PiiEntities.GB_NINO, GB_NINO, NationalChecksums::gbNino);
        keyed(PiiEntities.GB_NHS, GB_NHS, GB_NHS_TRIGGER, NationalChecksums::gbNhs);
        checked(PiiEntities.IE_PPS, IE_PPS, NationalChecksums::iePps);
        checked(PiiEntities.ES_DNI, ES_DNI, NationalChecksums::esDni);
        keyed(PiiEntities.PT_NIF, PT_NIF, PT_NIF_TRIGGER, NationalChecksums::ptNif);
        keyed(PiiEntities.NL_BSN, NL_BSN, NL_BSN_TRIGGER, NationalChecksums::nlBsn);
        keyed(PiiEntities.IN_AADHAAR, IN_AADHAAR, IN_AADHAAR_TRIGGER, NationalChecksums::inAadhaar);
        checked(PiiEntities.IN_PAN, IN_PAN, v -> true);
        keyed(PiiEntities.JP_MY_NUMBER, JP_MY_NUMBER, JP_MY_NUMBER_TRIGGER, NationalChecksums::jpMyNumber);
        checked(PiiEntities.SG_NRIC, SG_NRIC, NationalChecksums::sgNric);
        keyed(PiiEntities.AU_MEDICARE, AU_MEDICARE, AU_MEDICARE_TRIGGER, NationalChecksums::auMedicare);
        keyed(PiiEntities.AU_TFN, AU_TFN, AU_TFN_TRIGGER, NationalChecksums::auTfn);
        keyed(PiiEntities.NZ_IRD, NZ_IRD, NZ_IRD_TRIGGER, NationalChecksums::nzIrd);
        keyed(PiiEntities.CA_SIN, CA_SIN, CA_SIN_TRIGGER, NationalChecksums::caSin);
        checked(PiiEntities.US_ITIN, US_ITIN, v -> true);
        RECOGNIZERS.put(PiiEntities.DATE_OF_BIRTH,
                scanner(PiiEntities.DATE_OF_BIRTH, DATE, CONTEXTUAL, BuiltinRecognizers::acceptBirthDate, AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.US_SSN, scanner(PiiEntities.US_SSN, US_SSN, CHECKED, (t, s, e, d) -> new int[]{s, e}, AFTER_MATCH));
        RECOGNIZERS.put(PiiEntities.IP_ADDRESS, ipAddress());
        RECOGNIZERS.put(PiiEntities.CREDIT_CARD,
                scanner(PiiEntities.CREDIT_CARD, CREDIT_CARD, CHECKED, (t, s, e, d) -> cardWithin(t, s, e), BuiltinRecognizers::afterFirstSeparator));
        RECOGNIZERS.put(PiiEntities.PHONE, scanner(PiiEntities.PHONE, PHONE, CONTEXTUAL, (t, s, e, d) -> new int[]{s, e}, AFTER_MATCH));
    }

    /**
     * {@code body} as a whole token: no letter or digit directly before or after
     * it. Upper-case letters only, as these identifiers are printed.
     */
    private static Pattern bounded(String body) {
        return Pattern.compile("(?<![A-Za-z0-9])(?:" + body + ")(?![A-Za-z0-9])");
    }

    /** {@link #bounded} for identifiers also written in lower case. */
    private static Pattern boundedCi(String body) {
        return Pattern.compile("(?<![A-Za-z0-9])(?:" + body + ")(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    }

    /**
     * A keyword pattern: the {@code latin} alternatives start at a word boundary,
     * the {@code other} ones (scripts Java's ASCII word boundary cannot see) do
     * not.
     */
    private static Pattern trigger(String latin, String other) {
        String regex = "\\b(?:" + latin + ")" + (other != null ? "|" + other : "");
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Accepts a match when {@code valid} holds for its {@link #normalized} value.
     */
    private static void checked(String entity, Pattern pattern, Predicate<String> valid) {
        RECOGNIZERS.put(entity,
                scanner(entity, pattern, CHECKED, (t, s, e, d) -> valid.test(normalized(t, s, e)) ? new int[]{s, e} : null, AFTER_MATCH));
    }

    /**
     * {@link #checked}, and a keyword at most {@link #TRIGGER_WINDOW} characters
     * before the value — for identifiers whose check digit lets about one in ten
     * random numbers of the right length through.
     */
    private static void keyed(String entity, Pattern pattern, Pattern trigger, Predicate<String> valid) {
        RECOGNIZERS.put(entity, scanner(entity, pattern, CHECKED,
                (t, s, e, d) -> valid.test(normalized(t, s, e)) && hasTriggerBefore(trigger, t, s, d) ? new int[]{s, e} : null, AFTER_MATCH));
    }

    /** IPv4 and IPv6 addresses, left to right. */
    private static Recognizer ipAddress() {
        Recognizer v4 = scanner(PiiEntities.IP_ADDRESS, IPV4, CHECKED, (t, s, e, d) -> new int[]{s, e}, AFTER_MATCH);
        Recognizer v6 = scanner(PiiEntities.IP_ADDRESS, IPV6, CHECKED,
                (t, s, e, d) -> NationalChecksums.ipv6(t.substring(s, e)) ? new int[]{s, e} : null, AFTER_MATCH);
        return (text, deadline) -> {
            List<PiiSpan> spans = new ArrayList<>(v4.find(text, deadline));
            for (PiiSpan span : v6.find(text, deadline)) {
                if (spans.stream().noneMatch(other -> other.overlaps(span.start(), span.end()))) {
                    spans.add(span);
                }
            }
            spans.sort(Comparator.comparingInt(PiiSpan::start));
            return spans;
        };
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

    /** The letters and digits of {@code text[start, end)}, upper-case. */
    private static String normalized(String text, int start, int end) {
        StringBuilder out = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toUpperCase(c));
            }
        }
        return out.toString();
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
