/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A behavior rule condition that cannot evaluate itself must be refused at
 * configure() time — with a message that names the offending rule — instead of
 * silently degrading into a condition that matches every conversation step.
 */
@DisplayName("Behavior rule configuration validation")
class RuleConfigValidationTest {

    private static final String CONFIG_URI = "eddi://ai.labs.rules/rulestore/rulesets/000000000000000000000001?version=1";

    private RuleDeserialization ruleDeserialization;
    private IJsonSerialization jsonSerialization;
    private RulesEvaluationTask task;

    @BeforeEach
    void setUp() throws Exception {
        IExpressionProvider expressionProvider = mock(IExpressionProvider.class);
        when(expressionProvider.parseExpressions(anyString()))
                .thenAnswer(invocation -> new Expressions(new Expression(invocation.getArgument(0, String.class))));

        ruleDeserialization = new RuleDeserialization(
                new ObjectMapper(),
                expressionProvider,
                mock(IJsonSerialization.class),
                mock(IMemoryItemConverter.class),
                mock(CapabilityRegistryService.class),
                mock(ITemplatingEngine.class));

        jsonSerialization = mock(IJsonSerialization.class);
        task = new RulesEvaluationTask(new IResourceClientLibraryStub(), jsonSerialization, ruleDeserialization, expressionProvider);
    }

    private String ruleSetWith(String ruleName, String conditionsJson) {
        return """
                {
                  "behaviorGroups": [
                    {
                      "name": "group1",
                      "rules": [
                        {
                          "name": "%s",
                          "actions": ["some_action"],
                          "conditions": %s
                        }
                      ]
                    }
                  ]
                }
                """.formatted(ruleName, conditionsJson);
    }

