/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tests of the artifact-schema cost bound: which schemas it passes, and
 * the reason it gives for each shape it refuses.
 */
class ArtifactSchemaComplexityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String problem(String json) throws Exception {
        return ArtifactSchemaComplexity.problem(MAPPER.readTree(json));
    }

    private static void assertRefused(String json, String fragment) throws Exception {
        String problem = problem(json);
        assertNotNull(problem, "expected a refusal for " + json);
        assertTrue(problem.contains(fragment), problem);
    }

    @Test
    void nullAndNonObjectSchemasAreNotJudged() throws Exception {
        assertNull(ArtifactSchemaComplexity.problem(null));
        assertNull(problem("true"));
        assertNull(problem("[1,2]"));
    }

    @Test
    void anOrdinarySchemaPasses() throws Exception {
        assertNull(problem("""
                {"type":"object","properties":{"name":{"type":"string"},"tags":{"type":"array","items":{"type":"string"}}},
                 "patternProperties":{"^x-":{"type":"string"}},"prefixItems":[{"type":"string"}],
                 "allOf":[{"required":["name"]}],"anyOf":[{}],"oneOf":[{}],"not":{"type":"null"},
                 "if":{"required":["a"]},"then":{},"else":{},"dependentSchemas":{"a":{"required":["b"]}},
                 "additionalProperties":false,"contains":{"type":"string"},"additionalItems":[{"type":"string"}]}"""));
    }

    @Test
    void aTreeAndABinaryTreePass() throws Exception {
        assertNull(problem("{\"type\":\"object\",\"properties\":{\"children\":{\"type\":\"array\",\"items\":{\"$ref\":\"#\"}}}}"));
        assertNull(problem("{\"type\":\"object\",\"properties\":{\"left\":{\"$ref\":\"#\"},\"right\":{\"$ref\":\"#\"}}}"));
    }

    @Test
    void aReferenceBackWithoutDescendingIsRefused() throws Exception {
        assertRefused("{\"allOf\":[{\"$ref\":\"#\"}]}", "recurses endlessly");
    }

    @Test
    void twoRoutesThroughUnnamedChildrenAreRefused() throws Exception {
        assertRefused("{\"type\":\"array\",\"items\":{\"$ref\":\"#\"},\"contains\":{\"$ref\":\"#\"}}", "two routes");
    }

    @Test
    void twoRoutesThroughTheSamePropertyAreRefused() throws Exception {
        assertRefused("{\"properties\":{\"a\":{\"anyOf\":[{\"$ref\":\"#\"},{\"properties\":{\"b\":{\"$ref\":\"#\"}}}]}}}", "two routes");
    }

    @Test
    void exponentialDefinitionsAreRefusedByTheBudget() throws Exception {
        StringBuilder defs = new StringBuilder("\"d0\":{\"type\":\"string\"}");
        for (int i = 1; i <= 14; i++) {
            defs.append(",\"d").append(i).append("\":{\"allOf\":[{\"$ref\":\"#/$defs/d").append(i - 1)
                    .append("\"},{\"$ref\":\"#/$defs/d").append(i - 1).append("\"}]}");
        }
        assertRefused("{\"$defs\":{" + defs + "},\"$ref\":\"#/$defs/d14\"}", "expands to more than");
    }

    @Test
    void aReferenceToAMissingPointerIsRefused() throws Exception {
        assertRefused("{\"$ref\":\"#/$defs/missing\"}", "does not point at anything");
    }

    @Test
    void anEscapedPointerIsDecoded() throws Exception {
        assertNull(problem("{\"$defs\":{\"a b\":{\"type\":\"string\"}},\"$ref\":\"#/$defs/a%20b\"}"));
        assertRefused("{\"$ref\":\"#/$defs/%zz\"}", "not a valid JSON pointer");
    }

    @Test
    void anchorsResolveAndAnUnknownAnchorIsRefused() throws Exception {
        assertNull(problem("{\"$defs\":{\"n\":{\"$anchor\":\"node\",\"type\":\"string\"}},\"$ref\":\"#node\"}"));
        assertNull(problem("{\"$defs\":{\"n\":{\"$dynamicAnchor\":\"node\",\"type\":\"string\"}},\"$dynamicRef\":\"#node\"}"));
        assertRefused("{\"$ref\":\"#nowhere\"}", "names an anchor the schema does not define");
    }

    @Test
    void aRecursiveRefPointsAtTheRoot() throws Exception {
        assertNull(problem("{\"properties\":{\"next\":{\"$recursiveRef\":\"#\"}}}"));
        assertRefused("{\"allOf\":[{\"$recursiveRef\":\"#\"}]}", "recurses endlessly");
    }

    @Test
    void aNonTextualReferenceIsIgnored() throws Exception {
        assertNull(problem("{\"$ref\":5,\"properties\":{\"a\":true}}"));
    }

    @Test
    void aNestedIdIsRefused() throws Exception {
        assertRefused("{\"$defs\":{\"x\":{\"$id\":\"https://example.com/x\"}}}", "nested $id");
    }

    @Test
    void anotherDocumentIsLeftToTheLoader() throws Exception {
        assertNull(problem("{\"$ref\":\"https://json-schema.org/draft/2020-12/schema\"}"));
        assertNull(problem("{\"$id\":\"https://example.com/a.json\",\"$ref\":\"other.json\"}"));
    }

    @Test
    void aRelativeReferenceWithoutAnAbsoluteIdIsRefused() throws Exception {
        assertRefused("{\"$ref\":\"other.json\"}", "is relative");
        assertRefused("{\"$id\":\"relative/id\",\"$ref\":\"other.json\"}", "is relative");
    }

    @Test
    void anInvalidUriReferenceIsRefused() throws Exception {
        assertRefused("{\"$ref\":\"a b:\\\\c\"}", "not a valid URI reference");
    }

    @Test
    void theRootIdResolvesToThisDocument() throws Exception {
        assertNull(problem("{\"$id\":\"https://example.com/a.json\",\"$defs\":{\"s\":{\"type\":\"string\"}},"
                + "\"$ref\":\"https://example.com/a.json#/$defs/s\"}"));
        assertNull(problem("{\"$id\":\"https://example.com/a.json\",\"properties\":{\"x\":{\"$ref\":\"a.json\"}}}"));
        assertRefused("{\"$id\":\"https://example.com/a.json\",\"allOf\":[{\"$ref\":\"https://example.com/a.json\"}]}", "recurses endlessly");
    }

    @Test
    void anEmptyReferenceIsTheRoot() throws Exception {
        assertRefused("{\"allOf\":[{\"$ref\":\"\"}]}", "recurses endlessly");
    }

    @Test
    void anEmptyFragmentOfTheRootIdIsTheRoot() throws Exception {
        assertRefused("{\"$id\":\"https://example.com/a.json\",\"$ref\":\"https://example.com/a.json#\"}", "recurses endlessly");
    }
}
