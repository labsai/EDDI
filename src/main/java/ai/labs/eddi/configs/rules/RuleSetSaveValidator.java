/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules;

import ai.labs.eddi.configs.rules.model.RuleConditionConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Save-time checks of the rule <em>graph</em>, which the per-condition
 * validation of {@code RuleDeserialization} cannot see because it looks at one
 * condition at a time:
 * <ul>
 * <li>a {@code dependency} that names no rule of the set — the condition could
 * only ever fail;</li>
 * <li>a dependency cycle — the first turn that reaches it throws
 * {@code InfiniteLoopException}, and when the cycle is reachable from
 * {@code CONVERSATION_START} the agent cannot start a conversation at all;</li>
 * <li>two rules of one group with the same name — rules are compared by name at
 * runtime, so they are one rule as far as the loop guard is concerned.</li>
 * </ul>
 * Rule names are optional. An unnamed rule cannot be referenced (a
 * {@code dependency} needs a non-blank {@code reference}), so it is never a
 * node of the graph and only a reference to a name that no rule carries is
 * refused.
 * <p>
 * Runs on save only. A stored ruleset that violates one of these still loads
 * and deploys exactly as before.
 */
public final class RuleSetSaveValidator {

    private static final String DEPENDENCY = "dependency";
    private static final String KEY_REFERENCE = "reference";

    private RuleSetSaveValidator() {
    }

    /**
     * @throws IllegalArgumentException
     *             naming the rule, with a message that tells the author what to
     *             change
     */
    public static void validate(RuleSetConfiguration ruleSet) {
        if (ruleSet == null || ruleSet.getBehaviorGroups() == null) {
            return;
        }

        Set<String> allNames = new LinkedHashSet<>();
        // rule name -> names it depends on, over every rule that carries the name
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        // name -> the rule that references it, for the error text
        List<String[]> references = new ArrayList<>();

        for (RuleGroupConfiguration group : ruleSet.getBehaviorGroups()) {
            if (group == null || group.getRules() == null) {
                continue;
            }
            Set<String> namesInGroup = new HashSet<>();
            for (RuleConfiguration rule : group.getRules()) {
                if (rule == null) {
                    continue;
                }
                String name = rule.getName();
                if (isBlank(name)) {
                    collectReferences(rule.getConditions(), new ArrayList<>(), "(unnamed rule)", references);
                    continue;
                }
                if (!namesInGroup.add(name)) {
                    throw new IllegalArgumentException("Invalid ruleset: rule name '" + name + "' is used more than once in group '"
                            + (group.getName() == null ? "" : group.getName())
                            + "'. Rules are told apart by name when dependencies are followed, so give each rule of a group its own name.");
                }
                allNames.add(name);
                List<String> referenced = new ArrayList<>();
                collectReferences(rule.getConditions(), referenced, name, references);
                dependencies.computeIfAbsent(name, key -> new LinkedHashSet<>()).addAll(referenced);
            }
        }

        for (String[] reference : references) {
            if (!allNames.contains(reference[1])) {
                throw new IllegalArgumentException("Invalid ruleset: rule '" + reference[0] + "' has a dependency on '" + reference[1]
                        + "', but no rule of this ruleset has that name. The condition could never match; fix the reference or add the rule "
                        + "(rules without a name cannot be referenced).");
            }
        }

        List<String> cycle;
        try {
            cycle = findCycle(dependencies);
        } catch (StackOverflowError e) {
            throw new IllegalArgumentException("Invalid ruleset: the rule dependencies are chained too deeply to be checked; "
                    + "flatten the chain of dependency conditions.");
        }
        if (cycle != null) {
            throw new IllegalArgumentException("Invalid ruleset: the rule dependencies form a cycle (" + String.join(" -> ", cycle)
                    + "). A turn that reaches it fails, so break the cycle by removing one of the dependency conditions.");
        }
    }

    private static void collectReferences(List<RuleConditionConfiguration> conditions, List<String> into, String ownerName, List<String[]> all) {
        if (conditions == null) {
            return;
        }
        for (RuleConditionConfiguration condition : conditions) {
            if (condition == null) {
                continue;
            }
            if (DEPENDENCY.equals(condition.getType()) && condition.getConfigs() != null) {
                String reference = condition.getConfigs().get(KEY_REFERENCE);
                if (!isBlank(reference)) {
                    into.add(reference);
                    all.add(new String[]{ownerName, reference});
                }
            }
            collectReferences(condition.getConditions(), into, ownerName, all);
        }
    }

    /**
     * The first cycle found, as the names along it (first name repeated last), or
     * null.
     */
    private static List<String> findCycle(Map<String, Set<String>> dependencies) {
        Set<String> done = new HashSet<>();
        for (String start : dependencies.keySet()) {
            Deque<String> path = new ArrayDeque<>();
            List<String> cycle = visit(start, dependencies, done, path, new HashSet<>());
            if (cycle != null) {
                return cycle;
            }
        }
        return null;
    }

    private static List<String> visit(String node, Map<String, Set<String>> dependencies, Set<String> done, Deque<String> path,
                                      Set<String> onPath) {
        if (done.contains(node)) {
            return null;
        }
        path.addLast(node);
        onPath.add(node);
        for (String next : dependencies.getOrDefault(node, Set.of())) {
            if (onPath.contains(next)) {
                List<String> cycle = new ArrayList<>();
                boolean inCycle = false;
                for (String element : path) {
                    if (element.equals(next)) {
                        inCycle = true;
                    }
                    if (inCycle) {
                        cycle.add(element);
                    }
                }
                cycle.add(next);
                return cycle;
            }
            List<String> found = visit(next, dependencies, done, path, onPath);
            if (found != null) {
                return found;
            }
        }
        path.removeLast();
        onPath.remove(node);
        done.add(node);
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
