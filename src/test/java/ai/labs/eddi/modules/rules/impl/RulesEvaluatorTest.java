/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RulesEvaluatorTest {

    private IConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = mock(IConversationMemory.class);
    }

    private RulesEvaluator createEvaluator(RuleSet ruleSet) {
        return new RulesEvaluator(ruleSet, false, false);
    }

    // --- Empty / basic ---

    @Test
    void evaluate_emptyRuleSet() throws Exception {
        var ruleSet = new RuleSet();
        var evaluator = createEvaluator(ruleSet);
        var result = evaluator.evaluate(memory);
        assertTrue(result.getSuccessRules().isEmpty());
        assertTrue(result.getFailRules().isEmpty());
    }

    @Test
    void evaluate_ruleWithNoConditions_isSuccess() throws Exception {
        var rule = new Rule("always");
        rule.setActions(List.of("greet"));
        var group = new RuleGroup();
        group.getRules().add(rule);
        var ruleSet = new RuleSet();
        ruleSet.getRuleGroups().add(group);

        var result = createEvaluator(ruleSet).evaluate(memory);
        assertEquals(1, result.getSuccessRules().size());
        assertEquals("always", result.getSuccessRules().get(0).getName());
    }

    @Test
    void evaluate_ruleConditionSuccess() throws Exception {
        var cond = mock(IRuleCondition.class);
        when(cond.execute(any(), any())).thenReturn(ExecutionState.SUCCESS);
        var rule = new Rule("r1");
        rule.setConditions(new LinkedList<>(List.of(cond)));

        var group = new RuleGroup();
        group.getRules().add(rule);
        var ruleSet = new RuleSet();
        ruleSet.getRuleGroups().add(group);

        var result = createEvaluator(ruleSet).evaluate(memory);
        assertEquals(1, result.getSuccessRules().size());
    }

    @Test
    void evaluate_ruleConditionFail_goesToFailRules() throws Exception {
        var cond = mock(IRuleCondition.class);
        when(cond.execute(any(), any())).thenReturn(ExecutionState.FAIL);
        var rule = new Rule("r1");
        rule.setConditions(new LinkedList<>(List.of(cond)));

        var group = new RuleGroup();
        group.getRules().add(rule);
        var ruleSet = new RuleSet();
        ruleSet.getRuleGroups().add(group);

        var result = createEvaluator(ruleSet).evaluate(memory);
        assertTrue(result.getSuccessRules().isEmpty());
        assertEquals(1, result.getFailRules().size());
    }

    @Test
    void evaluate_ruleConditionError_throwsException() {
        var cond = mock(IRuleCondition.class);
        try {
            when(cond.execute(any(), any())).thenReturn(ExecutionState.ERROR);
        } catch (Exception e) {
            fail(e);
        }
        var rule = new Rule("r1");
        rule.setConditions(new LinkedList<>(List.of(cond)));

        var group = new RuleGroup();
        group.getRules().add(rule);
        var ruleSet = new RuleSet();
        ruleSet.getRuleGroups().add(group);

        assertThrows(RulesEvaluator.RuleExecutionException.class,
                () -> createEvaluator(ruleSet).evaluate(memory));
    }

    // --- Execution strategies ---

    @Test
    void evaluate_executeUntilFirstSuccess_stopsAfterFirst() throws Exception {
        var cond1 = mock(IRuleCondition.class);
        when(cond1.execute(any(), any())).thenReturn(ExecutionState.SUCCESS);

        var rule1 = new Rule("r1");
        rule1.setConditions(new LinkedList<>(List.of(cond1)));
        var rule2 = new Rule("r2");
        rule2.setConditions(new LinkedList<>(List.of(cond1)));

        var group = new RuleGroup();
        group.setExecutionStrategy(RuleGroup.ExecutionStrategy.executeUntilFirstSuccess);
        group.getRules().addAll(List.of(rule1, rule2));

        var ruleSet = new RuleSet();
        ruleSet.getRuleGroups().add(group);

        var result = createEvaluator(ruleSet).evaluate(memory);
        assertEquals(1, result.getSuccessRules().size());
        assertEquals("r1", result.getSuccessRules().get(0).getName());
    }

    @Test
    void evaluate_executeAll_continuesAfterSuccess() throws Exception {
        var cond = mock(IRuleCondition.class);
        when(cond.execute(any(), any())).thenReturn(ExecutionState.SUCCESS);

        var rule1 = new Rule("r1");
        rule1.setConditions(new LinkedList<>(List.of(cond)));
        var rule2 = new Rule("r2");
        rule2.setConditions(new LinkedList<>(List.of(cond)));

        var group = new RuleGroup();
        group.setExecutionStrategy(RuleGroup.ExecutionStrategy.executeAll);
        group.getRules().addAll(List.of(rule1, rule2));

        var ruleSet = new RuleSet();
        ruleSet.getRuleGroups().add(group);

        var result = createEvaluator(ruleSet).evaluate(memory);
        assertEquals(2, result.getSuccessRules().size());
    }

    // --- Null rule set ---

    @Test
    void evaluate_nullRuleSet_throwsIllegalArgument() {
        var evaluator = new RulesEvaluator();
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(memory));
    }

    // --- Setters ---

    @Test
    void settersAndGetters() {
        var evaluator = new RulesEvaluator();
        var ruleSet = new RuleSet();
        evaluator.setRuleSet(ruleSet);
        evaluator.setAppendActions(true);
        evaluator.setExpressionsAsActions(true);
        assertSame(ruleSet, evaluator.getRuleSet());
        assertTrue(evaluator.isAppendActions());
        assertTrue(evaluator.isExpressionsAsActions());
    }
}
