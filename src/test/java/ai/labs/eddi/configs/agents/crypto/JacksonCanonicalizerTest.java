/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.crypto;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JacksonCanonicalizer Tests")
class JacksonCanonicalizerTest {

    @Nested
    @DisplayName("Key Sorting")
    class KeySortingTests {

        @Test
        @DisplayName("Should sort top-level keys alphabetically")
        void testSortTopLevel() throws JsonProcessingException {
            String result = JacksonCanonicalizer.canonicalize("{\"z\":1,\"a\":2,\"m\":3}");
            assertEquals("{\"a\":2,\"m\":3,\"z\":1}", result);
        }

        @Test
        @DisplayName("Should sort nested keys recursively")
        void testSortNested() throws JsonProcessingException {
            String result = JacksonCanonicalizer.canonicalize("{\"b\":{\"z\":1,\"a\":2},\"a\":3}");
            assertEquals("{\"a\":3,\"b\":{\"a\":2,\"z\":1}}", result);
        }

        @Test
        @DisplayName("Should preserve array order")
        void testPreserveArrayOrder() throws JsonProcessingException {
            String result = JacksonCanonicalizer.canonicalize("{\"arr\":[3,1,2]}");
            assertEquals("{\"arr\":[3,1,2]}", result);
        }

        @Test
        @DisplayName("Should sort objects inside arrays")
        void testSortObjectsInArrays() throws JsonProcessingException {
            String result = JacksonCanonicalizer.canonicalize("{\"arr\":[{\"z\":1,\"a\":2}]}");
            assertEquals("{\"arr\":[{\"a\":2,\"z\":1}]}", result);
        }
    }

    @Nested
    @DisplayName("Data Types")
    class DataTypeTests {

        @Test
        @DisplayName("Should handle strings")
        void testStrings() throws JsonProcessingException {
            String result = JacksonCanonicalizer.canonicalize("{\"key\":\"value\"}");
            assertEquals("{\"key\":\"value\"}", result);
        }

        @Test
        @DisplayName("Should handle booleans")
        void testBooleans() throws JsonProcessingException {
            String result = JacksonCanonicalizer.canonicalize("{\"b\":true,\"a\":false}");
            assertEquals("{\"a\":false,\"b\":true}", result);
        }

        @Test
        @DisplayName("Should handle null values")
        void testNulls() throws JsonProcessingException {
            String result = JacksonCanonicalizer.canonicalize("{\"b\":null,\"a\":1}");
            assertEquals("{\"a\":1,\"b\":null}", result);
        }

        @Test
        @DisplayName("Should handle empty objects")
        void testEmptyObject() throws JsonProcessingException {
            String result = JacksonCanonicalizer.canonicalize("{}");
            assertEquals("{}", result);
        }

        @Test
        @DisplayName("Should handle empty arrays")
        void testEmptyArray() throws JsonProcessingException {
            String result = JacksonCanonicalizer.canonicalize("{\"a\":[]}");
            assertEquals("{\"a\":[]}", result);
        }
    }

    @Nested
    @DisplayName("Determinism")
    class DeterminismTests {

        @Test
        @DisplayName("Should produce identical output for semantically equal JSON")
        void testDeterministic() throws JsonProcessingException {
            String json1 = "{\"a\":1,\"b\":2}";
            String json2 = "{\"b\":2,\"a\":1}";
            assertEquals(
                    JacksonCanonicalizer.canonicalize(json1),
                    JacksonCanonicalizer.canonicalize(json2));
        }
    }

    @Nested
    @DisplayName("Invalid Input")
    class ErrorTests {

        @Test
        @DisplayName("Should throw on invalid JSON")
        void testInvalidJson() {
            assertThrows(JsonProcessingException.class,
                    () -> JacksonCanonicalizer.canonicalize("not json"));
        }
    }
}
