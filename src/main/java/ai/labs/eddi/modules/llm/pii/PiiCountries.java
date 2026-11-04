/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.pii.PiiEntities.*;

/**
 * Which entity types are on by default, and which belong to which country.
 * <p>
 * National identifiers are opt-in: each matches only its own country's format,
 * so turning on every country by default would buy false positives in texts
 * that never mention those countries. A configuration names countries by ISO
 * 3166 code ({@code countries: "DE,FR"}), individual types, or both.
 */
public final class PiiCountries {

    /**
     * What runs when nothing is selected: the four kinds {@code pii-redaction}
     * always had, plus the IBAN, which is international.
     */
    public static final List<String> STANDARD = List.of(EMAIL, US_SSN, CREDIT_CARD, PHONE, IBAN);

    private static final Map<String, List<String>> BY_COUNTRY = new LinkedHashMap<>();

    static {
        // Americas
        BY_COUNTRY.put("US", List.of(US_SSN, US_ITIN));
        BY_COUNTRY.put("CA", List.of(CA_SIN));
        BY_COUNTRY.put("BR", List.of(BR_CPF));
        BY_COUNTRY.put("MX", List.of(MX_CURP));
        // Europe
        BY_COUNTRY.put("GB", List.of(GB_NINO, GB_NHS));
        BY_COUNTRY.put("IE", List.of(IE_PPS));
        BY_COUNTRY.put("FR", List.of(FR_NIR));
        BY_COUNTRY.put("IT", List.of(IT_FISCAL_CODE));
        BY_COUNTRY.put("ES", List.of(ES_DNI));
        BY_COUNTRY.put("PT", List.of(PT_NIF));
        BY_COUNTRY.put("NL", List.of(NL_BSN));
        BY_COUNTRY.put("BE", List.of(BE_NATIONAL_NUMBER));
        BY_COUNTRY.put("DE", List.of(DE_TAX_ID, DE_SOCIAL_SECURITY, DE_ID_CARD));
        BY_COUNTRY.put("AT", List.of(AT_SOCIAL_SECURITY));
        BY_COUNTRY.put("CH", List.of(CH_AHV));
        BY_COUNTRY.put("PL", List.of(PL_PESEL));
        BY_COUNTRY.put("SE", List.of(SE_PERSONNUMMER));
        BY_COUNTRY.put("DK", List.of(DK_CPR));
        BY_COUNTRY.put("FI", List.of(FI_HETU));
        // Asia-Pacific
        BY_COUNTRY.put("IN", List.of(IN_AADHAAR, IN_PAN));
        BY_COUNTRY.put("CN", List.of(CN_RESIDENT_ID));
        BY_COUNTRY.put("JP", List.of(JP_MY_NUMBER));
        BY_COUNTRY.put("KR", List.of(KR_RRN));
        BY_COUNTRY.put("SG", List.of(SG_NRIC));
        BY_COUNTRY.put("AU", List.of(AU_TFN, AU_MEDICARE));
        BY_COUNTRY.put("NZ", List.of(NZ_IRD));
        // Africa
        BY_COUNTRY.put("ZA", List.of(ZA_ID));
    }

    private PiiCountries() {
    }

    /** Every country with identifiers of its own, by ISO 3166 code. */
    public static Set<String> codes() {
        return BY_COUNTRY.keySet();
    }

    /**
     * The entity types of {@code country} (ISO 3166 code, any case); empty for a
     * country without any.
     */
    public static List<String> entitiesOf(String country) {
        return BY_COUNTRY.getOrDefault(country.trim().toUpperCase(Locale.ROOT), List.of());
    }

    public static boolean isCountry(String code) {
        return BY_COUNTRY.containsKey(code.trim().toUpperCase(Locale.ROOT));
    }
}
