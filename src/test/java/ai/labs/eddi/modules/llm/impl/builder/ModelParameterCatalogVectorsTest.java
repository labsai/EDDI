/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.modules.llm.impl.builder.ModelParameterCatalog.Finding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Manager evaluates the same model rules in TypeScript to show, while an
 * agent designer types, which settings the model will reject. Two
 * implementations of one rule language drift unless something ties them: this
 * runs the vectors the Manager's own test consumes
 * ({@code src/test/resources/llm/parameter-rule-vectors.json}) through the
 * backend {@link ModelParameterCatalog}.
 */
class ModelParameterCatalogVectorsTest {

    private static final ModelParameterCatalog CATALOG = ModelParameterCatalog.defaultCatalog();

    record Vector(String name, String type, Map<String, String> params, String key, String expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> vectors() throws IOException {
        try (InputStream in = ModelParameterCatalogVectorsTest.class.getResourceAsStream("/llm/parameter-rule-vectors.json")) {
            List<Vector> all = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
            assertFalse(all.isEmpty(), "no vectors found");
            return all.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("backend and Manager agree on every vector")
    void agree(Vector vector) {
        String actual = CATALOG.adapt(vector.type(), vector.params(), true).findings().stream().filter(f -> f.key().equals(vector.key()))
                .map(Finding::effect).findFirst().map(e -> e.name().toLowerCase(Locale.ROOT)).orElse("ok");
        assertEquals(vector.expected(), actual);
    }
}
