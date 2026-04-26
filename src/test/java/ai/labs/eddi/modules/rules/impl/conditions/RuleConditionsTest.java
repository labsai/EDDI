/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.modules.rules.impl.RuleGroup;
import ai.labs.eddi.modules.rules.impl.RuleSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link Occurrence} and {@link Dependency} rule conditions.
 */
@DisplayName("Rule Conditions Tests")
class RuleConditionsTest {

    @Nested
    @DisplayName("Occurrence")
    class OccurrenceTest {

        @Test
        @DisplayName("should return ID 'occurrence'")
        void returnsId() {
            assertEquals("occurrence", new Occurrence().getId());
        }

        @Test
        @DisplayName("should set and get configs")
        void setAndGetConfigs() {
            Occurrence occ = new Occurrence();
            Map<String, String> configs = Map.of(
                    "behaviorRuleName", "greetRule",
                    "maxTimesOccurred", "3",
                    "minTimesOccurred", "1");
            occ.setConfigs(configs);

            Map<String, String> result = occ.getConfigs();
            assertEquals("greetRule", result.get("behaviorRuleName"));
            assertEquals("3", result.get("maxTimesOccurred"));
            assertEquals("1", result.get("minTimesOccurred"));
        }

        @Test
        @DisplayName("should handle null configs")
        void handlesNullConfigs() {
            Occurrence occ = new Occurrence();
            occ.setConfigs(null);
            // Should not throw
            assertNotNull(occ.getConfigs());
        }

        @Test
        @DisplayName("should handle empty configs")
        void handlesEmptyConfigs() {
            Occurrence occ = new Occurrence();
            occ.setConfigs(Map.of());
            assertNotNull(occ.getConfigs());
        }

        @Test
        @DisplayName("should return FAIL when data is null")
        void failsWhenDataNull() {
            Occurrence occ = new Occurrence();
            occ.setConfigs(Map.of("behaviorRuleName", "rule1"));

            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IConversationStepStack stepStack = mock(IConversationMemory.IConversationStepStack.class);
            when(memory.getAllSteps()).thenReturn(stepStack);
            when(stepStack.getAllData("behavior_rules:success")).thenReturn(null);

            assertEquals(IRuleCondition.ExecutionState.FAIL, occ.execute(memory, new ArrayList<>()));
        }

        @Test
        @DisplayName("should return SUCCESS when within min/max bounds")
        @SuppressWarnings("unchecked")
        void successWithinBounds() {
            Occurrence occ = new Occurrence();
            occ.setConfigs(Map.of("behaviorRuleName", "greet", "minTimesOccurred", "1", "maxTimesOccurred", "3"));

            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IConversationStepStack stepStack = mock(IConversationMemory.IConversationStepStack.class);
            when(memory.getAllSteps()).thenReturn(stepStack);

            // Create mock data: rule "greet" appeared twice
            IData<List<String>> data1 = mock(IData.class);
            when(data1.getResult()).thenReturn(List.of("greet", "farewell"));
            IData<List<String>> data2 = mock(IData.class);
            when(data2.getResult()).thenReturn(List.of("greet"));

            List<List<IData<List<String>>>> allData = List.of(
                    List.of(data1),
                    List.of(data2));
            doReturn(allData).when(stepStack).getAllData("behavior_rules:success");

            assertEquals(IRuleCondition.ExecutionState.SUCCESS, occ.execute(memory, new ArrayList<>()));
        }

        @Test
        @DisplayName("should return FAIL when exceeds max")
        @SuppressWarnings("unchecked")
        void failExceedsMax() {
            Occurrence occ = new Occurrence();
            occ.setConfigs(Map.of("behaviorRuleName", "greet", "maxTimesOccurred", "1"));

            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IConversationStepStack stepStack = mock(IConversationMemory.IConversationStepStack.class);
            when(memory.getAllSteps()).thenReturn(stepStack);

            IData<List<String>> data1 = mock(IData.class);
            when(data1.getResult()).thenReturn(List.of("greet", "greet"));

            doReturn(List.of(List.of(data1))).when(stepStack).getAllData("behavior_rules:success");

            assertEquals(IRuleCondition.ExecutionState.FAIL, occ.execute(memory, new ArrayList<>()));
        }

