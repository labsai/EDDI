/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.autonomy.SuspendedGate;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits an LLM tool-call batch into gated (requires human approval) and
 * allowed calls.
 * <p>
 * Precedence: (P1) exempt always beats requireApproval; (P2) any pattern match
 * suffices; (P3) empty/absent requireApproval = gate inactive (unless an
 * earned-autonomy suspension is in force, see {@link SuspendedGate}). Each call
 * is tested against the forms in {@link #addressesOf} —
 * {@code source.method:path}, then {@code source:name}, then the bare dispatch
 * name — fail-safe: a tool with an unknown source still matches bare-name
 * patterns.
 */
public class ToolApprovalGate {

    /** Why a reissue of an already-executed approved call is gated again. */
    public static final String REPEAT_REASON = "repeat of an approved call that has already been executed";

    /**
     * @param mandatoryByCallId
     *            call id to the {@link MandatoryApproval} a
     *            {@link MandatoryApprovalRule} demanded for it; empty when no rule
     *            matched (and always empty for the overloads without rules)
     */
    public record GateResult(List<ToolExecutionRequest> gated,
            List<ToolExecutionRequest> allowed,
            Map<String, String> gateReasonByCallId,
            Map<String, MandatoryApproval> mandatoryByCallId) {

        public GateResult {
            mandatoryByCallId = mandatoryByCallId == null ? Map.of() : Map.copyOf(mandatoryByCallId);
        }

        /** A result no mandatory rule contributed to — the pre-hook shape. */
        public GateResult(List<ToolExecutionRequest> gated, List<ToolExecutionRequest> allowed, Map<String, String> gateReasonByCallId) {
            this(gated, allowed, gateReasonByCallId, Map.of());
        }

        /**
         * The same result with {@code gated} replaced — the shape every screening step
         * that refuses some gated calls rebuilds, keeping the mandatory markers.
         */
        public GateResult withGated(List<ToolExecutionRequest> stillGated) {
            return new GateResult(stillGated, allowed, gateReasonByCallId, mandatoryByCallId);
        }

        /** The mandatory approval of {@code request}, or null. */
        public MandatoryApproval mandatoryOf(ToolExecutionRequest request) {
            return request == null || request.id() == null ? null : mandatoryByCallId.get(request.id());
        }
    }

    public GateResult classify(List<ToolExecutionRequest> batch, Map<String, String> toolSources,
                               ToolApprovalsConfig cfg, ClearedToolCalls cleared) {
        return classify(batch, toolSources, Map.of(), cfg, cleared);
    }

    /**
     * As {@link #classify(List, Map, ToolApprovalsConfig, ClearedToolCalls)},
     * additionally matching patterns against what an http tool actually calls.
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
     * @param cleared
     *            calls a human approved this pause and the resumer has already
     *            executed or replayed. A model that reissues one — same id, tool
     *            name AND arguments — is asking to run it again: the repeat is
     *            gated with {@link #REPEAT_REASON}, never let through, because one
     *            approval authorises one execution
     */
    public GateResult classify(List<ToolExecutionRequest> batch, Map<String, String> toolSources,
                               Map<String, String> toolEndpoints, ToolApprovalsConfig cfg, ClearedToolCalls cleared) {
        return classifyPatterns(batch, toolSources, toolEndpoints, cfg, cleared);
    }

    /**
     * As {@link #classify(List, Map, Map, ToolApprovalsConfig, ClearedToolCalls)},
     * with the mandatory, argument-aware rules of this turn applied first.
     * <p>
     * A call any rule matches is gated <b>before P1-P3 are evaluated</b>:
     * {@code exempt} does not let it through, an empty {@code requireApproval} does
     * not disable the gate for it, and its {@link MandatoryApproval} is carried in
     * {@link GateResult#mandatoryByCallId()}. A call the rules leave alone is
     * classified exactly as without them, so an empty rule list gives a result
     * identical to the overload without rules.
     * <p>
     * A reissue of an approved call that a rule matches is gated as a
     * {@link #REPEAT_REASON repeat} and still carries its mandatory approval, so
     * the repeat needs the same approver as the original. Every call the rules gate
     * must have an id (the tool loop mints ids whenever rules are present); a call
     * without one is gated with no marker rather than allowed.
     */
    public GateResult classify(List<ToolExecutionRequest> batch, Map<String, String> toolSources,
                               Map<String, String> toolEndpoints, ToolApprovalsConfig cfg, ClearedToolCalls cleared,
                               List<MandatoryApprovalRule> mandatoryRules) {
        if (mandatoryRules == null || mandatoryRules.isEmpty()) {
            return classifyPatterns(batch, toolSources, toolEndpoints, cfg, cleared);
        }
        List<ToolExecutionRequest> rest = new ArrayList<>();
        List<ToolExecutionRequest> mandatoryGated = new ArrayList<>();
        Map<String, MandatoryApproval> mandatory = new HashMap<>();
        Map<String, String> mandatoryReasons = new HashMap<>();
        for (ToolExecutionRequest request : batch) {
            MandatoryApproval approval = request.name() == null ? null : MandatoryApprovals.evaluate(mandatoryRules, request);
            if (approval == null) {
                rest.add(request);
                continue;
            }
            mandatoryGated.add(request);
            boolean repeat = cleared != null && cleared.consume(request);
            if (request.id() != null && !request.id().isBlank()) {
                mandatory.put(request.id(), approval);
                mandatoryReasons.put(request.id(), repeat ? REPEAT_REASON : MandatoryApprovals.GATE_REASON_PREFIX + approval.reasonCode());
            }
        }
        if (mandatoryGated.isEmpty()) {
            return classifyPatterns(batch, toolSources, toolEndpoints, cfg, cleared);
        }
        GateResult patterns = rest.isEmpty()
                ? new GateResult(List.of(), List.of(), Map.of())
                : classifyPatterns(rest, toolSources, toolEndpoints, cfg, cleared);
        // Keep the model's order among the gated calls: the pending batch lists them
        // in this order, and an approver reads them as the model proposed them.
        List<ToolExecutionRequest> gated = new ArrayList<>();
        for (ToolExecutionRequest request : batch) {
            if (mandatoryGated.contains(request) || patterns.gated().contains(request)) {
                gated.add(request);
            }
        }
        Map<String, String> reasons = new HashMap<>(patterns.gateReasonByCallId());
        reasons.putAll(mandatoryReasons);
        return new GateResult(gated, patterns.allowed(), reasons, mandatory);
    }

    private GateResult classifyPatterns(List<ToolExecutionRequest> batch, Map<String, String> toolSources,
                                        Map<String, String> toolEndpoints, ToolApprovalsConfig cfg, ClearedToolCalls cleared) {
        if (cfg == null || (cfg.getRequireApproval() == null || cfg.getRequireApproval().isEmpty()) && SuspendedGate.none(cfg)) {
            return new GateResult(List.of(), List.copyOf(batch), Map.of());
        }
        List<CompiledPattern> require = compileAll(cfg.getRequireApproval());
        List<CompiledPattern> exempt = compileAll(cfg.getExempt());

        List<ToolExecutionRequest> gated = new ArrayList<>();
        List<ToolExecutionRequest> allowed = new ArrayList<>();
        Map<String, String> reasons = new HashMap<>();
        for (ToolExecutionRequest request : batch) {
            if (cleared != null && cleared.consume(request)) {
                // This exact call was approved once and has ALREADY been executed (or
                // replayed from the journal) by the resumer. Letting the repeat through
                // ran it a second time on one approval; it goes back to a human instead.
                gated.add(request);
                reasons.put(request.id(), REPEAT_REASON);
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
                if (!gatedAsSuspended(request, addresses, cfg, gated, reasons)) {
                    allowed.add(request);
                }
                continue;
            }
            CompiledPattern match = firstMatch(require, addresses);
            if (match != null) {
                gated.add(request);
                if (request.id() != null && !request.id().isBlank()) {
                    reasons.put(request.id(), match.raw());
                }
            } else if (!gatedAsSuspended(request, addresses, cfg, gated, reasons)) {
                allowed.add(request);
            }
        }
        return new GateResult(gated, allowed, reasons);
    }

    /**
     * Gates a call the config would allow when it matches a suspended
     * earned-autonomy pattern (see {@link SuspendedGate}); a suspension only ever
     * adds a gate.
     */
    private static boolean gatedAsSuspended(ToolExecutionRequest request, List<String> addresses, ToolApprovalsConfig cfg,
                                            List<ToolExecutionRequest> gated, Map<String, String> reasons) {
        String suspended = SuspendedGate.match(cfg, addresses);
        if (suspended == null) {
            return false;
        }
        gated.add(request);
        if (request.id() != null && !request.id().isBlank()) {
            reasons.put(request.id(), SuspendedGate.REASON_PREFIX + suspended);
        }
        OversightMetrics.suspensionGated();
        return true;
    }

    /**
     * The exempt pattern that lets a call through, or null when it is not exempt —
     * built on {@link #addressesOf}, so it asks exactly the question the gate asks.
     * Used by the sampled audit to tell an exempt call from an unmatched one.
     */
    public static String firstExemptMatch(String name, Map<String, String> toolSources, Map<String, String> toolEndpoints,
                                          ToolApprovalsConfig cfg) {
        if (cfg == null || cfg.getExempt() == null || name == null) {
            return null;
        }
        CompiledPattern match = firstMatch(compileAll(cfg.getExempt()),
                addressesOf(name, toolSources != null ? toolSources : Map.of(), toolEndpoints != null ? toolEndpoints : Map.of()));
        return match != null ? match.raw() : null;
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

    private record CompiledPattern(String raw) {
    }

    private static List<CompiledPattern> compileAll(List<String> globs) {
        if (globs == null) {
            return List.of();
        }
        return globs.stream().map(CompiledPattern::new).toList();
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
                if (ToolApprovalPatterns.matches(cp.raw(), address)) {
                    return cp;
                }
            }
        }
        return null;
    }
}
