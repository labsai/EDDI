/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits an LLM tool-call batch into gated (requires human approval) and
 * allowed calls.
 * <p>
 * Precedence: (P1) exempt always beats requireApproval; (P2) any pattern match
 * suffices; (P3) empty/absent requireApproval = gate fully inactive. Patterns
 * are tested against {@code "source:name"} first, then the bare dispatch name —
 * fail-safe: a tool with unknown source still matches bare-name patterns.
 */
public class ToolApprovalGate {

    public record GateResult(List<ToolExecutionRequest> gated,
            List<ToolExecutionRequest> allowed,
            Map<String, String> gateReasonByCallId) {
    }

    public GateResult classify(List<ToolExecutionRequest> batch, Map<String, String> toolSources,
                               ToolApprovalsConfig cfg, Set<String> clearedCallIds) {
        return classify(batch, toolSources, Map.of(), cfg, clearedCallIds);
    }

    /**
     * As {@link #classify(List, Map, ToolApprovalsConfig, Set)}, additionally
     * matching patterns against what an http tool actually calls.
     * <p>
     * A pattern may address a tool three ways: its bare name, {@code source:name},
     * or — for httpcall tools — {@code source.method:path}, e.g.
     * {@code http.post:/agentstore/agents}. The third form is the useful one for a
     * generated API client: names come from {@code operationId} or a slug and drift
     * when the spec changes, whereas {@code http.post:*} gates every mutation
     * whether or not anyone maintained a list, and
     * {@code http.post:/agentstore/agents} says exactly which one needs more care.
     *
     * @param toolEndpoints
     *            tool name to {@code method:path}; empty for non-http tools
     */
    public GateResult classify(List<ToolExecutionRequest> batch, Map<String, String> toolSources,
                               Map<String, String> toolEndpoints, ToolApprovalsConfig cfg, Set<String> clearedCallIds) {
        if (cfg == null || cfg.getRequireApproval() == null || cfg.getRequireApproval().isEmpty()) {
            return new GateResult(List.of(), List.copyOf(batch), Map.of());
        }
        List<CompiledPattern> require = compileAll(cfg.getRequireApproval());
        List<CompiledPattern> exempt = compileAll(cfg.getExempt());

        List<ToolExecutionRequest> gated = new ArrayList<>();
        List<ToolExecutionRequest> allowed = new ArrayList<>();
        Map<String, String> reasons = new HashMap<>();
        for (ToolExecutionRequest request : batch) {
            if (request.id() != null && clearedCallIds.contains(request.id())) {
                allowed.add(request); // already approved by a human — never re-gate
                continue;
            }
            if (request.name() == null) {
                // Malformed tool call with no name (some providers emit these): it can
                // match no pattern and would NPE at toolSources.get(null) on an
                // immutable map. Let it through to `allowed` so the downstream dispatch
                // degrades gracefully to "tool not found", as it did pre-HITL, instead
                // of failing the whole turn.
                allowed.add(request);
                continue;
            }
            List<String> addresses = addressesOf(request.name(), toolSources, toolEndpoints);
            if (firstMatch(exempt, addresses) != null) {
                allowed.add(request);
                continue;
            }
            CompiledPattern match = firstMatch(require, addresses);
            if (match != null) {
                gated.add(request);
                if (request.id() != null) {
                    reasons.put(request.id(), match.raw());
                }
            } else {
                allowed.add(request);
            }
        }
        return new GateResult(gated, allowed, reasons);
    }

    /**
     * The forms a pattern may address one tool call by, in the order they are
     * tried: {@code source.method:path} (httpcall tools only), {@code source:name},
     * and the bare {@code name}.
     * <p>
     * Extracted and public so that <em>everything</em> matching a configured
     * pattern against a tool call — the gate itself and {@link ToolApprovalRules},
     * which picks the per-tool friction rule — asks the identical question. A
     * second, independent copy of this derivation would drift silently, and since
     * the gate allows an unmatched call, drift here is an ungated write.
     * <p>
     * Entries that cannot be derived are omitted, so the result is empty for a null
     * name (a malformed provider tool call) and matches nothing.
     */
    public static List<String> addressesOf(String name, Map<String, String> toolSources,
                                           Map<String, String> toolEndpoints) {
        if (name == null) {
            return List.of();
        }
        String source = toolSources.get(name);
        // e.g. "http" + "." + "post:/agentstore/agents"
        String endpoint = toolEndpoints.get(name);
        List<String> addresses = new ArrayList<>(3);
        if (source != null && endpoint != null) {
            addresses.add(source + "." + endpoint);
        }
        if (source != null) {
            addresses.add(source + ":" + name);
        }
        addresses.add(name);
        return addresses;
    }

    private record CompiledPattern(String raw, Pattern pattern) {
    }

    private static List<CompiledPattern> compileAll(List<String> globs) {
        if (globs == null) {
            return List.of();
        }
        return globs.stream().map(g -> new CompiledPattern(g, ToolApprovalPatterns.compile(g))).toList();
    }

    /**
     * Pattern order dominates address order: the first configured pattern matching
     * <em>any</em> address wins.
     * <p>
     * {@code addresses} is empty for a null tool name (langchain4j's
     * {@code ToolExecutionRequest} does not guarantee one — some providers emit
     * malformed tool calls), so such a call matches nothing and flows to
     * {@code allowed}; the gate stays inert for it and the downstream dispatch
     * degrades gracefully to "tool not found", as it did pre-HITL, instead of
     * failing the whole turn.
     */
    private static CompiledPattern firstMatch(List<CompiledPattern> patterns, List<String> addresses) {
        for (CompiledPattern cp : patterns) {
            for (String address : addresses) {
                if (cp.pattern().matcher(address).matches()) {
                    return cp;
                }
            }
        }
        return null;
    }
}
