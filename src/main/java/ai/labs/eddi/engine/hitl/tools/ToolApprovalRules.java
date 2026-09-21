/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig.ApprovalRule;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Resolves the per-tool friction rule ({@code toolApprovals.rules}) governing a
 * gated tool-call batch.
 * <p>
 * Rules answer "how carefully is this reviewed?" — timeout policy, timeout,
 * approver-facing reason, end-user pending message. They deliberately cannot
 * answer "is this reviewed at all?": that stays with
 * {@code requireApproval}/{@code exempt} in {@link ToolApprovalGate}, which
 * this class never consults and never overrides.
 *
 * <h2>Per call: most specific wins</h2> A rule is matched against a call using
 * {@link ToolApprovalGate#addressesOf} — the same three addressing forms the
 * gate itself uses, from the same method, so a rule can never address a call
 * differently than the pattern that gated it. Rules are tried most-specific
 * first: fewer wildcards beats more, then the longer pattern beats the shorter.
 * So {@code http.post:/agentstore/agents} takes precedence over
 * {@code http.post:*}, whatever order the designer listed them in.
 *
 * <h2>Per batch: strictest wins</h2> A model can emit several gated calls in
 * one message and they pause <em>together</em>, under one timeout policy — so
 * the calls' rules must be reduced to one. The batch is governed by the matched
 * rule that assumes the least human authority on timeout:
 * <ol>
 * <li>{@code WAIT_INDEFINITELY} — assumes none; the pause outlives any
 * clock</li>
 * <li>{@code ABORT} — decides nothing, but abandons the turn</li>
 * <li>{@code AUTO_REJECT} — decides "no" on the human's behalf</li>
 * <li>{@code AUTO_APPROVE} — decides "yes", executing the write unreviewed</li>
 * </ol>
 * A rule with no {@code timeoutPolicy} expresses no opinion and yields to any
 * rule that does; ties break on specificity. The practical guarantee: mixing a
 * lenient rule into a batch can never soften a stricter one, so
 * {@code http.delete:*} at {@code WAIT_INDEFINITELY} still holds when the model
 * bundles a delete with an auto-approved read-ish write.
 */
public final class ToolApprovalRules {

    private ToolApprovalRules() {
    }

    /**
     * The rule governing each gated call, keyed by call id. Calls with no id or
     * matching no rule are absent; an empty map means "no rules apply, use the
     * scalars".
     */
    public static Map<String, ApprovalRule> matchByCallId(List<ToolExecutionRequest> gated,
                                                          Map<String, String> toolSources,
                                                          Map<String, String> toolEndpoints,
                                                          ToolApprovalsConfig cfg) {
        Map<String, ApprovalRule> result = new LinkedHashMap<>();
        List<CompiledRule> compiled = compile(cfg);
        if (compiled.isEmpty() || gated == null) {
            return result;
        }
        for (ToolExecutionRequest request : gated) {
            if (request.id() == null) {
                continue;
            }
            ApprovalRule rule = firstMatch(compiled, ToolApprovalGate.addressesOf(request.name(), toolSources, toolEndpoints));
            if (rule != null) {
                result.put(request.id(), rule);
            }
        }
        return result;
    }

    /**
     * Reduces the rules matched across a batch to the single one that governs its
     * pause — see the strictness ordering in the class javadoc. Returns null when
     * nothing matched, in which case every field falls back to the
     * {@link ToolApprovalsConfig} scalars.
     */
    public static ApprovalRule governing(Collection<ApprovalRule> matched) {
        if (matched == null || matched.isEmpty()) {
            return null;
        }
        return matched.stream().filter(Objects::nonNull)
                .max(Comparator.comparingInt((ApprovalRule r) -> strictness(r.getTimeoutPolicy()))
                        .thenComparing(Comparator.comparingInt(ToolApprovalRules::specificity)))
                .orElse(null);
    }

    /**
     * Convenience for the common path: match, then reduce. Returns null when the
     * config has no rules or none matched.
     */
    public static ApprovalRule governing(List<ToolExecutionRequest> gated, Map<String, String> toolSources,
                                         Map<String, String> toolEndpoints, ToolApprovalsConfig cfg) {
        return governing(matchByCallId(gated, toolSources, toolEndpoints, cfg).values());
    }

    /**
     * How much of the human's decision the policy takes on timeout, ascending. Only
     * {@code AUTO_APPROVE} can produce an unreviewed side effect, so it must lose
     * every tie-break; {@code WAIT_INDEFINITELY} takes no decision at all, so it
     * wins them. A null policy has no opinion and loses to every stated one.
     */
    private static int strictness(HitlTimeoutPolicy policy) {
        if (policy == null) {
            return 0;
        }
        return switch (policy) {
            case AUTO_APPROVE -> 1;
            case AUTO_REJECT -> 2;
            case ABORT -> 3;
            case WAIT_INDEFINITELY -> 4;
        };
    }

    /**
     * Higher is more specific: a pattern with fewer wildcards beats one with more,
     * and among equals the longer literal wins. Scored so the two criteria cannot
     * trade off against each other — a single {@code *} costs more than any
     * realistic pattern length ({@code ToolApprovalPatterns} caps patterns at 256
     * characters).
     */
    private static int specificity(ApprovalRule rule) {
        String match = rule.getMatch();
        if (match == null) {
            return Integer.MIN_VALUE;
        }
        int wildcards = (int) match.chars().filter(c -> c == '*').count();
        return -wildcards * 1024 + match.length();
    }

    private record CompiledRule(ApprovalRule rule, Pattern pattern) {
    }

    /** Rules compiled and sorted most-specific-first; invalid entries dropped. */
    private static List<CompiledRule> compile(ToolApprovalsConfig cfg) {
        if (cfg == null || cfg.getRules() == null || cfg.getRules().isEmpty()) {
            return List.of();
        }
        List<ApprovalRule> sorted = new ArrayList<>();
        for (ApprovalRule rule : cfg.getRules()) {
            // A blank match is refused at save time (HitlConfigValidation); skipping it
            // here keeps a hand-edited or legacy document from failing a live turn.
            if (rule != null && rule.getMatch() != null && !rule.getMatch().isBlank()) {
                sorted.add(rule);
            }
        }
        sorted.sort(Comparator.comparingInt(ToolApprovalRules::specificity).reversed());
        return sorted.stream().map(r -> new CompiledRule(r, ToolApprovalPatterns.compile(r.getMatch()))).toList();
    }

    private static ApprovalRule firstMatch(List<CompiledRule> compiled, List<String> addresses) {
        for (CompiledRule cr : compiled) {
            for (String address : addresses) {
                if (cr.pattern().matcher(address).matches()) {
                    return cr.rule();
                }
            }
        }
        return null;
    }
}
