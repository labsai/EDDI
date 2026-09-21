/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RuleSetResult — rule outcome categories")
class RuleSetResultTest {

    @Test
    @DisplayName("every evaluated rule lands in exactly one of the two categories")
    void evaluatorProducesExactlyTwoCategories() throws Exception {
        IConversationMemory memory = mock(IConversationMemory.class);

        IRuleCondition failingCondition = mock(IRuleCondition.class);
        when(failingCondition.execute(any(), any())).thenReturn(ExecutionState.FAIL);

        var succeeding = new Rule("Succeeds");
        var failing = new Rule("Fails");
        failing.setConditions(new LinkedList<>(List.of(failingCondition)));

        var group = new RuleGroup();
        group.setExecutionStrategy(RuleGroup.ExecutionStrategy.executeAll);
        group.getRules().add(succeeding);
        group.getRules().add(failing);

        var ruleSet = new RuleSet();
        ruleSet.getRuleGroups().add(group);

        RuleSetResult result = new RulesEvaluator(ruleSet, true, false).evaluate(memory);

        assertEquals(List.of("Succeeds"), result.getSuccessRules().stream().map(Rule::getName).toList());
        assertEquals(List.of("Fails"), result.getFailRules().stream().map(Rule::getName).toList());
    }

    @Test
    @DisplayName("exposes no 'dropped success' category — nothing ever populated it")
    void exposesNoDroppedSuccessCategory() {
        boolean hasDroppedField = Arrays.stream(RuleSetResult.class.getDeclaredFields()).map(Field::getName)
                .anyMatch(name -> name.toLowerCase().contains("dropped"));
        assertFalse(hasDroppedField, "RuleSetResult must not declare a dropped-success field — no producer ever filled it, "
                + "so 'behavior_rules:droppedSuccess' could never appear in conversation memory");

        boolean hasDroppedAccessor = Arrays.stream(RuleSetResult.class.getDeclaredMethods()).map(Method::getName)
                .anyMatch(name -> name.toLowerCase().contains("dropped"));
        assertFalse(hasDroppedAccessor, "RuleSetResult must not expose a dropped-success accessor");
    }

    @Test
    @DisplayName("RulesEvaluationTask declares no 'droppedSuccess' memory key")
    void taskDeclaresNoDroppedSuccessKey() {
        boolean hasDroppedKey = Arrays.stream(RulesEvaluationTask.class.getDeclaredFields()).map(Field::getName)
                .anyMatch(name -> name.toUpperCase().contains("DROPPED"));

        assertFalse(hasDroppedKey, "RulesEvaluationTask must not write a 'behavior_rules:droppedSuccess' entry — "
                + "the key could never carry data and misled agent designers into matching on it");
    }
}
