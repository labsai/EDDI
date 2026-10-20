/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agenttests;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The suite's JSON shape: the documented example parses under the same strict
 * parser the REST boundary applies, absent fields fall back to the documented
 * defaults, and a typo is refused rather than silently ignored.
 */
@DisplayName("AgentTestSuite JSON model")
class AgentTestSuiteModelTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StrictConfigurationParser strict = new StrictConfigurationParser(mapper);

    static final String DOCUMENTED_EXAMPLE = """
            {
              "name": "Booking agent — core flows",
              "defaults": { "toolMode": "mock", "unmockedTools": "fail", "repetitions": 1, "passRate": 1.0 },
              "toolMocks": [
                { "tool": "getAvailability", "argsSubset": { "city": "Vienna" },
                  "response": { "httpCode": 200, "body": { "rooms": 3 } } },
                { "tool": "createBooking", "response": { "httpCode": 403, "body": "forbidden" } }
              ],
              "cases": [ {
                "name": "greeting then availability",
                "context": { "language": "en" },
                "turns": [
                  { "input": "hello", "expect": { "actionsContain": ["greet"] } },
                  { "input": "rooms in Vienna tomorrow?",
                    "expect": {
                      "toolCalled": [ { "tool": "getAvailability", "argsSubset": { "city": "Vienna" } } ],
                      "toolNotCalled": ["createBooking"],
                      "properties": { "city": { "equals": "Vienna" } },
                      "outputMatches": "(?i)3 rooms",
                      "maxCostUsd": 0.02
                    } }
                ]
              } ]
            }
            """;

    @Test
    void documentedExampleParsesStrictlyAndValidates() throws Exception {
        strict.rejectUnknownFields(DOCUMENTED_EXAMPLE.getBytes(StandardCharsets.UTF_8), AgentTestSuite.class);
        AgentTestSuite suite = strict.parse(DOCUMENTED_EXAMPLE, AgentTestSuite.class);

        AgentTestSuiteValidator.validate(suite);

        assertEquals(2, suite.toolMocks().size());
        assertEquals(403, suite.toolMocks().get(1).response().effectiveHttpCode());
        var second = suite.cases().getFirst().turns().get(1).expect();
        assertEquals("Vienna", second.properties().get("city").equalTo());
        assertEquals(List.of("createBooking"), second.toolNotCalled());
        assertEquals(Map.of("city", "Vienna"), second.toolCalled().getFirst().argsSubset());
    }

    @Test
    @DisplayName("a minimal suite without defaults gets the documented ones")
    void absentFieldsUseDefaults() throws Exception {
        AgentTestSuite suite = mapper.readValue("{\"cases\":[{\"turns\":[{\"input\":\"hi\"}]}]}", AgentTestSuite.class);

        var defaults = suite.effectiveDefaults();
        assertEquals(AgentTestSuite.TOOL_MODE_MOCK, defaults.toolMode());
        assertEquals(AgentTestSuite.UNMOCKED_FAIL, defaults.unmockedTools(), "unmocked tools fail closed unless the author opts in");
        assertEquals(1, defaults.repetitions());
        assertEquals(1.0, defaults.passRate());
        assertEquals(AgentTestSuite.DEFAULT_MAX_COST_PER_RUN, suite.effectiveMaxCostPerRun());
        assertNull(suite.cases().getFirst().turns().getFirst().expect());
    }

    @Test
    void partialDefaultsKeepTheRest() throws Exception {
        AgentTestSuite suite = mapper.readValue("{\"defaults\":{\"repetitions\":5}}", AgentTestSuite.class);

        assertEquals(5, suite.effectiveDefaults().repetitions());
        assertEquals(AgentTestSuite.UNMOCKED_FAIL, suite.effectiveDefaults().unmockedTools());
    }

    @Test
    @DisplayName("a misspelled expectation is a 400, not an assertion that silently never runs")
    void typoIsRejectedByTheStrictParser() {
        String typo = "{\"cases\":[{\"turns\":[{\"input\":\"hi\",\"expect\":{\"actionContain\":[\"greet\"]}}]}]}";

        assertThrows(RuntimeException.class,
                () -> strict.rejectUnknownFields(typo.getBytes(StandardCharsets.UTF_8), AgentTestSuite.class));
    }

    @Test
    void roundTripKeepsEqualsUnderItsJsonName() throws Exception {
        AgentTestSuite suite = mapper.readValue(DOCUMENTED_EXAMPLE, AgentTestSuite.class);

        String json = mapper.writeValueAsString(suite);

        assertTrue(json.contains("\"equals\":\"Vienna\""), json);
        assertFalse(json.contains("equalTo"), json);
        assertEquals(suite, mapper.readValue(json, AgentTestSuite.class));
    }
}