        @Test
        @DisplayName("should return FAIL when below min")
        @SuppressWarnings("unchecked")
        void failBelowMin() {
            Occurrence occ = new Occurrence();
            occ.setConfigs(Map.of("behaviorRuleName", "greet", "minTimesOccurred", "5"));

            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IConversationStepStack stepStack = mock(IConversationMemory.IConversationStepStack.class);
            when(memory.getAllSteps()).thenReturn(stepStack);

            IData<List<String>> data1 = mock(IData.class);
            when(data1.getResult()).thenReturn(List.of("greet"));

            doReturn(List.of(List.of(data1))).when(stepStack).getAllData("behavior_rules:success");

            assertEquals(IRuleCondition.ExecutionState.FAIL, occ.execute(memory, new ArrayList<>()));
        }

        @Test
        @DisplayName("should clone correctly")
        void clonesCorrectly() {
            Occurrence occ = new Occurrence();
            occ.setConfigs(Map.of("behaviorRuleName", "test", "maxTimesOccurred", "5", "minTimesOccurred", "2"));

            IRuleCondition clone = occ.clone();

            assertNotSame(occ, clone);
            assertInstanceOf(Occurrence.class, clone);
            assertEquals("test", clone.getConfigs().get("behaviorRuleName"));
        }

        @Test
        @DisplayName("should return SUCCESS with no min/max bounds (defaults -1)")
        @SuppressWarnings("unchecked")
        void successWithNoConstraints() {
            Occurrence occ = new Occurrence();
            occ.setConfigs(Map.of("behaviorRuleName", "greet"));

            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IConversationStepStack stepStack = mock(IConversationMemory.IConversationStepStack.class);
            when(memory.getAllSteps()).thenReturn(stepStack);

            IData<List<String>> data1 = mock(IData.class);
            when(data1.getResult()).thenReturn(List.of("other"));

            doReturn(List.of(List.of(data1))).when(stepStack).getAllData("behavior_rules:success");

            // No min/max set → both isMin and isMax default to true
            assertEquals(IRuleCondition.ExecutionState.SUCCESS, occ.execute(memory, new ArrayList<>()));
        }
    }

    @Nested
    @DisplayName("Dependency")
    class DependencyTest {

        @Test
        @DisplayName("should return ID 'dependency'")
        void returnsId() {
            assertEquals("dependency", new Dependency().getId());
        }

        @Test
        @DisplayName("should set and get configs")
        void setAndGetConfigs() {
            Dependency dep = new Dependency();
            dep.setConfigs(Map.of("reference", "someRule"));

            Map<String, String> result = dep.getConfigs();
            assertEquals("someRule", result.get("reference"));
        }

        @Test
        @DisplayName("should handle null configs")
        void handlesNullConfigs() {
            Dependency dep = new Dependency();
            dep.setConfigs(null);
            assertNotNull(dep.getConfigs());
        }

        @Test
        @DisplayName("should handle empty configs")
        void handlesEmptyConfigs() {
            Dependency dep = new Dependency();
            dep.setConfigs(Map.of());
            assertNotNull(dep.getConfigs());
        }

        @Test
        @DisplayName("should clone correctly")
        void clonesCorrectly() {
            Dependency dep = new Dependency();
            dep.setConfigs(Map.of("reference", "testRule"));
            RuleSet ruleSet = new RuleSet();
            dep.setContainingRuleSet(ruleSet);

            IRuleCondition clone = dep.clone();

            assertNotSame(dep, clone);
            assertInstanceOf(Dependency.class, clone);
            assertEquals("testRule", clone.getConfigs().get("reference"));
        }

        @Test
        @DisplayName("should return FAIL when no rules match reference")
        void failsWhenNoRulesMatch() throws Exception {
            Dependency dep = new Dependency();
            dep.setConfigs(Map.of("reference", "nonexistent"));

            // Set up a RuleSet with no matching rules
            RuleSet ruleSet = new RuleSet();
            RuleGroup group = new RuleGroup();
            Rule rule = new Rule("someOtherRule");
            group.getRules().add(rule);
            ruleSet.getRuleGroups().add(group);
            dep.setContainingRuleSet(ruleSet);

            IConversationMemory memory = mock(IConversationMemory.class);

            IRuleCondition.ExecutionState result = dep.execute(memory, new ArrayList<>());
            assertEquals(IRuleCondition.ExecutionState.FAIL, result);
        }
    }
}
