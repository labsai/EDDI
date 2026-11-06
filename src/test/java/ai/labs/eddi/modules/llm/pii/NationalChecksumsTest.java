/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.function.Predicate;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every validator on compact input, including each way a value is rejected:
 * wrong length, a non-digit, an implausible date, a wrong check digit. Valid
 * values are published examples.
 */
class NationalChecksumsTest {

    private static final Map<String, Predicate<String>> VALIDATORS = Map.ofEntries(entry("caSin", NationalChecksums::caSin),
            entry("brCpf", NationalChecksums::brCpf), entry("mxCurp", NationalChecksums::mxCurp), entry("gbNino", NationalChecksums::gbNino),
            entry("gbNhs", NationalChecksums::gbNhs), entry("iePps", NationalChecksums::iePps), entry("frNir", NationalChecksums::frNir),
            entry("itFiscalCode", NationalChecksums::itFiscalCode), entry("esDni", NationalChecksums::esDni),
            entry("ptNif", NationalChecksums::ptNif),
            entry("nlBsn", NationalChecksums::nlBsn), entry("beNationalNumber", NationalChecksums::beNationalNumber),
            entry("atSocialSecurity", NationalChecksums::atSocialSecurity), entry("chAhv", NationalChecksums::chAhv),
            entry("plPesel", NationalChecksums::plPesel), entry("sePersonnummer", NationalChecksums::sePersonnummer),
            entry("dkCpr", NationalChecksums::dkCpr), entry("fiHetu", NationalChecksums::fiHetu), entry("inAadhaar", NationalChecksums::inAadhaar),
            entry("cnResidentId", NationalChecksums::cnResidentId), entry("jpMyNumber", NationalChecksums::jpMyNumber),
            entry("krRrn", NationalChecksums::krRrn), entry("sgNric", NationalChecksums::sgNric), entry("auTfn", NationalChecksums::auTfn),
            entry("auMedicare", NationalChecksums::auMedicare), entry("nzIrd", NationalChecksums::nzIrd), entry("zaId", NationalChecksums::zaId),
            entry("passportMrz", NationalChecksums::passportMrz), entry("ipv6", NationalChecksums::ipv6));

