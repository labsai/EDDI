package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.datastore.serialization.DeserializationException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
