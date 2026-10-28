/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandViolation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandValidatorTest {

    private static List<BrandViolation> name(String value) {
        return BrandValidator.validate(value, null, null, null, UnaryOperator.identity());
    }

    @Nested
    @DisplayName("names")
    class Names {

        @ParameterizedTest
        @ValueSource(strings = {"Acme Assist", "Zebra Labs", "Ünïcødé 名前", "R&D", "O'Brien \"Bots\""})
        void ordinaryNamesPass(String value) {
            assertEquals(List.of(), name(value));
        }

        /** The §5 hostile inputs that a name could carry into HTML or JS. */
        @ParameterizedTest
        @ValueSource(strings = {"</script>", "<img src=x onerror=alert(1)>", "a>b", "a<b"})
        void angleBracketsAreRefused(String value) {
            List<BrandViolation> violations = name(value);
            assertEquals(1, violations.size(), value);
            assertEquals("productName", violations.getFirst().field());
            assertTrue(violations.getFirst().message().contains("'<' or '>'"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"line\nbreak", "tab\there", "nul\u0000", "del\u007f", "sep ", "rtl‮evil", "iso⁦x"})
        void controlAndBidiCharactersAreRefused(String value) {
            List<BrandViolation> violations = name(value);
            assertEquals(1, violations.size(), value);
            assertTrue(violations.getFirst().message().contains("control characters"));
        }

        @Test
        void lengthCapsCountCodePoints() {
            assertEquals(List.of(), name("x".repeat(40)));
            assertEquals(1, name("x".repeat(41)).size());
            // 40 emoji are 80 chars but 40 code points: allowed.
            assertEquals(List.of(), name("😀".repeat(40)));
            assertEquals(1, BrandValidator.validate(null, "x".repeat(13), null, null, UnaryOperator.identity()).size());
            assertEquals(1, BrandValidator.validate(null, null, "x".repeat(121), null, UnaryOperator.identity()).size());
            assertEquals(List.of(), BrandValidator.validate(null, "x".repeat(12), "x".repeat(120), null, UnaryOperator.identity()));
        }

        @Test
        void blankIsRefusedAndNullIsUnset() {
            assertEquals(1, name("   ").size());
            assertEquals(List.of(), name(null));
        }

        @Test
        void fieldNamesAreMappedForTheSeed() {
            List<BrandViolation> violations = BrandValidator.validate("<", null, null, new BrandColors(null, null, "red", null, null),
                    BrandingService::propertyName);
            assertEquals(List.of("eddi.branding.product-name", "eddi.branding.color.dark-primary"),
                    violations.stream().map(BrandViolation::field).toList());
        }
    }

    @Nested
    @DisplayName("colours")
    class Colors {

        @ParameterizedTest
        @ValueSource(strings = {"#fff", "#0F766E", "rgb(15 118 110)", "rgb(15, 118, 110)", "rgba(15,118,110,0.5)", "rgb(10% 20% 30% / 50%)",
                "hsl(173 80% 26%)", "hsla(173, 80%, 26%, .5)", "oklch(0.55 0.1 180)", "oklch(55% 0.1 180deg / 0.9)", "RGB(1 2 3)"})
        void colourGrammarAccepts(String value) {
            assertTrue(BrandValidator.isSafeColor(value), value);
        }

        /**
         * The §5 hostile inputs for the CSS context, plus everything outside the
         * grammar that a looser check might let through.
         */
        @ParameterizedTest
        @ValueSource(strings = {"red", "red;}body{display:none", "#fff;}body{display:none}", "url(javascript:alert(1))",
                "expression(alert(1))", "rgb(1 2 3);x", "rgb(var(--x) 2 3)", "rgb(calc(1) 2 3)", "#ffff", "#12345", "#ggg",
                "</style><script>", "rgb(1 2 3)\n}", "\"#fff\"", "rgb(1 2)", "rgb(1 2 3 4 5)", "hwb(1 2 3)", "", " #fff",
                "rgb(1 2 3)/**/", "\\23 fff"})
        void colourGrammarRefuses(String value) {
            assertFalse(BrandValidator.isSafeColor(value), value);
        }

        @Test
        void overlongColourIsRefusedBeforeTheRegex() {
            assertFalse(BrandValidator.isSafeColor("rgb(" + "1".repeat(100) + " 2 3)"));
            assertFalse(BrandValidator.isSafeColor("rgb(1" + " ".repeat(70) + "2 3)"));
        }

        @Test
        void everyColourFieldIsChecked() {
            List<BrandViolation> violations = BrandValidator.validate(null, null, null, new BrandColors("x", "x", "x", "x", "x"),
                    UnaryOperator.identity());
            assertEquals(List.of("colors.primary", "colors.primaryForeground", "colors.darkPrimary", "colors.darkPrimaryForeground",
                    "colors.chatAccent"), violations.stream().map(BrandViolation::field).toList());
        }
    }

    @Nested
    @DisplayName("contrast")
    class Contrast {

        @Test
        void blackOnWhiteIs21() {
            assertEquals(21.0, BrandValidator.contrastRatio(new double[]{0, 0, 0}, new double[]{1, 1, 1}), 0.001);
        }

        @Test
        void lowContrastWarnsAndDoesNotRefuse() {
            List<String> warnings = BrandValidator.contrastWarnings(new BrandColors("#777777", "#888888", "#777777", "#888888", null));
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().contains("primaryForeground on primary"));
        }

        @Test
        void darkPairIsCheckedWhenItDiffers() {
            List<String> warnings = BrandValidator.contrastWarnings(new BrandColors("#0f766e", "#ffffff", "#2dd4bf", "#2dd4bf", null));
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().contains("darkPrimaryForeground on darkPrimary"));
        }

        @Test
        void goodContrastAndUnparseableColoursGiveNoWarning() {
            assertEquals(List.of(), BrandValidator.contrastWarnings(new BrandColors("#0f766e", "#ffffff", null, null, null)));
            assertEquals(List.of(), BrandValidator.contrastWarnings(new BrandColors("oklch(0.5 0.1 180)", "#777", null, null, null)));
            assertEquals(List.of(), BrandValidator.contrastWarnings(BrandColors.EMPTY));
            assertEquals(List.of(), BrandValidator.contrastWarnings(null));
        }

        @Test
        void rgbFunctionsAreParsed() {
            List<String> warnings = new ArrayList<>(
                    BrandValidator.contrastWarnings(new BrandColors("rgb(119 119 119)", "rgb(53.3% 53.3% 53.3%)", null, null, null)));
            assertEquals(1, warnings.size());
            assertTrue(BrandValidator.parseSrgb("rgb(1deg 2 3)").isEmpty());
            assertTrue(BrandValidator.parseSrgb("hsl(1 2% 3%)").isEmpty());
            assertEquals(1.0, BrandValidator.parseSrgb("rgb(300 0 0)").orElseThrow()[0], 0.0001);
        }
    }
}
