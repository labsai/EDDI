/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The national and universal identifiers beyond the original kinds. Valid
 * values are published examples (issuing authorities, Wikipedia), not values
 * generated with the code under test.
 */
class NationalRecognizersTest {

    private static List<PiiSpan> find(String entity, String text) {
        return BuiltinRecognizers.find(entity, text, BuiltinRecognizers.NO_DEADLINE);
    }

    @ParameterizedTest
    @DisplayName("each identifier is found, exactly as written")
    @CsvSource(delimiter = '|', value = {"US_ITIN|ITIN 912-70-1234 filed|912-70-1234", "CA_SIN|SIN: 046 454 286|046 454 286",
            "BR_CPF|CPF 529.982.247-25 ok|529.982.247-25", "BR_CPF|cpf 52998224725|52998224725",
            "MX_CURP|CURP HEGG560427MVZRRL04.|HEGG560427MVZRRL04", "GB_NINO|NI number AB 12 34 56 C|AB 12 34 56 C",
            "GB_NHS|NHS number 943 476 5919|943 476 5919", "IE_PPS|PPS 1234567T|1234567T", "FR_NIR|NIR 2 55 08 14 168 025 38|2 55 08 14 168 025 38",
            "IT_FISCAL_CODE|CF: RSSMRA85T10A562S|RSSMRA85T10A562S", "IT_FISCAL_CODE|cf rssmra85t10a562s|rssmra85t10a562s",
            "ES_DNI|DNI 12345678-Z|12345678-Z", "ES_DNI|NIE X1234567L|X1234567L", "PT_NIF|NIF 123 456 789|123 456 789",
            "NL_BSN|BSN 1112.22.333|1112.22.333", "BE_NATIONAL_NUMBER|nr 93.05.18-223.61|93.05.18-223.61",
            "AT_SOCIAL_SECURITY|SVNR 1237 010180|1237 010180", "CH_AHV|AHV 756.9217.0769.85|756.9217.0769.85",
            "PL_PESEL|PESEL 44051401359|44051401359", "SE_PERSONNUMMER|pnr 811228-9874|811228-9874",
            "SE_PERSONNUMMER|pnr 19811228-9874|19811228-9874", "DK_CPR|CPR-nr. 070761-4285|070761-4285", "FI_HETU|HETU 131052-308T|131052-308T",
            "IN_AADHAAR|Aadhaar 2341 2341 2346|2341 2341 2346", "IN_PAN|PAN ABCPE1234F|ABCPE1234F",
            "CN_RESIDENT_ID|身份证 11010519491231002X|11010519491231002X", "JP_MY_NUMBER|マイナンバー 1234 5678 9018|1234 5678 9018",
            "KR_RRN|주민등록번호 900101-1234567|900101-1234567", "SG_NRIC|NRIC S1234567D|S1234567D", "AU_TFN|TFN 123 456 782|123 456 782",
            "AU_MEDICARE|Medicare 2123 45670 1|2123 45670 1", "NZ_IRD|IRD 49-091-850|49-091-850", "ZA_ID|ID 8001015009087|8001015009087",
            "PASSPORT_MRZ|L898902C36UTO7408122F1204159ZE184226B<<<<<10|L898902C36UTO7408122F1204159ZE184226B<<<<<10",
            "IP_ADDRESS|from 192.168.0.12.|192.168.0.12", "IP_ADDRESS|host 2001:db8::8a2e:370:7334 up|2001:db8::8a2e:370:7334",
            "DATE_OF_BIRTH|née le 12/03/1980|12/03/1980", "DATE_OF_BIRTH|生年月日 1980-03-12|1980-03-12"})
    void finds(String entity, String text, String value) {
        List<PiiSpan> spans = find(entity, text);
        assertEquals(1, spans.size(), text + " → " + spans);
        assertEquals(value, text.substring(spans.getFirst().start(), spans.getFirst().end()));
        assertEquals(entity, spans.getFirst().entityType());
    }

    @ParameterizedTest
    @DisplayName("a wrong check digit, a bad date or a missing keyword is not found")
    @CsvSource(delimiter = '|', value = {"GB_NHS|943 476 5919", "GB_NHS|NHS 943 476 5918", "FR_NIR|NIR 2 55 08 14 168 025 39",
            "IT_FISCAL_CODE|RSSMRA85T10A562T", "ES_DNI|12345678A", "NL_BSN|BSN 111222334", "NL_BSN|111222333", "BE_NATIONAL_NUMBER|93.05.18-223.62",
            "AT_SOCIAL_SECURITY|SVNR 1238 010180", "AT_SOCIAL_SECURITY|1237 010180", "CH_AHV|756.9217.0769.86", "PL_PESEL|PESEL 44051401358",
            "PL_PESEL|44051401359", "SE_PERSONNUMMER|811228-9875", "DK_CPR|CPR 320761-4285", "FI_HETU|131052-308U",
            "IN_AADHAAR|Aadhaar 2341 2341 2345", "IN_AADHAAR|2341 2341 2346", "CN_RESIDENT_ID|110105194912310021",
            "JP_MY_NUMBER|マイナンバー 1234 5678 9019", "SG_NRIC|S1234567E", "AU_TFN|TFN 123 456 783", "AU_MEDICARE|Medicare 2123 45671 1",
            "NZ_IRD|IRD 49-091-851", "CA_SIN|SIN 046 454 287", "CA_SIN|046 454 286", "ZA_ID|8001015009088", "BR_CPF|111.111.111-11",
            "BR_CPF|529.982.247-26", "MX_CURP|HEGG560427MVZRRL05", "GB_NINO|BG 12 34 56 C", "IE_PPS|1234567A", "KR_RRN|900132-1234567",
            "US_ITIN|912-89-1234", "PASSPORT_MRZ|L898902C36UTO7408122F1204159ZE184226B<<<<<11", "IP_ADDRESS|version 1.2.3.4.5",
            "IP_ADDRESS|at 12:30:45", "IP_ADDRESS|256.1.1.1", "PT_NIF|NIF 123 456 788"})
    void ignores(String entity, String text) {
        assertEquals(List.of(), find(entity, text), text);
    }

    @Test
    @DisplayName("every entity type is either universal or belongs to exactly one country")
    void countriesCoverEveryNationalType() {
        Set<String> universal = Set.of(PiiEntities.EMAIL, PiiEntities.PHONE, PiiEntities.CREDIT_CARD, PiiEntities.IBAN, PiiEntities.IP_ADDRESS,
                PiiEntities.PASSPORT_MRZ, PiiEntities.DATE_OF_BIRTH);
        Set<String> seen = new HashSet<>();
        for (String code : PiiCountries.codes()) {
            for (String entity : PiiCountries.entitiesOf(code)) {
                assertTrue(BuiltinRecognizers.supports(entity), entity);
                assertTrue(entity.startsWith(code + "_"), entity + " is filed under " + code);
                assertTrue(seen.add(entity), entity + " is in two countries");
            }
        }
        for (String entity : BuiltinRecognizers.entities()) {
            assertTrue(universal.contains(entity) ^ seen.contains(entity), entity);
        }
        assertTrue(BuiltinRecognizers.entities().containsAll(PiiCountries.STANDARD));
    }

    @Test
    void countryCodesAreCaseInsensitive() {
        assertEquals(PiiCountries.entitiesOf("DE"), PiiCountries.entitiesOf(" de "));
        assertEquals(List.of(), PiiCountries.entitiesOf("XX"));
    }
}
