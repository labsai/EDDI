/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions;

import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.providers.DamerauLevenshteinCorrectionProvider;
import ai.labs.eddi.modules.nlp.extensions.corrections.providers.MergedTermsCorrectionProvider;
import ai.labs.eddi.modules.nlp.extensions.corrections.providers.PhoneticCorrectionProvider;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;
import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.ContractedWordNormalizerProvider;
import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.ConvertSpecialCharacterNormalizerProvider;
import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.RemoveUndefinedCharacterNormalizerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for all NLP extension providers (normalizers and corrections).
 */
@DisplayName("NLP Extension Providers Tests")
class NlpExtensionProvidersTest {

    @Nested
    @DisplayName("Normalizer Providers")
    class NormalizerProviders {

        @Test
        @DisplayName("ConvertSpecialCharacterNormalizerProvider should provide normalizer")
        void convertSpecialChar() {
            var provider = new ConvertSpecialCharacterNormalizerProvider();
            assertEquals("ai.labs.parser.normalizers.specialCharacter", provider.getId());
            assertEquals("Convert Special Character Normalizer", provider.getDisplayName());
            INormalizer normalizer = provider.provide(Map.of());
            assertNotNull(normalizer);
        }

        @Test
        @DisplayName("ContractedWordNormalizerProvider should provide normalizer")
        void contractedWord() {
            var provider = new ContractedWordNormalizerProvider();
            assertEquals("ai.labs.parser.normalizers.contractedWords", provider.getId());
            assertEquals("Contracted Word Normalizer", provider.getDisplayName());
            INormalizer normalizer = provider.provide(Map.of());
            assertNotNull(normalizer);
        }

        @Test
        @DisplayName("RemoveUndefinedCharacterNormalizerProvider should provide normalizer")
        void removeUndefined() {
            var provider = new RemoveUndefinedCharacterNormalizerProvider();
            assertEquals("ai.labs.parser.normalizers.allowedCharacter", provider.getId());
            assertEquals("Remove Undefined Character Normalizer", provider.getDisplayName());
            INormalizer normalizer = provider.provide(Map.of());
            assertNotNull(normalizer);
        }
    }

    @Nested
    @DisplayName("Correction Providers")
    class CorrectionProviders {

        @Test
        @DisplayName("DamerauLevenshteinCorrectionProvider should provide correction with default distance")
        void levenshteinDefault() {
            var provider = new DamerauLevenshteinCorrectionProvider();
            assertEquals("ai.labs.parser.corrections.levenshtein", provider.getId());
            assertEquals("Damerau Levenshtein Correction", provider.getDisplayName());
            ICorrection correction = provider.provide(Map.of());
            assertNotNull(correction);
        }

        @Test
        @DisplayName("DamerauLevenshteinCorrectionProvider should provide correction with custom distance")
        void levenshteinCustomDistance() {
            var provider = new DamerauLevenshteinCorrectionProvider();
            ICorrection correction = provider.provide(Map.of("distance", "3"));
            assertNotNull(correction);
        }

        @Test
        @DisplayName("DamerauLevenshteinCorrectionProvider should return config descriptors")
        void levenshteinConfigs() {
            var provider = new DamerauLevenshteinCorrectionProvider();
            var configs = provider.getConfigs();
            assertNotNull(configs);
            assertTrue(configs.containsKey("distance"));
        }

        @Test
        @DisplayName("PhoneticCorrectionProvider should provide correction")
        void phonetic() {
            var provider = new PhoneticCorrectionProvider();
            assertEquals("ai.labs.parser.corrections.phonetic", provider.getId());
            assertEquals("Phonetic Matching Correction", provider.getDisplayName());
            ICorrection correction = provider.provide(Map.of());
            assertNotNull(correction);
        }

        @Test
        @DisplayName("MergedTermsCorrectionProvider should provide correction")
        void mergedTerms() {
            var provider = new MergedTermsCorrectionProvider();
            assertEquals("ai.labs.parser.corrections.mergedTerms", provider.getId());
            assertEquals("Merged Terms Correction", provider.getDisplayName());
            ICorrection correction = provider.provide(Map.of());
            assertNotNull(correction);
        }
    }
}
