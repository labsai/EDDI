/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.modules.rules.impl.RuleGroup;
import ai.labs.eddi.modules.rules.impl.RuleSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;

import static org.junit.jupiter.api.Assertions.*;

class DependencyTest {

    @Test
    void id() {
        assertEquals("dependency", new Dependency().getId());
    }

    @Test
    void defaultConfigs_hasReferenceKey() {
        var dep = new Dependency();
        var configs = dep.getConfigs();
        assertTrue(configs.containsKey("reference"));
        assertNull(configs.get("reference"));
    }

    @Test
    void setConfigs_setsReference() {
        var dep = new Dependency();
        dep.setConfigs(Map.of("reference", "greetingRule"));

        assertEquals("greetingRule", dep.getConfigs().get("reference"));
    }

    @Test
    void setConfigs_null_noOp() {
        assertDoesNotThrow(() -> new Dependency().setConfigs(null));
    }

    @Test
    void setConfigs_empty_noOp() {
        assertDoesNotThrow(() -> new Dependency().setConfigs(Collections.emptyMap()));
    }

    @Test
    void clone_preservesReference() {
        var dep = new Dependency();
        dep.setConfigs(Map.of("reference", "testRule"));

        var cloned = dep.clone();
        assertNotSame(dep, cloned);
        assertEquals("dependency", cloned.getId());
        assertEquals("testRule", cloned.getConfigs().get("reference"));
    }

    /**
     * Rule i depends twice on rule i-1: acyclic, so the infinite-loop trace never
     * trips, yet every path to the bottom rule used to be walked.
     */
    @Test
    void sharedDependenciesAreEvaluatedOncePerEvaluation() throws Exception {
        AtomicInteger bottomExecutions = new AtomicInteger();
        var memory = mock(IConversationMemory.class);

        var ruleSet = new RuleSet();
        var group = new RuleGroup();
        var rules = new LinkedList<Rule>();
        var bottom = new Rule("r0");
        bottom.setConditions(List.of(new IRuleCondition() {
            @Override
            public String getId() {
                return "counter";
            }

            @Override
            public Map<String, String> getConfigs() {
                return Map.of();
            }

            @Override
            public void setConfigs(Map<String, String> configs) {
            }

            @Override
            public ExecutionState execute(IConversationMemory m, List<Rule> trace) {
                bottomExecutions.incrementAndGet();
                return ExecutionState.SUCCESS;
            }

            @Override
            public IRuleCondition clone() {
                return this;
            }
        }));
        rules.add(bottom);
        int depth = 40;
        for (int i = 1; i <= depth; i++) {
            var rule = new Rule("r" + i);
            var first = new Dependency();
            first.setConfigs(Map.of("reference", "r" + (i - 1)));
            first.setContainingRuleSet(ruleSet);
            var second = new Dependency();
            second.setConfigs(Map.of("reference", "r" + (i - 1)));
            second.setContainingRuleSet(ruleSet);
            rule.setConditions(new LinkedList<>(List.of(first, second)));
            rules.add(rule);
        }
        group.setRules(rules);
        ruleSet.setRuleGroups(List.of(group));

        var top = rules.getLast();
        IRuleCondition.ExecutionState state = TemplateDataScope.call(memory, () -> top.execute(memory, new ArrayList<>()));

        assertEquals(IRuleCondition.ExecutionState.SUCCESS, state);
        assertEquals(1, bottomExecutions.get());
    }

    /**
     * Rule names need not be unique. A cached rule must not answer for a rule of
     * the same name that is on the active trace, or a rule that depends on its own
     * name passes instead of failing as an infinite loop.
     */
    @Test
    void cachedRuleOfTheSameNameDoesNotHideACycle() {
        var memory = mock(IConversationMemory.class);
        var ruleSet = new RuleSet();
        var group = new RuleGroup();

        var firstX = new Rule("x");
        firstX.setConditions(List.of(succeeding()));
        var a = new Rule("a");
        a.setConditions(new LinkedList<>(List.of(dependencyOn("x", ruleSet))));
        var secondX = new Rule("x");
        secondX.setConditions(new LinkedList<>(List.of(dependencyOn("x", ruleSet))));
        group.setRules(new LinkedList<>(List.of(firstX, a, secondX)));
        ruleSet.setRuleGroups(List.of(group));

        assertThrows(Rule.InfiniteLoopException.class, () -> TemplateDataScope.call(memory, () -> {
            assertEquals(IRuleCondition.ExecutionState.SUCCESS, a.execute(memory, new ArrayList<>()));
            return secondX.execute(memory, new ArrayList<>());
        }));
    }

    /**
     * Rule names are optional, so a ruleset can hold an unnamed rule. Dependency
     * used to call {@code getName().equals(reference)} on every rule of the set, so
     * one unnamed rule anywhere turned every dependency evaluation into a
     * NullPointerException that failed the turn.
     */
    @Test
    void anUnnamedRuleInTheSetDoesNotBreakTheLookup() throws Exception {
        var memory = mock(IConversationMemory.class);
        var ruleSet = new RuleSet();
        var group = new RuleGroup();
        var unnamed = new Rule(null);
        unnamed.setConditions(List.of(succeeding()));
        var b = new Rule("b");
        b.setConditions(List.of(succeeding()));
        group.setRules(new LinkedList<>(List.of(unnamed, b)));
        ruleSet.setRuleGroups(List.of(group));

        var state = TemplateDataScope.call(memory, () -> dependencyOn("b", ruleSet).execute(memory, new ArrayList<>()));

        assertEquals(IRuleCondition.ExecutionState.SUCCESS, state);
    }

    @Test
    void validateConfiguration_missingReference_isRefused() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> new Dependency().validateConfiguration());

        assertTrue(thrown.getMessage().contains("dependency"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("reference"), thrown.getMessage());
    }

    @Test
    void validateConfiguration_blankReference_isRefused() {
        var dependency = new Dependency();
        dependency.setConfigs(Map.of("reference", "  "));

        assertThrows(IllegalArgumentException.class, dependency::validateConfiguration);
    }

    @Test
    void validateConfiguration_withReference_passes() {
        var dependency = new Dependency();
        dependency.setConfigs(Map.of("reference", "greetingRule"));

        assertDoesNotThrow(dependency::validateConfiguration);
    }

    private static Dependency dependencyOn(String reference, RuleSet ruleSet) {
        var dependency = new Dependency();
        dependency.setConfigs(Map.of("reference", reference));
        dependency.setContainingRuleSet(ruleSet);
        return dependency;
    }

    private static IRuleCondition succeeding() {
        return new IRuleCondition() {
            @Override
            public String getId() {
                return "succeeding";
            }

            @Override
            public Map<String, String> getConfigs() {
                return Map.of();
            }

            @Override
            public void setConfigs(Map<String, String> configs) {
            }

            @Override
            public ExecutionState execute(IConversationMemory m, List<Rule> trace) {
                return ExecutionState.SUCCESS;
            }

            @Override
            public IRuleCondition clone() {
                return this;
            }
        };
    }
}
