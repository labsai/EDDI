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
            String source = toolSources.get(request.name());
            String qualified = source != null ? source + ":" + request.name() : null;
            if (firstMatch(exempt, qualified, request.name()) != null) {
                allowed.add(request);
                continue;
            }
            CompiledPattern match = firstMatch(require, qualified, request.name());
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

    private record CompiledPattern(String raw, Pattern pattern) {
    }

    private static List<CompiledPattern> compileAll(List<String> globs) {
        if (globs == null) {
            return List.of();
        }
        return globs.stream().map(g -> new CompiledPattern(g, ToolApprovalPatterns.compile(g))).toList();
    }

    private static CompiledPattern firstMatch(List<CompiledPattern> patterns, String qualified, String bare) {
        for (CompiledPattern cp : patterns) {
            // bare (= request.name()) can be null: langchain4j's ToolExecutionRequest
            // does not guarantee a non-null name (some providers emit malformed tool
            // calls). Guard both matchers so a null name matches nothing and the call
            // flows to `allowed` — the gate stays inert for it and the downstream tool
            // dispatch degrades gracefully to "tool not found", as it did pre-HITL,
            // instead of NPEing and failing the whole turn.
            if ((qualified != null && cp.pattern().matcher(qualified).matches())
                    || (bare != null && cp.pattern().matcher(bare).matches())) {
                return cp;
            }
        }
        return null;
    }
}
