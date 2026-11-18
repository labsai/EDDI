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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
    /**
     * The condition types whose {@code setConditions} keeps the nested conditions
     * ({@code Connector}, {@code Negation}).
     */
    private static final Set<String> RETAINS_CHILDREN = Set.of("connector", "negation");

    private RuleSetSaveValidator() {
    }

    /**
     * @throws IllegalArgumentException
     *             with the first problem found, naming the rule, in a message that
     *             tells the author what to change
     */
    public static void validate(RuleSetConfiguration ruleSet) {
        List<String> problems = findProblems(ruleSet);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(problems.getFirst());
        }
    }

    /**
     * Every problem of the rule graph, each as a sentence that names the rule:
     * empty when the ruleset is sound. An import, a sync or a duplicate logs these
     * instead of refusing the document.
     */
    public static List<String> findProblems(RuleSetConfiguration ruleSet) {
        List<String> problems = new ArrayList<>();
        if (ruleSet == null || ruleSet.getBehaviorGroups() == null) {
            return problems;
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
                    problems.add("Invalid ruleset: rule name '" + name + "' is used more than once in group '"
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
                problems.add("Invalid ruleset: rule '" + reference[0] + "' has a dependency on '" + reference[1]
                        + "', but no rule of this ruleset has that name. The condition could never match; fix the reference or add the rule "
                        + "(rules without a name cannot be referenced).");
            }
        }

        for (List<String> cycle : findCycles(dependencies)) {
            problems.add("Invalid ruleset: the rule dependencies form a cycle (" + String.join(" -> ", cycle)
                    + "). A turn that reaches it fails, so break the cycle by removing one of the dependency conditions.");
        }
        return problems;
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
            // The runtime keeps the children of a connector and of a negation only
            // (RuleDeserialization hands them to IRuleCondition.setConditions, which every
            // other condition ignores), so a dependency nested under any other condition,
            // a dependency included, is never followed and must not make a cycle.
            if (RETAINS_CHILDREN.contains(condition.getType())) {
                collectReferences(condition.getConditions(), into, ownerName, all);
            }
        }
    }

    private record Frame(String node, Iterator<String> edges) {
    }

    /**
     * One cycle per cyclic component of the dependency graph, each as the names
     * along it (first name repeated last). Strongly connected components by an
     * iterative Tarjan walk with an explicit work stack, so the depth of a chain of
     * dependencies cannot overflow the call stack.
     */
    private static List<List<String>> findCycles(Map<String, Set<String>> dependencies) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        List<List<String>> cycles = new ArrayList<>();
        int counter = 0;

        for (String root : dependencies.keySet()) {
            if (index.containsKey(root)) {
                continue;
            }
            Deque<Frame> work = new ArrayDeque<>();
            index.put(root, counter);
            low.put(root, counter++);
            stack.push(root);
            onStack.add(root);
            work.push(new Frame(root, dependencies.getOrDefault(root, Set.of()).iterator()));

            while (!work.isEmpty()) {
                Frame frame = work.peek();
                if (frame.edges().hasNext()) {
                    String next = frame.edges().next();
                    if (!index.containsKey(next)) {
                        index.put(next, counter);
                        low.put(next, counter++);
                        stack.push(next);
                        onStack.add(next);
                        work.push(new Frame(next, dependencies.getOrDefault(next, Set.of()).iterator()));
                    } else if (onStack.contains(next)) {
                        low.merge(frame.node(), index.get(next), Math::min);
                    }
                    continue;
                }
                work.pop();
                String node = frame.node();
                if (low.get(node).equals(index.get(node))) {
                    Set<String> component = new LinkedHashSet<>();
                    String member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        component.add(member);
                    } while (!member.equals(node));
                    boolean cyclic = component.size() > 1 || dependencies.getOrDefault(node, Set.of()).contains(node);
                    if (cyclic) {
                        cycles.add(cycleWithin(component, dependencies));
                    }
                }
                if (!work.isEmpty()) {
                    low.merge(work.peek().node(), low.get(node), Math::min);
                }
            }
        }
        return cycles;
    }

    /**
     * A cycle through the members of a cyclic component: from any member, follow
     * edges that stay inside the component until a name repeats.
     */
    private static List<String> cycleWithin(Set<String> component, Map<String, Set<String>> dependencies) {
        List<String> path = new ArrayList<>();
        String current = component.iterator().next();
        while (!path.contains(current)) {
            path.add(current);
            String following = null;
            for (String candidate : dependencies.getOrDefault(current, Set.of())) {
                if (component.contains(candidate)) {
                    following = candidate;
                    break;
                }
            }
            current = following;
        }
        List<String> cycle = new ArrayList<>(path.subList(path.indexOf(current), path.size()));
        cycle.add(current);
        return cycle;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
