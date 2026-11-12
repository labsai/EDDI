/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiChecksumsTest {

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "4111 1111 1111 1111", "5555555555554444", "378282246310005", "4012888888881881"})
    void luhn_valid(String card) {
        assertTrue(PiiChecksums.luhn(card), card);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111112", "1234567890123", "0000000000", ""})
    void luhn_invalid(String card) {
        assertFalse(PiiChecksums.luhn(card), card);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DE89370400440532013000", "GB82WEST12345698765432", "AT611904300234573201", "CH9300762011623852957",
            "FR1420041010050500013M02606", "NL91ABNA0417164300", "NO9386011117947"})
    void iban_valid(String iban) {
        assertTrue(PiiChecksums.iban(iban), iban);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DE89370400440532013001", "DE8937040044053201300", "XX89370400440532013000", "DE89 3704 0044 0532 0130 00",
            "de89370400440532013000", "DE"})
    void iban_invalid(String iban) {
        assertFalse(PiiChecksums.iban(iban), iban);
    }

    @Test
    void deTaxId() {
        assertTrue(PiiChecksums.deTaxId("86095742719"));
        assertFalse(PiiChecksums.deTaxId("86095742718"), "wrong check digit");
        assertFalse(PiiChecksums.deTaxId("06095742719"), "leading zero");
        assertFalse(PiiChecksums.deTaxId("12345678901"), "no repeated digit");
        assertFalse(PiiChecksums.deTaxId("11112345678"), "a digit four times");
        assertFalse(PiiChecksums.deTaxId("8609574271"), "too short");
    }

    @Test
    void icaoCheckDigit() {
        assertEquals(1, PiiChecksums.icaoCheckDigit("L01X00T47"));
        assertEquals(-1, PiiChecksums.icaoCheckDigit("l01x00t47"));
    }

    @Test
    void deSocialSecurity() {
        assertTrue(PiiChecksums.deSocialSecurity("15070649C103"));
        assertTrue(PiiChecksums.deSocialSecurity("15070649c103"), "the letter may be lower-case");
        assertFalse(PiiChecksums.deSocialSecurity("15070649C104"), "wrong check digit");
        assertFalse(PiiChecksums.deSocialSecurity("15320649C103"), "day 32");
        assertFalse(PiiChecksums.deSocialSecurity("1507064C9103"), "letter out of place");
    }
}