    private Map<String, Object> taskConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("uri", CONFIG_URI);
        return config;
    }

    @Test
    @DisplayName("an actionmatcher without actions is refused, naming the rule")
    void emptyActionMatcher_isRefused() {
        String json = ruleSetWith("Guard Rule", """
                [ { "type": "actionmatcher", "configs": { "occurrence": "lastStep" } } ]
                """);

        var exception = assertThrows(IllegalArgumentException.class, () -> ruleDeserialization.deserialize(json));

        assertTrue(exception.getMessage().contains("Guard Rule"), exception.getMessage());
        assertTrue(exception.getMessage().contains("actionmatcher"), exception.getMessage());
        assertTrue(exception.getMessage().contains("actions"), exception.getMessage());
    }

    @Test
    @DisplayName("a misspelled actions key is refused rather than matching everything")
    void misspelledActionsKey_isRefused() {
        String json = ruleSetWith("Typo Rule", """
                [ { "type": "actionmatcher", "configs": { "action": "greet", "occurrence": "lastStep" } } ]
                """);

        var exception = assertThrows(IllegalArgumentException.class, () -> ruleDeserialization.deserialize(json));

        assertTrue(exception.getMessage().contains("Typo Rule"), exception.getMessage());
    }

    @Test
    @DisplayName("an inputmatcher without expressions is refused, naming the rule")
    void emptyInputMatcher_isRefused() {
        String json = ruleSetWith("Input Guard", """
                [ { "type": "inputmatcher", "configs": { "occurrence": "currentStep" } } ]
                """);

        var exception = assertThrows(IllegalArgumentException.class, () -> ruleDeserialization.deserialize(json));

        assertTrue(exception.getMessage().contains("Input Guard"), exception.getMessage());
        assertTrue(exception.getMessage().contains("expressions"), exception.getMessage());
    }

    @Test
    @DisplayName("a typo'd occurrence is refused, naming the value and the legal set")
    void misspelledOccurrence_isRefused() {
        String json = ruleSetWith("Last Step Guard", """
                [ { "type": "actionmatcher", "configs": { "actions": "greet", "occurrence": "lastSteps" } } ]
                """);

        var exception = assertThrows(IllegalArgumentException.class, () -> ruleDeserialization.deserialize(json));

        assertTrue(exception.getMessage().contains("Last Step Guard"), exception.getMessage());
        assertTrue(exception.getMessage().contains("lastSteps"), exception.getMessage());
        assertTrue(exception.getMessage().contains("anyStep"), exception.getMessage());
    }

    @Test
    @DisplayName("a negation without nested conditions is refused")
    void emptyNegation_isRefused() {
        String json = ruleSetWith("Empty Negation", """
                [ { "type": "negation" } ]
                """);

        var exception = assertThrows(IllegalArgumentException.class, () -> ruleDeserialization.deserialize(json));

        assertTrue(exception.getMessage().contains("Empty Negation"), exception.getMessage());
        assertTrue(exception.getMessage().contains("negation"), exception.getMessage());
    }

    @Test
    @DisplayName("a contextmatcher with an unknown contextType is refused")
    void unknownContextType_isRefused() {
        String json = ruleSetWith("Context Rule", """
                [ { "type": "contextmatcher", "configs": { "contextKey": "language", "contextType": "expression" } } ]
                """);

        var exception = assertThrows(IllegalArgumentException.class, () -> ruleDeserialization.deserialize(json));

        assertTrue(exception.getMessage().contains("Context Rule"), exception.getMessage());
        assertTrue(exception.getMessage().contains("expression"), exception.getMessage());
    }

    @Test
    @DisplayName("a two-child negation is accepted (the documented form)")
    void documentedTwoChildNegation_isAccepted() throws Exception {
        String json = ruleSetWith("Not Both", """
                [ {
                    "type": "negation",
                    "conditions": [
                      { "type": "actionmatcher", "configs": { "actions": "a" } },
                      { "type": "actionmatcher", "configs": { "actions": "b" } }
                    ]
                } ]
                """);

        RuleSet ruleSet = ruleDeserialization.deserialize(json);

        var conditions = ruleSet.getRuleGroups().get(0).getRules().get(0).getConditions();
        assertEquals(1, conditions.size(), "expected exactly one negation condition");
        assertEquals(2, conditions.get(0).getConditions().size(), "expected both children to survive deserialization");
    }

    @Test
    @DisplayName("configure() surfaces an invalid condition as WorkflowConfigurationException naming the rule")
    void configureRejectsInvalidRuleSet() throws Exception {
        String json = ruleSetWith("Broken Guard", """
                [ { "type": "actionmatcher", "configs": { "occurrence": "lastStep" } } ]
                """);
        when(jsonSerialization.serialize(any())).thenReturn(json);

        var exception = assertThrows(WorkflowConfigurationException.class, () -> task.configure(taskConfig(), Collections.emptyMap()));

        assertTrue(exception.getMessage().contains("Broken Guard"), exception.getMessage());
    }

    @Test
    @DisplayName("configure() accepts a valid ruleset")
    void configureAcceptsValidRuleSet() throws Exception {
        String json = ruleSetWith("Valid Guard", """
                [ { "type": "actionmatcher", "configs": { "actions": "greet", "occurrence": "lastStep" } } ]
                """);
        when(jsonSerialization.serialize(any())).thenReturn(json);

        Object evaluator = task.configure(taskConfig(), Collections.emptyMap());

        assertInstanceOf(RulesEvaluator.class, evaluator);
    }

    /**
     * Minimal stub — the ruleset JSON is supplied via the serialization mock, so
     * the resource itself only has to be non-null.
     */
    private static class IResourceClientLibraryStub implements IResourceClientLibrary {

        @Override
        public void init() {
            // not needed
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getResource(URI uri, Class<T> clazz) {
            if (clazz == RuleSetConfiguration.class) {
                return (T) new RuleSetConfiguration();
            }
            return null;
        }

        @Override
        public Response duplicateResource(URI uri) {
            return null;
        }

        @Override
        public Response deleteResource(URI uri, boolean permanent) {
            return null;
        }
    }
}