    @ParameterizedTest(name = "{0}({1}) = {2}")
    @DisplayName("validators accept published values and reject each kind of malformed one")
    @CsvSource({
            // Canada
            "caSin, 046454286, true", "caSin, 04645428, false", "caSin, 04645428A, false", "caSin, 046454287, false",
            // Brazil
            "brCpf, 52998224725, true", "brCpf, 5299822472, false", "brCpf, 5299822472A, false", "brCpf, 11111111111, false",
            "brCpf, 52998224735, false", "brCpf, 52998224726, false",
            // Mexico
            "mxCurp, HEGG560427MVZRRL04, true", "mxCurp, MAHJ280603MSPRRV09, true", "mxCurp, HEGG560427MVZRRL0, false",
            "mxCurp, HEGG561327MVZRRL04, false", "mxCurp, HEGG560427MVZRRL#4, false", "mxCurp, HEGG560427MVZRRL05, false",
            // United Kingdom
            "gbNino, AB123456C, true", "gbNino, AB123456, false", "gbNino, BG123456C, false", "gbNhs, 9434765919, true",
            "gbNhs, 0000000000, true", "gbNhs, 943476591, false", "gbNhs, 943476591A, false", "gbNhs, 9434765918, false",
            "gbNhs, 0008000000, false",
            // Ireland
            "iePps, 1234567T, true", "iePps, 1234567TW, true", "iePps, 1234567FA, true", "iePps, 1234567TA, false", "iePps, 1234567, false",
            "iePps, 1234567TAB, false", "iePps, 123456AT, false",
            // France
            "frNir, 255081416802538, true", "frNir, 25508141680253, false", "frNir, 255082A16802538, false", "frNir, 255082B16802538, false",
            "frNir, 2550X1416802538, false", "frNir, 2550814168025AB, false", "frNir, 255081416802539, false",
            // Italy
            "itFiscalCode, RSSMRA85T10A562S, true", "itFiscalCode, RSSMRA85T10A562, false", "itFiscalCode, RSSMRA85T10A56#S, false",
            "itFiscalCode, RSSMRA85T10A562T, false",
            // Spain
            "esDni, 12345678Z, true", "esDni, X1234567L, true", "esDni, 1234567Z, false", "esDni, 1234A678Z, false", "esDni, 12345678A, false",
            // Portugal
            "ptNif, 123456789, true", "ptNif, 500000000, true", "ptNif, 12345678, false", "ptNif, 12345678A, false", "ptNif, 023456789, false",
            "ptNif, 423456789, false", "ptNif, 123456788, false",
            // Netherlands
            "nlBsn, 111222333, true", "nlBsn, 11122233, false", "nlBsn, 11122233A, false", "nlBsn, 000000000, false", "nlBsn, 111222334, false",
            // Belgium: born before and from 2000
            "beNationalNumber, 93051822361, true", "beNationalNumber, 00010100105, true", "beNationalNumber, 9305182236, false",
            "beNationalNumber, 9305182236A, false", "beNationalNumber, 93051822362, false",
            // Austria
            "atSocialSecurity, 1237010180, true", "atSocialSecurity, 123701018, false", "atSocialSecurity, 123701018A, false",
            "atSocialSecurity, 0237010180, false", "atSocialSecurity, 1238010180, false", "atSocialSecurity, 1000000003, false",
            // Switzerland
            "chAhv, 7569217076985, true", "chAhv, 756921707698, false", "chAhv, 756921707698A, false", "chAhv, 7559217076985, false",
            "chAhv, 7569217076986, false",
            // Poland
            "plPesel, 44051401359, true", "plPesel, 4405140135, false", "plPesel, 4405140135A, false", "plPesel, 44151401359, false",
            "plPesel, 44001401359, false", "plPesel, 44050001359, false", "plPesel, 44053201359, false", "plPesel, 44051401358, false",
            // Sweden, a coordination number included
            "sePersonnummer, 8112289874, true", "sePersonnummer, 7010632391, true", "sePersonnummer, 811228987, false",
            "sePersonnummer, 811228987A, false", "sePersonnummer, 8113289874, false", "sePersonnummer, 8112289875, false",
            // Denmark
            "dkCpr, 0707614285, true", "dkCpr, 070761428, false", "dkCpr, 070761428A, false", "dkCpr, 3207614285, false",
            // Finland
            "fiHetu, 131052-308T, true", "fiHetu, 131052-308, false", "fiHetu, 131052Z308T, false", "fiHetu, 131052X308T, true",
            "fiHetu, 13105A-308T, false",
            "fiHetu, 131352-308T, false", "fiHetu, 131052-308U, false",
            // India
            "inAadhaar, 234123412346, true", "inAadhaar, 23412341234, false", "inAadhaar, 23412341234A, false", "inAadhaar, 134123412346, false",
            "inAadhaar, 234123412345, false",
            // China
            "cnResidentId, 11010519491231002X, true", "cnResidentId, 11010519491231002, false", "cnResidentId, 1101051949123100AX, false",
            "cnResidentId, 110105194913310021, false", "cnResidentId, 110105184912310021, false", "cnResidentId, 110105194912310021, false",
            // Japan
            "jpMyNumber, 123456789018, true", "jpMyNumber, 100000063350, true", "jpMyNumber, 12345678901, false",
            "jpMyNumber, 12345678901A, false", "jpMyNumber, 123456789019, false",
            // Korea
            "krRrn, 9001011234567, true", "krRrn, 900101123456, false", "krRrn, 900101123456A, false", "krRrn, 9001019234567, false",
            "krRrn, 9001010234567, false", "krRrn, 9013011234567, false",
            // Singapore
            "sgNric, S1234567D, true", "sgNric, T1234567J, true", "sgNric, F1234567N, true", "sgNric, G1234567X, true", "sgNric, S1234567E, false",
            "sgNric, X1234567D, false", "sgNric, S12A4567D, false", "sgNric, S1234567, false",
            // Australia
            "auTfn, 123456782, true", "auTfn, 12345678, false", "auTfn, 12345678A, false", "auTfn, 123456783, false",
            "auMedicare, 2123456701, true", "auMedicare, 21234567011, true", "auMedicare, 212345670, false", "auMedicare, 212345670111, false",
            "auMedicare, 212345670A, false", "auMedicare, 1123456701, false", "auMedicare, 7123456701, false", "auMedicare, 2123456711, false",
            // New Zealand, a number needing the secondary weights included
            "nzIrd, 49091850, true", "nzIrd, 35901981, true", "nzIrd, 136410132, true", "nzIrd, 136410133, false", "nzIrd, 4909185, false",
            "nzIrd, 4909185000, false", "nzIrd, 4909185A, false", "nzIrd, 10000000, false", "nzIrd, 150000000, false",
            // South Africa
            "zaId, 8001015009087, true", "zaId, 800101500908, false", "zaId, 800101500908A, false", "zaId, 8001015009387, false",
            "zaId, 8013015009087, false", "zaId, 8001015009088, false",
            // Passport MRZ: each of the four check digits, and a character outside the
            // alphabet
            "passportMrz, L898902C36UTO7408122F1204159ZE184226B<<<<<10, true", "passportMrz, L898902C36UTO7408122F1204159ZE184226B<<<<<1, false",
            "passportMrz, L898902C37UTO7408122F1204159ZE184226B<<<<<10, false",
            "passportMrz, L898902C36UTO7408123F1204159ZE184226B<<<<<10, false",
            "passportMrz, L898902C36UTO7408122F1204158ZE184226B<<<<<10, false",
            "passportMrz, L898902C36UTO7408122F1204159ZE184226B<<<<<11, false",
            "passportMrz, l898902C36UTO7408122F1204159ZE184226B<<<<<10, false",
            // IPv6
            "ipv6, 1:2:3:4:5:6:7:8, true", "ipv6, fe80::1, true", "ipv6, 1:2:3:4:5:6:7, false", "ipv6, 1::2::3, false",
            "ipv6, 12345::1, false", "ipv6, ::, false"})
    void validates(String validator, String value, boolean expected) {
        assertEquals(expected, VALIDATORS.get(validator).test(value), validator + "(" + value + ")");
    }
}
