/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agenttests;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Defaults;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Expectation;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.MockResponse;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.PropertyExpectation;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.TestCase;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.ToolMock;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Turn;
import ai.labs.eddi.configs.agenttests.mongo.AgentTestSuiteStore;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AgentTestSuiteValidator")
class AgentTestSuiteValidatorTest {

    private static AgentTestSuite suiteWith(Defaults defaults, List<ToolMock> mocks, List<TestCase> cases) {
        return new AgentTestSuite("s", null, defaults, null, mocks, cases);
    }

    private static TestCase oneTurn(Expectation expect) {
        return new TestCase("c", null, null, null, List.of(new Turn("hi", null, expect)));
    }

    private static Expectation outputMatches(String regex) {
        return new Expectation(null, null, null, null, regex, null, null, null, null, null);
    }

    private static String message(AgentTestSuite suite) {
        return assertThrows(IllegalArgumentException.class, () -> AgentTestSuiteValidator.validate(suite)).getMessage();
    }

    @Test
    void validSuitePasses() {
        assertDoesNotThrow(() -> AgentTestSuiteValidator.validate(suiteWith(null, null, List.of(oneTurn(null)))));
    }

    @Test
    void emptySuiteIsADraftAndAllowed() {
        assertDoesNotThrow(() -> AgentTestSuiteValidator.validate(new AgentTestSuite("draft", null, null, null, null, null)));
    }

    @Test
    void unknownToolModeIsRejected() {
        assertTrue(message(suiteWith(new Defaults("replay", null, null, null, null), null, null)).contains("toolMode"));
    }

    @Test
    void unknownUnmockedModeIsRejected() {
        assertTrue(message(suiteWith(new Defaults(null, "ignore", null, null, null), null, null)).contains("unmockedTools"));
    }

    @Test
    void passRateOutsideZeroToOneIsRejected() {
        var testCase = new TestCase("c", null, null, 1.5, List.of(new Turn("hi", null, null)));
        assertTrue(message(suiteWith(null, null, List.of(testCase))).contains("cases[0].passRate"));
    }

    @Test
    void repetitionsAboveTheCapAreRejected() {
        var testCase = new TestCase("c", null, AgentTestSuiteValidator.MAX_REPETITIONS + 1, null, List.of(new Turn("hi", null, null)));
        assertTrue(message(suiteWith(null, null, List.of(testCase))).contains("repetitions"));
    }

    @Test
    void caseWithoutTurnsIsRejected() {
        assertTrue(message(suiteWith(null, null, List.of(new TestCase("c", null, null, null, List.of())))).contains("turns"));
    }

    @Test
    void turnWithoutInputIsRejected() {
        var testCase = new TestCase("c", null, null, null, List.of(new Turn(null, null, null)));
        assertTrue(message(suiteWith(null, null, List.of(testCase))).contains("input"));
    }

    @Test
    void invalidRegexIsA400NamingTheField() {
        String msg = message(suiteWith(null, null, List.of(oneTurn(outputMatches("(unclosed")))));
        assertTrue(msg.contains("outputMatches"), msg);
    }

    @Test
    @DisplayName("an over-long regex is refused at save time — the first half of the ReDoS guard")
    void overlongRegexIsRejected() {
        String longPattern = "a".repeat(AgentTestSuiteValidator.MAX_PATTERN_LENGTH + 1);
        assertTrue(message(suiteWith(null, null, List.of(oneTurn(outputMatches(longPattern))))).contains("longer than"));
    }

    @Test
    void propertyConditionMustSaySomething() {
        var expect = new Expectation(null, null, Map.of("city", new PropertyExpectation(null, null, null, null)), null, null, null, null,
                null, null, null);
        assertTrue(message(suiteWith(null, null, List.of(oneTurn(expect)))).contains("properties.city"));
    }

    @Test
    void unknownConversationStateIsRejected() {
        var expect = new Expectation(null, null, null, null, null, null, null, null, null, "PAUSED");
        assertTrue(message(suiteWith(null, null, List.of(oneTurn(expect)))).contains("conversationState"));
    }

    @Test
    void mockNeedsToolAndResponse() {
        assertTrue(message(suiteWith(null, List.of(new ToolMock(" ", null, new MockResponse(200, "x"))), null)).contains("toolMocks[0].tool"));
        assertTrue(message(suiteWith(null, List.of(new ToolMock("t", null, null)), null)).contains("toolMocks[0].response"));
        assertTrue(message(suiteWith(null, List.of(new ToolMock("t", null, new MockResponse(42, "x"))), null)).contains("httpCode"));
    }

    @Test
    @DisplayName("cases × repetitions × turns is capped per run")
    void totalTurnsPerRunIsCapped() {
        var turns = Collections.nCopies(AgentTestSuiteValidator.MAX_TURNS_PER_CASE, new Turn("hi", null, null));
        var cases = new ArrayList<TestCase>();
        for (int i = 0; i < 3; i++) {
            cases.add(new TestCase("c" + i, null, AgentTestSuiteValidator.MAX_REPETITIONS, null, turns));
        }
        assertTrue(message(suiteWith(null, null, cases)).contains("turns"));
    }

    @Test
    void costCeilingMustBeSane() {
        assertTrue(message(new AgentTestSuite("s", null, null, -1.0, null, null)).contains("maxCostPerRun"));
    }

    @Test
    @DisplayName("the store runs the validator before anything is written")
    @SuppressWarnings("unchecked")
    void storeValidatesBeforeWriting() {
        IResourceStorageFactory factory = mock(IResourceStorageFactory.class);
        IResourceStorage<AgentTestSuite> storage = mock(IResourceStorage.class);
        when(factory.create(eq(AgentTestSuiteStore.COLLECTION_NAME), any(), eq(AgentTestSuite.class))).thenReturn(storage);
        var store = new AgentTestSuiteStore(factory, mock(IDocumentBuilder.class));

        var invalid = suiteWith(new Defaults("replay", null, null, null, null), null, null);

        assertThrows(IllegalArgumentException.class, () -> store.create(invalid));
        assertThrows(IllegalArgumentException.class, () -> store.update("0123456789abcdef01234567", 1, invalid));
        verifyNoInteractions(storage);
    }
}
