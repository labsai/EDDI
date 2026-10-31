/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmbeddedJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode read(String span) {
        try {
            return MAPPER.readTree(span);
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void soleObject_skipsAnUnparseableBraceAndFindsTheRealObject() {
        assertEquals("A", EmbeddedJson.soleObject("Weighing {cost, risk}: {\"vote\": \"A\"} (see {notes})",
                EmbeddedJsonTest::read).path("vote").asText());
    }

    @Test
    void soleObject_bracesInsideStringsDoNotCount() {
        assertEquals("a } b", EmbeddedJson.soleObject("x {\"reason\": \"a } b\"} y", EmbeddedJsonTest::read)
                .path("reason").asText());
    }

    @Test
    void soleObject_nestedObjectIsReturnedWhole() {
        assertEquals(1, EmbeddedJson.soleObject("{\"a\": {\"b\": 1}} trailing }", EmbeddedJsonTest::read)
                .path("a").path("b").asInt());
    }

    @Test
    void soleArray_bracketedAsideDoesNotHideTheArray() {
        assertEquals(1, EmbeddedJson.soleArray("Checked [details]: [{\"passed\": false}]", EmbeddedJsonTest::read).size());
    }

    @Test
    void twoAcceptableObjects_areAmbiguous() {
        assertNull(EmbeddedJson.soleObject("[{\"score\": 0.9}, {\"score\": 0.1}]", EmbeddedJsonTest::read));
    }

    @Test
    void nothingParsable_returnsNull() {
        assertNull(EmbeddedJson.soleObject("no json { here", EmbeddedJsonTest::read));
        assertNull(EmbeddedJson.soleObject(null, EmbeddedJsonTest::read));
    }

}
