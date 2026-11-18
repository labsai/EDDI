/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules;

import ai.labs.eddi.configs.rules.model.RuleConditionConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuleSetSaveValidator")
class RuleSetSaveValidatorTest {

    private static RuleConditionConfiguration dependency(String reference) {
        var condition = new RuleConditionConfiguration();
        condition.setType("dependency");
        condition.setConfigs(reference == null ? Map.of() : Map.of("reference", reference));
        return condition;
    }

    private static RuleConfiguration rule(String name, RuleConditionConfiguration... conditions) {
        var rule = new RuleConfiguration();
        rule.setName(name);
        rule.setActions(List.of("a"));
        rule.setConditions(List.of(conditions));
        return rule;
    }

    private static RuleSetConfiguration set(RuleConfiguration... rules) {
        var group = new RuleGroupConfiguration();
        group.setName("g");
        group.setRules(List.of(rules));
        var set = new RuleSetConfiguration();
        set.setBehaviorGroups(List.of(group));
        return set;
    }

    @Test
    @DisplayName("a dependency cycle is refused and the message names the path")
    void cycle() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> RuleSetSaveValidator.validate(set(rule("A", dependency("B")), rule("B", dependency("A")))));
        assertTrue(e.getMessage().contains("A -> B -> A"), e.getMessage());
    }

    @Test
    @DisplayName("a rule that depends on itself is a cycle")
    void selfCycle() {
        assertThrows(IllegalArgumentException.class, () -> RuleSetSaveValidator.validate(set(rule("A", dependency("A")))));
    }

    @Test
    @DisplayName("a cycle through a nested condition is found")
    void nestedCycle() {
        var negation = new RuleConditionConfiguration();
        negation.setType("negation");
        negation.setConditions(List.of(dependency("B")));
        assertThrows(IllegalArgumentException.class, () -> RuleSetSaveValidator.validate(set(rule("A", negation), rule("B", dependency("A")))));
    }

    @Test
    @DisplayName("a dangling reference is refused")
    void dangling() {
        var e = assertThrows(IllegalArgumentException.class, () -> RuleSetSaveValidator.validate(set(rule("A", dependency("nope")))));
        assertTrue(e.getMessage().contains("'nope'"), e.getMessage());
    }

    @Test
    @DisplayName("a reference to an unnamed rule is dangling")
    void referenceToUnnamedRule() {
        assertThrows(IllegalArgumentException.class, () -> RuleSetSaveValidator.validate(set(rule("A", dependency("x")), rule(""))));
    }

    @Test
    @DisplayName("duplicate names in one group are refused")
    void duplicateNames() {
        assertThrows(IllegalArgumentException.class, () -> RuleSetSaveValidator.validate(set(rule("A"), rule("A"))));
    }

    @Test
    @DisplayName("a chain and a diamond are not cycles; unnamed rules and the same name in two groups are fine")
    void validShapes() {
        var chain = set(rule("A", dependency("B")), rule("B", dependency("C")), rule("C"), rule("D", dependency("B"), dependency("C")), rule(""),
                rule(null));
        assertDoesNotThrow(() -> RuleSetSaveValidator.validate(chain));

        var second = new RuleGroupConfiguration();
        second.setName("h");
        second.setRules(List.of(rule("B")));
        var twoGroups = set(rule("B"));
        twoGroups.setBehaviorGroups(List.of(twoGroups.getBehaviorGroups().getFirst(), second));
        assertDoesNotThrow(() -> RuleSetSaveValidator.validate(twoGroups));
    }

    @Test
    @DisplayName("a missing reference is left to the condition's own validation")
    void missingReferenceIsNotThisChecksBusiness() {
        assertDoesNotThrow(() -> RuleSetSaveValidator.validate(set(rule("A", dependency(null)))));
    }

    @Test
    @DisplayName("null and empty rulesets pass")
    void empty() {
        assertDoesNotThrow(() -> RuleSetSaveValidator.validate(null));
        assertDoesNotThrow(() -> RuleSetSaveValidator.validate(new RuleSetConfiguration()));
    }

    @Test
    @DisplayName("findProblems lists every problem, validate throws the first")
    void findProblemsListsAll() {
        var ruleSet = set(rule("A", dependency("nope")), rule("A"), rule("B", dependency("C")), rule("C", dependency("B")));

        var problems = RuleSetSaveValidator.findProblems(ruleSet);

        assertTrue(problems.size() >= 3, problems.toString());
        var thrown = assertThrows(IllegalArgumentException.class, () -> RuleSetSaveValidator.validate(ruleSet));
        assertTrue(thrown.getMessage().equals(problems.getFirst()));
    }

    @Test
    @DisplayName("a dependency nested under another dependency is dropped by the runtime and makes no cycle")
    void nestedUnderDependencyIsIgnored() {
        var outer = dependency("B");
        outer.setConditions(List.of(dependency("A")));
        assertDoesNotThrow(() -> RuleSetSaveValidator.validate(set(rule("A", outer), rule("B"))));
    }

    @Test
    @DisplayName("every independent cycle is reported")
    void everyCycleIsReported() {
        var problems = RuleSetSaveValidator
                .findProblems(set(rule("A", dependency("B")), rule("B", dependency("A")), rule("C", dependency("D")), rule("D", dependency("C"))));
        assertTrue(problems.stream().anyMatch(p -> p.contains("A -> B -> A")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("C -> D -> C")), problems.toString());
    }

    @Test
    @DisplayName("a very long acyclic chain does not depend on the call stack")
    void longChain() {
        var rules = new RuleConfiguration[20000];
        for (int i = 0; i < rules.length; i++) {
            rules[i] = i + 1 < rules.length ? rule("r" + i, dependency("r" + (i + 1))) : rule("r" + i);
        }
        assertDoesNotThrow(() -> RuleSetSaveValidator.validate(set(rules)));
    }
}
