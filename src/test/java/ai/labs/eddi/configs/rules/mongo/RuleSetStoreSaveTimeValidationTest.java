/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules.mongo;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.rules.model.RuleConditionConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.DocumentBuilder;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.rules.impl.RuleDeserialization;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding E6(2): the behaviour-rule condition checks added in wave 1 only ran
 * at agent-<em>deploy</em> time, so a ruleset with an empty
 * {@code actionmatcher} or a typo'd {@code occurrence} was answered with 201
 * and only failed later, with a message naming neither the rule nor its group.
 *
 * <p>
 * {@link RuleSetStore#validate} hoists exactly those checks onto the write path
 * by running the ruleset through the very same {@code IRuleDeserialization} the
 * pipeline uses — no duplicated validation logic. These tests wire the real
 * deserializer (only its unrelated collaborators are mocked) so they fail if
 * the hoist is reverted, and would also fail if the checks themselves were
 * reimplemented divergently here.
 * </p>
 */
@DisplayName("RuleSetStore — save-time rule validation")
class RuleSetStoreSaveTimeValidationTest {

    private IResourceStorage<RuleSetConfiguration> resourceStorage;
    private RuleSetStore ruleSetStore;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        var storageFactory = mock(IResourceStorageFactory.class);
        resourceStorage = mock(IResourceStorage.class);
        when(storageFactory.create(eq("rulesets"), any(), eq(RuleSetConfiguration.class), any(String[].class))).thenReturn(resourceStorage);

        ObjectMapper objectMapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
        var documentBuilder = new DocumentBuilder(new JsonSerialization(objectMapper));

        var ruleDeserialization = new RuleDeserialization(objectMapper, mock(IExpressionProvider.class),
                new JsonSerialization(objectMapper), mock(IMemoryItemConverter.class), mock(CapabilityRegistryService.class),
                mock(ITemplatingEngine.class));

        ruleSetStore = new RuleSetStore(storageFactory, documentBuilder, ruleDeserialization);
    }

    private static RuleSetConfiguration ruleSetWith(String ruleName, String conditionType, Map<String, String> configs) {
        var condition = new RuleConditionConfiguration();
        condition.setType(conditionType);
        condition.setConfigs(configs);

        var rule = new RuleConfiguration();
        rule.setName(ruleName);
        rule.setActions(List.of("do_something"));
        rule.setConditions(List.of(condition));

        var group = new RuleGroupConfiguration();
        group.setName("main");
        group.setRules(List.of(rule));

        var ruleSet = new RuleSetConfiguration();
        ruleSet.setBehaviorGroups(List.of(group));
        return ruleSet;
    }

    @Test
    @DisplayName("an empty 'actions' matcher is refused at save time, naming the rule")
    void emptyActionMatcherIsRefusedOnCreate() throws Exception {
        var ruleSet = ruleSetWith("Confirm creation", "actionmatcher", Map.of("actions", ""));

        var thrown = assertThrows(IllegalArgumentException.class, () -> ruleSetStore.create(ruleSet));

        assertTrue(thrown.getMessage().contains("Confirm creation"),
                "the offending rule must be named so the author can find it: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("actions"), "the offending config key must be named: " + thrown.getMessage());
        verify(resourceStorage, never()).newResource(any());
    }

    @Test
    @DisplayName("an unknown 'occurrence' value is refused at save time, listing the legal values")
    void unknownOccurrenceIsRefusedOnCreate() throws Exception {
        var ruleSet = ruleSetWith("Start over", "actionmatcher", Map.of("actions", "confirm_creation", "occurrence", "lastSteps"));

        var thrown = assertThrows(IllegalArgumentException.class, () -> ruleSetStore.create(ruleSet));

        assertTrue(thrown.getMessage().contains("Start over"), "the offending rule must be named: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("lastSteps"), "the rejected value must be quoted back: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("lastStep"), "the legal values must be listed: " + thrown.getMessage());
        verify(resourceStorage, never()).newResource(any());
    }

    @Test
    @DisplayName("an unknown condition type is refused at save time")
    void unknownConditionTypeIsRefusedOnCreate() throws Exception {
        var ruleSet = ruleSetWith("Typo'd condition", "actionmatchr", Map.of("actions", "confirm_creation"));

        var thrown = assertThrows(IllegalArgumentException.class, () -> ruleSetStore.create(ruleSet));

        assertTrue(thrown.getMessage().contains("actionmatchr"), "the unknown type must be quoted back: " + thrown.getMessage());
        verify(resourceStorage, never()).newResource(any());
    }

    @Test
    @DisplayName("update is guarded too — an invalid ruleset never reaches storage")
    void invalidRuleSetIsRefusedOnUpdate() throws Exception {
        var ruleSet = ruleSetWith("Start over", "actionmatcher", Map.of("actions", "confirm_creation", "occurrence", "lastSteps"));

        assertThrows(IllegalArgumentException.class, () -> ruleSetStore.update("rs1", 1, ruleSet));

        verify(resourceStorage, never()).read(any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a well-formed ruleset is still persisted")
    void validRuleSetIsPersisted() throws Exception {
        var createdResource = mock(IResourceStorage.IResource.class);
        when(resourceStorage.newResource(any(RuleSetConfiguration.class))).thenReturn(createdResource);

        var ruleSet = ruleSetWith("Greet", "actionmatcher", Map.of("actions", "CONVERSATION_START", "occurrence", "lastStep"));
        ruleSetStore.create(ruleSet);

        verify(resourceStorage).newResource(ruleSet);
        verify(resourceStorage).store(createdResource);
    }
}
