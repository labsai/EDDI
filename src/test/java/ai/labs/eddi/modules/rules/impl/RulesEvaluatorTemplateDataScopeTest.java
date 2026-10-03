/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.rules.impl.conditions.DynamicValueMatcher;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The value-reading conditions share one conversion of the memory per rule-set
 * evaluation — they used to convert it once per condition.
 */
class RulesEvaluatorTemplateDataScopeTest {

    @Test
    @DisplayName("five dynamic value matchers, one memory conversion")
    void oneConversionPerEvaluation() throws Exception {
        var memory = mock(IConversationMemory.class);
        var converter = mock(IMemoryItemConverter.class);
        when(converter.convert(memory)).thenReturn(Map.of("properties", Map.of("tier", "gold")));

        var group = new RuleGroup();
        group.setExecutionStrategy(RuleGroup.ExecutionStrategy.executeAll);
        for (int i = 0; i < 5; i++) {
            var matcher = new DynamicValueMatcher(converter);
            matcher.setConfigs(Map.of("valuePath", "properties.tier", "equals", "gold"));
            var rule = new Rule("r" + i);
            rule.setConditions(new LinkedList<IRuleCondition>(List.of(matcher)));
            group.getRules().add(rule);
        }
        var ruleSet = new RuleSet();
        ruleSet.getRuleGroups().add(group);

        var result = new RulesEvaluator(ruleSet, false, false).evaluate(memory);

        assertEquals(5, result.getSuccessRules().size());
        verify(converter, times(1)).convert(memory);
    }
}
