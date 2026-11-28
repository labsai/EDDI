/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.datastore.serialization.DeserializationException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition;
import ai.labs.eddi.modules.rules.impl.conditions.TemplateDataScope;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RuleDeserializationTest {

    private RuleDeserialization ruleDeserialization;

    @BeforeEach
    void setUp() {
        ruleDeserialization = new RuleDeserialization(
                new ObjectMapper(),
                mock(IExpressionProvider.class),
                mock(IJsonSerialization.class),
                mock(IMemoryItemConverter.class),
                mock(CapabilityRegistryService.class),
                mock(ITemplatingEngine.class));
    }

    @Test
    void deserialize_emptyGroups() throws DeserializationException {
        String json = "{\"behaviorGroups\":[]}";
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        assertNotNull(ruleSet);
        assertTrue(ruleSet.getRuleGroups().isEmpty());
    }

    @Test
    void deserialize_groupWithName() throws DeserializationException {
        String json = """
                {
                  "behaviorGroups": [
                    {
                      "name": "greetings",
                      "executionStrategy": "executeAll",
                      "rules": []
                    }
                  ]
                }
                """;
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        assertEquals(1, ruleSet.getRuleGroups().size());
        assertEquals("greetings", ruleSet.getRuleGroups().get(0).getName());
        assertEquals(RuleGroup.ExecutionStrategy.executeAll, ruleSet.getRuleGroups().get(0).getExecutionStrategy());
    }

    @Test
    void deserialize_defaultExecutionStrategy() throws DeserializationException {
        String json = """
                {
                  "behaviorGroups": [
                    {
                      "name": "default-group",
                      "rules": []
                    }
                  ]
                }
                """;
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        assertEquals(RuleGroup.ExecutionStrategy.executeUntilFirstSuccess,
                ruleSet.getRuleGroups().get(0).getExecutionStrategy());
    }

    @Test
    void deserialize_ruleWithActions() throws DeserializationException {
        String json = """
                {
                  "behaviorGroups": [
                    {
                      "name": "test-group",
                      "rules": [
                        {
                          "name": "say-hello",
                          "actions": ["greet", "welcome"],
                          "conditions": []
                        }
                      ]
                    }
                  ]
                }
                """;
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        var rules = ruleSet.getRuleGroups().get(0).getRules();
        assertEquals(1, rules.size());
        assertEquals("say-hello", rules.get(0).getName());
        assertEquals(2, rules.get(0).getActions().size());
    }

    @Test
    void deserialize_ruleWithActionMatcher() throws DeserializationException {
        String json = """
                {
                  "behaviorGroups": [
                    {
                      "name": "group1",
                      "rules": [
                        {
                          "name": "check-action",
                          "actions": ["respond"],
                          "conditions": [
                            {
                              "type": "actionmatcher",
                              "configs": {
                                "actions": "greet"
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        var conditions = ruleSet.getRuleGroups().get(0).getRules().get(0).getConditions();
        assertEquals(1, conditions.size());
    }

    @Test
    void deserialize_negationCondition() throws DeserializationException {
        String json = """
                {
                  "behaviorGroups": [
                    {
                      "name": "group1",
                      "rules": [
                        {
                          "name": "not-something",
                          "actions": ["fallback"],
                          "conditions": [
                            {
                              "type": "negation",
                              "conditions": [
                                {
                                  "type": "actionmatcher",
                                  "configs": {
                                    "actions": "greet"
                                  }
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        var conditions = ruleSet.getRuleGroups().get(0).getRules().get(0).getConditions();
        assertEquals(1, conditions.size());
    }

    @Test
    void deserialize_invalidJson_throwsDeserializationException() {
        assertThrows(DeserializationException.class, () -> ruleDeserialization.deserialize("not json"));
    }

    @Test
    void deserialize_multipleGroups() throws DeserializationException {
        String json = """
                {
                  "behaviorGroups": [
                    { "name": "g1", "rules": [] },
                    { "name": "g2", "executionStrategy": "executeAll", "rules": [] }
                  ]
                }
                """;
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        assertEquals(2, ruleSet.getRuleGroups().size());
    }

    @Test
    void deserialize_connectorCondition() throws DeserializationException {
        String json = """
                {
                  "behaviorGroups": [
                    {
                      "name": "group1",
                      "rules": [
                        {
                          "name": "combo",
                          "actions": ["combo-action"],
                          "conditions": [
                            {
                              "type": "connector",
                              "configs": {
                                "operator": "AND"
                              },
                              "conditions": [
                                {
                                  "type": "actionmatcher",
                                  "configs": { "actions": "a" }
                                },
                                {
                                  "type": "actionmatcher",
                                  "configs": { "actions": "b" }
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        var conditions = ruleSet.getRuleGroups().get(0).getRules().get(0).getConditions();
        assertEquals(1, conditions.size());
    }

    @Test
    void deserialize_occurrenceCondition() throws DeserializationException {
        String json = """
                {
                  "behaviorGroups": [
                    {
                      "name": "group1",
                      "rules": [
                        {
                          "name": "occ-test",
                          "actions": ["occ-action"],
                          "conditions": [
                            {
                              "type": "occurrence",
                              "configs": {
                                "maxTimesOccurred": "3",
                                "behaviorRuleName": "occ-test"
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        var conditions = ruleSet.getRuleGroups().get(0).getRules().get(0).getConditions();
        assertEquals(1, conditions.size());
    }

    @Test
    void deserialize_dependencyCondition() throws DeserializationException {
        String json = """
                {
                  "behaviorGroups": [
                    {
                      "name": "group1",
                      "rules": [
                        {
                          "name": "dep-test",
                          "actions": ["dep-action"],
                          "conditions": [
                            {
                              "type": "dependency",
                              "configs": {
                                "reference": "other-rule"
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        var conditions = ruleSet.getRuleGroups().get(0).getRules().get(0).getConditions();
        assertEquals(1, conditions.size());
    }

    @Test
    void validateForSave_deploymentContextWithTagMatches_isRefusedNamingTheRule() {
        String json = singleConditionRuleSet("Cautious In Production", """
                { "type": "deploymentContext", "configs": { "when": "production", "tagMatches": "high-risk" } }
                """);

        var thrown = assertThrows(IllegalArgumentException.class, () -> ruleDeserialization.validateForSave(json));

        assertTrue(thrown.getMessage().contains("Cautious In Production"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("tagMatches"), thrown.getMessage());
    }

    /**
     * A ruleset stored before {@code tagMatches} was retired must keep deploying:
     * refusing it on load stopped the whole agent from deploying. It is accepted
     * with a WARN naming the ruleset and the rule.
     */
    @Test
    void deserialize_storedDeploymentContextWithTagMatches_isAcceptedWithAWarn() throws DeserializationException {
        String json = singleConditionRuleSet("Cautious In Production", """
                { "type": "deploymentContext", "configs": { "when": "production", "tagMatches": "high-risk" } }
                """);

        List<String> warnings = new ArrayList<>();
        RuleSet ruleSet = captureWarnings(warnings,
                () -> ruleDeserialization.deserialize(json, "eddi://ai.labs.rules/rulestore/rulesets/rs1?version=3"));

        assertEquals(1, ruleSet.getRuleGroups().get(0).getRules().get(0).getConditions().size());
        assertEquals(1, warnings.size(), "one WARN per retired config: " + warnings);
        assertTrue(warnings.get(0).contains("Cautious In Production"), warnings.get(0));
        assertTrue(warnings.get(0).contains("rulesets/rs1?version=3"), warnings.get(0));
        assertTrue(warnings.get(0).contains("tagMatches"), warnings.get(0));
    }

    /**
     * The case the "removing it changes nothing at runtime" claim missed: the inner
     * condition never matched, so the negation always did and the rule fired on
     * every turn. A stored ruleset keeps exactly that behaviour.
     */
    @Test
    void deserialize_storedNegatedTagMatches_stillEvaluatesToSuccess() throws Exception {
        String json = singleConditionRuleSet("Not High Risk", """
                { "type": "negation", "conditions": [
                  { "type": "deploymentContext", "configs": { "tagMatches": "high-risk" } }
                ] }
                """);

        List<String> warnings = new ArrayList<>();
        RuleSet ruleSet = captureWarnings(warnings, () -> ruleDeserialization.deserialize(json));
        IRuleCondition negation = ruleSet.getRuleGroups().get(0).getRules().get(0).getConditions().get(0);

        assertEquals(IRuleCondition.ExecutionState.SUCCESS, negation.execute(mock(IConversationMemory.class), new ArrayList<>()));
        assertEquals(1, warnings.size(), "the nested retired config is reported too: " + warnings);
        assertTrue(warnings.get(0).contains("Not High Risk"), warnings.get(0));
    }

    @Test
    void validateForSave_negatedTagMatches_isRefusedToo() {
        String json = singleConditionRuleSet("Not High Risk", """
                { "type": "negation", "conditions": [
                  { "type": "deploymentContext", "configs": { "tagMatches": "high-risk" } }
                ] }
                """);

        var thrown = assertThrows(IllegalArgumentException.class, () -> ruleDeserialization.validateForSave(json));

        assertTrue(thrown.getMessage().contains("tagMatches"), thrown.getMessage());
    }

    @Test
    void deserialize_deploymentContextWithWhenOnly_isAccepted() throws DeserializationException {
        String json = singleConditionRuleSet("Production Only", """
                { "type": "deploymentContext", "configs": { "when": "production" } }
                """);

        var conditions = ruleDeserialization.deserialize(json).getRuleGroups().get(0).getRules().get(0).getConditions();

        assertEquals(1, conditions.size());
        assertDoesNotThrow(() -> ruleDeserialization.validateForSave(json));
    }

    @Test
    void validateForSave_dependencyWithoutReference_isRefusedNamingTheRule() {
        String json = singleConditionRuleSet("Follow Up", """
                { "type": "dependency", "configs": {} }
                """);

        var thrown = assertThrows(IllegalArgumentException.class, () -> ruleDeserialization.validateForSave(json));

        assertTrue(thrown.getMessage().contains("Follow Up"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("reference"), thrown.getMessage());
    }

    @Test
    void deserialize_storedDependencyWithoutReference_isAcceptedWithAWarnAndNeverMatches() throws Exception {
        String json = singleConditionRuleSet("Follow Up", """
                { "type": "dependency", "configs": {} }
                """);

        List<String> warnings = new ArrayList<>();
        RuleSet ruleSet = captureWarnings(warnings, () -> ruleDeserialization.deserialize(json));
        IRuleCondition dependency = ruleSet.getRuleGroups().get(0).getRules().get(0).getConditions().get(0);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("Follow Up"), warnings.get(0));
        var memory = mock(IConversationMemory.class);
        assertEquals(IRuleCondition.ExecutionState.FAIL,
                TemplateDataScope.call(memory, () -> dependency.execute(memory, new ArrayList<>())));
    }

    @FunctionalInterface
    private interface DeserializationCall {
        RuleSet run() throws DeserializationException;
    }

    /**
     * Runs {@code call} with the RuleDeserialization logger opened (the test
     * logging config turns ai.labs.eddi off) and collects its WARN messages.
     */
    private static RuleSet captureWarnings(List<String> warnings, DeserializationCall call) throws DeserializationException {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getParameters() == null
                            ? String.valueOf(record.getMessage())
                            : String.format(record.getMessage(), record.getParameters()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger julLogger = Logger.getLogger(RuleDeserialization.class.getName());
        Level previousLevel = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
        try {
            return call.run();
        } finally {
            julLogger.removeHandler(handler);
            julLogger.setLevel(previousLevel);
        }
    }

    private static String singleConditionRuleSet(String ruleName, String condition) {
        return """
                { "behaviorGroups": [ { "name": "group1", "rules": [
                  { "name": "%s", "actions": ["an-action"], "conditions": [ %s ] }
                ] } ] }
                """.formatted(ruleName, condition);
    }

    @Test
    void deserialize_contentTypeMatcherCondition() throws DeserializationException {
        String json = """
                {
                  "behaviorGroups": [
                    {
                      "name": "group1",
                      "rules": [
                        {
                          "name": "ct-test",
                          "actions": ["ct-action"],
                          "conditions": [
                            {
                              "type": "contentTypeMatcher",
                              "configs": {
                                "contentType": "image/png"
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
        RuleSet ruleSet = ruleDeserialization.deserialize(json);
        var conditions = ruleSet.getRuleGroups().get(0).getRules().get(0).getConditions();
        assertEquals(1, conditions.size());
    }
}
