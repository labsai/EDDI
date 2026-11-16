/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections.providers;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType;
import ai.labs.eddi.modules.nlp.extensions.IParserExtensionProvider;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code lookupIfKnown} arrives from a parser workflow config, which is JSON:
 * an author may write {@code true} or {@code "true"}. The provider used to cast
 * the value to {@code String}, so the natural JSON boolean failed the workflow
 * with a {@code ClassCastException}. And the option was advertised in no
 * correction provider's {@code ExtensionDescriptor}, so no editor could offer
 * it.
 */
@DisplayName("correction providers — lookupIfKnown")
class LookupIfKnownConfigTest {

    static Stream<Arguments> values() {
        return Stream.of(Arguments.of(Boolean.TRUE, true), Arguments.of(Boolean.FALSE, false), Arguments.of("true", true),
                Arguments.of("TRUE", true), Arguments.of(" true ", true), Arguments.of("false", false), Arguments.of("yes", false),
                Arguments.of("", false));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("values")
    @DisplayName("JSON booleans and strings are both accepted")
    void booleanAndStringAreAccepted(Object value, boolean expected) {
        Map<String, Object> config = new HashMap<>();
        config.put(IParserExtensionProvider.KEY_LOOKUP_IF_KNOWN, value);

        assertEquals(expected, new DamerauLevenshteinCorrectionProvider().provide(config).lookupIfKnown());
        assertEquals(expected, new PhoneticCorrectionProvider().provide(config).lookupIfKnown());
    }

    @Test
    @DisplayName("absent means false")
    void absentIsFalse() {
        ICorrection correction = new DamerauLevenshteinCorrectionProvider().provide(Map.of());

        assertFalse(correction.lookupIfKnown());
        assertFalse(new PhoneticCorrectionProvider().provide(Map.of()).lookupIfKnown());
    }

    @Test
    @DisplayName("a non-string, non-boolean value is read by its string form, not cast")
    void otherTypesDoNotThrow() {
        Map<String, Object> config = Map.of(IParserExtensionProvider.KEY_LOOKUP_IF_KNOWN, 1);

        assertFalse(new DamerauLevenshteinCorrectionProvider().provide(config).lookupIfKnown());
    }

    @Test
    @DisplayName("both providers that read it advertise it in their descriptor")
    void advertised() {
        for (IParserExtensionProvider<?> provider : new IParserExtensionProvider<?>[]{new DamerauLevenshteinCorrectionProvider(),
                new PhoneticCorrectionProvider()}) {
            var configs = provider.getConfigs();
            var value = configs.get(IParserExtensionProvider.KEY_LOOKUP_IF_KNOWN);
            assertNotNull(value, provider.getId() + " reads lookupIfKnown but does not advertise it");
            assertEquals(FieldType.BOOLEAN, value.getFieldType());
            assertTrue(value.isIsOptional());
            assertEquals(Boolean.FALSE, value.getDefaultValue());
        }
    }
}
