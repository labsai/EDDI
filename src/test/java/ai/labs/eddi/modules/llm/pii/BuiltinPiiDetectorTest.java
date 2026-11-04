/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinPiiDetectorTest {

    private final BuiltinPiiDetector detector = new BuiltinPiiDetector();

    private List<PiiSpan> detect(String text, String... entities) throws DetectorUnavailableException {
        return detector.detect(text, DetectorContext.of(Set.of(entities), 1_000));
    }

    /** The single value of {@code entity} found in {@code text}. */
    private String only(String text, String entity) throws DetectorUnavailableException {
        List<PiiSpan> spans = detect(text, entity);
        assertEquals(1, spans.size(), text + " → " + spans);
        PiiSpan span = spans.getFirst();
        assertEquals(entity, span.entityType());
        assertEquals(BuiltinPiiDetector.TYPE, span.detectorId());
        return text.substring(span.start(), span.end());
    }

    @ParameterizedTest
    @DisplayName("each entity type finds exactly its value")
    @CsvSource(delimiter = '|', value = {"IBAN|Bitte an DE89 3704 0044 0532 0130 00 überweisen|DE89 3704 0044 0532 0130 00",
            "IBAN|IBAN: GB82WEST12345698765432.|GB82WEST12345698765432",
            "IBAN|DE89 3704 0044 0532 0130 00 OK danke|DE89 3704 0044 0532 0130 00",
            "DE_TAX_ID|Steuer-ID 86 095 742 719 liegt bei|86 095 742 719", "DE_TAX_ID|IdNr. 86095742719|86095742719",
            "DE_SOCIAL_SECURITY|Rentenversicherungsnummer 15070649C103|15070649C103",
            "DE_SOCIAL_SECURITY|RVNR 15 070649 C 103 vorhanden|15 070649 C 103", "DE_ID_CARD|Personalausweis Nr. L01X00T47|L01X00T47",
            "DE_ID_CARD|passport number c01x00t47 attached|c01x00t47", "DE_ID_CARD|Ausweis L01X00T471|L01X00T471",
            "DATE_OF_BIRTH|Max, geb. 12.03.1980, wohnt in Graz|12.03.1980", "DATE_OF_BIRTH|Date of birth: 1980-03-12|1980-03-12",
            "DATE_OF_BIRTH|geboren am 12. März 1980|12. März 1980", "DATE_OF_BIRTH|DOB 03/12/80|03/12/80",
            "EMAIL|mail jane.doe@example.org now|jane.doe@example.org", "US_SSN|SSN 123-45-6789|123-45-6789",
            "CREDIT_CARD|card 4111 1111 1111 1111 123|4111 1111 1111 1111", "PHONE|call +43 660 1234567|+43 660 1234567"})
    void finds(String entity, String text, String value) throws DetectorUnavailableException {
        assertEquals(value, only(text, entity));
    }

    @ParameterizedTest
    @DisplayName("look-alikes without a check digit or keyword are not found")
    @CsvSource(delimiter = '|', value = {"IBAN|DE89 3704 0044 0532 0130 01", "IBAN|ZZ89370400440532013000", "DE_TAX_ID|Bestellung 86095742718",
            "DE_TAX_ID|+49 860 957 427 19", "DE_SOCIAL_SECURITY|15070649C104", "DE_ID_CARD|Artikel L01X00T47",
            "DE_ID_CARD|Ausweis L01X00T472", "DE_ID_CARD|Ausweis PRTVWXYZK", "DE_ID_CARD|password L01X00T47", "DATE_OF_BIRTH|Termin 12.03.1980",
            "DATE_OF_BIRTH|geb. 32.13.1980", "DATE_OF_BIRTH|born 12.03.1850"})
    void ignores(String entity, String text) throws DetectorUnavailableException {
        assertEquals(List.of(), detect(text, entity), text);
    }

    @Test
    @DisplayName("the keyword must stand within the window before the value")
    void triggerWindow() throws DetectorUnavailableException {
        String far = "geb." + " ".repeat(BuiltinRecognizers.TRIGGER_WINDOW) + "12.03.1980";
        assertEquals(List.of(), detect(far, PiiEntities.DATE_OF_BIRTH));
    }

    @Test
    @DisplayName("an empty entity set asks for the standard set")
    void allEntities() throws DetectorUnavailableException {
        List<PiiSpan> spans = detector.detect("a@b.io DE89370400440532013000", DetectorContext.of(Set.of(), 1_000));
        assertEquals(Set.of(PiiEntities.EMAIL, PiiEntities.IBAN), Set.copyOf(spans.stream().map(PiiSpan::entityType).toList()));
    }

    @Test
    @DisplayName("the entities parameter narrows the context's set")
    void entitiesParameter() throws DetectorUnavailableException {
        var context = new DetectorContext(Set.of(), Map.of("entities", "email"), System.nanoTime() + 1_000_000_000L);
        List<PiiSpan> spans = detector.detect("a@b.io DE89370400440532013000", context);
        assertEquals(1, spans.size());
        assertEquals(PiiEntities.EMAIL, spans.getFirst().entityType());
    }

    @Test
    @DisplayName("past the deadline the detector is unavailable, never 'nothing found'")
    void deadline() {
        var context = new DetectorContext(Set.of(), Map.of(), System.nanoTime() - 1);
        var e = assertThrows(DetectorUnavailableException.class, () -> detector.detect("a@b.io", context));
        assertEquals(BuiltinPiiDetector.REASON_TIMEOUT, e.reason());
        assertEquals(BuiltinPiiDetector.TYPE, e.detectorType());
    }

    @Test
    @DisplayName("long digit and letter runs are scanned in linear time")
    void linearScan() {
        String text = "1".repeat(100_000) + " " + "A1".repeat(50_000) + " geb. " + "1.".repeat(50_000) + " pesel " + "a:".repeat(50_000)
                + " " + "A<".repeat(50_000) + " " + "1-".repeat(50_000) + " " + "S1".repeat(50_000);
        var every = new DetectorContext(Set.of(), Map.of("entities", String.join(",", BuiltinRecognizers.entities())),
                System.nanoTime() + 20_000_000_000L);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> detector.detect(text, every));
    }

    @Test
    @DisplayName("the countries parameter adds that country's identifiers to the standard set")
    void countriesParameter() throws DetectorUnavailableException {
        var context = new DetectorContext(Set.of(), Map.of("countries", "it"), System.nanoTime() + 1_000_000_000L);
        List<PiiSpan> spans = detector.detect("a@b.io CF RSSMRA85T10A562S Steuer-ID 86095742719", context);
        assertEquals(List.of(PiiEntities.EMAIL, PiiEntities.IT_FISCAL_CODE), spans.stream().map(PiiSpan::entityType).toList());
    }

    @Test
    void validate() {
        assertNull(detector.validate(Map.of("entities", "email, iban")));
        assertNull(detector.validate(Map.of()));
        String problem = detector.validate(Map.of("entities", "email,PERSON"));
        assertNotNull(problem);
        assertTrue(problem.contains("PERSON"), problem);
        String country = detector.validate(Map.of("countries", "DE,XX"));
        assertNotNull(country);
        assertTrue(country.contains("XX"), country);
    }
}
