/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleTest {

    @Test
    void constructorSetsName() {
        var rule = new Rule("greeting");
        assertEquals("greeting", rule.getName());
    }

    @Test
    void defaultConstructor() {
        var rule = new Rule();
        assertNull(rule.getName());
    }

    @Test
    void settersAndGetters() {
        var rule = new Rule("r1");
        rule.setActions(List.of("a1", "a2"));
        rule.setName("renamed");
        assertEquals("renamed", rule.getName());
        assertEquals(2, rule.getActions().size());
    }

    // --- execute ---

    @Test
    void execute_noConditions_returnsSuccess() throws Exception {
        var rule = new Rule("r1");
        var memory = mock(IConversationMemory.class);
        var trace = new LinkedList<Rule>();

        var state = rule.execute(memory, trace);
        assertEquals(ExecutionState.SUCCESS, state);
    }

    @Test
    void execute_allConditionsPass_returnsSuccess() throws Exception {
        var rule = new Rule("r1");
        var condition = mock(IRuleCondition.class);
        when(condition.execute(any(), any())).thenReturn(ExecutionState.SUCCESS);
        rule.setConditions(List.of(condition));

        var state = rule.execute(mock(IConversationMemory.class), new LinkedList<>());
        assertEquals(ExecutionState.SUCCESS, state);
    }

    @Test
    void execute_conditionFails_returnsFail() throws Exception {
        var rule = new Rule("r1");
        var cond1 = mock(IRuleCondition.class);
        when(cond1.execute(any(), any())).thenReturn(ExecutionState.FAIL);
        rule.setConditions(List.of(cond1));

        var state = rule.execute(mock(IConversationMemory.class), new LinkedList<>());
        assertEquals(ExecutionState.FAIL, state);
    }

    @Test
    void execute_conditionError_returnsError() throws Exception {
        var rule = new Rule("r1");
        var cond = mock(IRuleCondition.class);
        when(cond.execute(any(), any())).thenReturn(ExecutionState.ERROR);
        rule.setConditions(List.of(cond));

        var state = rule.execute(mock(IConversationMemory.class), new LinkedList<>());
        assertEquals(ExecutionState.ERROR, state);
    }

    @Test
    void execute_shortCircuitsOnFail() throws Exception {
        var rule = new Rule("r1");
        var cond1 = mock(IRuleCondition.class);
        var cond2 = mock(IRuleCondition.class);
        when(cond1.execute(any(), any())).thenReturn(ExecutionState.FAIL);
        rule.setConditions(List.of(cond1, cond2));

        rule.execute(mock(IConversationMemory.class), new LinkedList<>());
        verify(cond2, never()).execute(any(), any());
    }

    @Test
    void execute_infiniteLoopDetection() {
        var rule = new Rule("loop");
        var trace = new LinkedList<Rule>();
        trace.add(rule); // already in trace

        assertThrows(Rule.InfiniteLoopException.class,
                () -> rule.execute(mock(IConversationMemory.class), trace));
    }

    // --- equals / hashCode ---

    @Test
    void equality_sameName() {
        assertEquals(new Rule("r1"), new Rule("r1"));
        assertEquals(new Rule("r1").hashCode(), new Rule("r1").hashCode());
    }

    @Test
    void equality_differentName() {
        assertNotEquals(new Rule("r1"), new Rule("r2"));
    }

    @Test
    void equality_notARule() {
        assertNotEquals(new Rule("r1"), "r1");
    }

    @Test
    void equality_sameInstance() {
        var rule = new Rule("r1");
        assertEquals(rule, rule);
    }

    // --- clone ---

    @Test
    void clone_copiesNameAndConditions() throws CloneNotSupportedException {
        var rule = new Rule("original");
        var cond = mock(IRuleCondition.class);
        when(cond.clone()).thenReturn(cond);
        rule.setConditions(new LinkedList<>(List.of(cond)));

        var clone = rule.clone();
        assertEquals("original", clone.getName());
        assertEquals(1, clone.getConditions().size());
    }

    // --- toString ---

    @Test
    void toStringReturnsName() {
        assertEquals("test-rule", new Rule("test-rule").toString());
    }
}
