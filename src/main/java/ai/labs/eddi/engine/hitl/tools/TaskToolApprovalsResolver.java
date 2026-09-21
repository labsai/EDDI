/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig.ApprovalRule;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the <em>effective</em> tool-approval gate when an LLM task carries
 * its own {@code toolApprovals} alongside the agent-level
 * {@code hitlConfig.toolApprovals}.
 * <p>
 * Historically a present task-level config <b>fully replaced</b> the agent
 * gate. That made one nested field in an llmstore document a complete bypass:
 * {@code toolApprovals.requireApproval: []} in a task, once saved, turned the
 * gate off for that task entirely — reviewed as an ordinary config edit,
 * effective as a security change. {@code eddi.hitl.tool.task-approvals.mode}
 * now decides:
 * <ul>
 * <li><b>strict</b> (default): the task config can only <em>strengthen</em> the
 * agent gate. Concretely:
 * <ul>
 * <li>{@code requireApproval}: union — the gate pauses when EITHER config
 * demands it. String-level union is semantically exact here, because pattern
 * matching is any-match ({@link ToolApprovalGate} P2). The empty-list bypass is
 * neutralised: {@code [] ∪ agent} = agent.</li>
 * <li>{@code exempt}: the agent's list, verbatim; task-level entries are
 * ignored. Exempt beats requireApproval ({@link ToolApprovalGate} P1), so a
 * task-added exemption is precisely the ungating vector — and a string-level
 * intersection would be semantically wrong (a task exempting a strict
 * <em>subset</em> of the agent's patterns shares no strings with it and would
 * silently gate every read).</li>
 * <li>{@code timeoutPolicy}: the task's, except {@code AUTO_APPROVE}, which is
 * honoured only when the agent-level policy is itself {@code AUTO_APPROVE} and
 * otherwise demoted to {@code WAIT_INDEFINITELY} — the same defensive demotion
 * the runtime already applies to an <em>inherited</em> {@code AUTO_APPROVE}
 * ({@code ConversationHitlService}). Only {@code AUTO_APPROVE} can produce an
 * unreviewed side effect; every other policy is honoured as written.</li>
 * <li>{@code maxAutoApprovalsPerTurn}: the minimum of the two — the
 * auto-approval budget must not grow at task level.</li>
 * <li>{@code rules}: task rules first (each task rule's {@code AUTO_APPROVE}
 * demoted to {@code WAIT_INDEFINITELY}), then the agent's rules unchanged.</li>
 * <li>Everything cosmetic or safe in both directions ({@code pauseReason},
 * {@code pendingMessage}, {@code inGroupTurns}, {@code approvalTimeout},
 * {@code maxPausesPerTurn}, {@code onNoProgress}): the task's when set, else
 * the agent's. ({@code onNoProgress} is safe in both values — neither
 * {@code WAIT_FOR_HUMAN} nor {@code AUTO_REJECT} can execute anything.)</li>
 * </ul>
 * </li>
 * <li><b>replace</b>: the pre-6.3.0 behavior, verbatim — a present task config
 * wins wholesale. The escape hatch for designs that deliberately run one task
 * <em>looser</em> than its agent.</li>
 * </ul>
 * A task with no {@code toolApprovals} inherits the agent config unchanged in
 * both modes, and an absent agent config is treated as an empty gate — the
 * strict result is then "whatever the task adds, minus anything the agent never
 * granted" (no exemptions, no {@code AUTO_APPROVE}).
 *
 * @author ginccc
 * @since 6.3.0
 */
public final class TaskToolApprovalsResolver {

    /** Config property choosing the combination semantics. */
    public static final String MODE_PROPERTY = "eddi.hitl.tool.task-approvals.mode";

    /** How a task-level config combines with the agent-level one. */
    public enum Mode {
        STRICT, REPLACE;

        /** Lenient parse; anything unrecognised is the safe default, STRICT. */
        public static Mode parse(String value) {
            return value != null && value.trim().toLowerCase(Locale.ROOT).equals("replace") ? REPLACE : STRICT;
        }
    }

    private TaskToolApprovalsResolver() {
        // static resolver
    }

    /**
     * Resolve using the deployment-configured mode. The static lookup mirrors the
     * existing {@code ConfigProvider.getConfig()} uses in this codebase — the call
     * sites ({@code LlmTask}, {@code ToolLoopResumer}) are not both CDI beans, so
     * constructor injection has no single home here.
     */
    public static ToolApprovalsConfig resolve(ToolApprovalsConfig agentLevel, ToolApprovalsConfig taskLevel) {
        return resolve(agentLevel, taskLevel, configuredMode());
    }

    /**
     * The deployment's configured mode. Public so a caller that merely wants to
     * <em>describe</em> the semantics — {@code LlmStore}'s save-time warnings — can
     * say what this deployment will actually do, rather than asserting the default.
     * Warning that a setting is ignored on a deployment that honours it is worse
     * than silence: it trains authors to disregard the warning.
     */
    public static Mode configuredMode() {
        return Mode.parse(ConfigProvider.getConfig()
                .getOptionalValue(MODE_PROPERTY, String.class).orElse("strict"));
    }

    /** Pure overload — the whole contract, testable without a config source. */
    public static ToolApprovalsConfig resolve(ToolApprovalsConfig agentLevel, ToolApprovalsConfig taskLevel, Mode mode) {
        // A policy we failed to READ outranks everything, REPLACE mode included. A
        // task-level config is authored inside the very agent whose policy could not
        // be loaded, so it is no evidence that gating is unnecessary — and REPLACE
        // would hand an ungated config straight back, undoing the fail-closed
        // fallback. See ToolApprovalsConfig#UNDETERMINED.
        if (ToolApprovalsConfig.isUndetermined(agentLevel)) {
            return agentLevel;
        }
        if (taskLevel == null) {
            return agentLevel;
        }
        if (mode == Mode.REPLACE) {
            return taskLevel;
        }
        ToolApprovalsConfig agent = agentLevel != null ? agentLevel : new ToolApprovalsConfig();

        var merged = new ToolApprovalsConfig();
        merged.setRequireApproval(unionPreservingOrder(agent.getRequireApproval(), taskLevel.getRequireApproval()));
        merged.setExempt(agent.getExempt() == null ? null : List.copyOf(agent.getExempt()));
        merged.setTimeoutPolicy(strictTimeoutPolicy(agent.getTimeoutPolicy(), taskLevel.getTimeoutPolicy()));
        merged.setApprovalTimeout(firstNonBlank(taskLevel.getApprovalTimeout(), agent.getApprovalTimeout()));
        merged.setMaxPausesPerTurn(taskLevel.getMaxPausesPerTurn() != null
                ? taskLevel.getMaxPausesPerTurn()
                : agent.getMaxPausesPerTurn());
        merged.setMaxAutoApprovalsPerTurn(strictAutoApprovalBudget(agent.getMaxAutoApprovalsPerTurn(), taskLevel.getMaxAutoApprovalsPerTurn()));
        merged.setOnNoProgress(firstNonBlank(taskLevel.getOnNoProgress(), agent.getOnNoProgress()));
        merged.setPauseReason(firstNonBlank(taskLevel.getPauseReason(), agent.getPauseReason()));
        merged.setPendingMessage(firstNonBlank(taskLevel.getPendingMessage(), agent.getPendingMessage()));
        merged.setInGroupTurns(firstNonBlank(taskLevel.getInGroupTurns(), agent.getInGroupTurns()));
        merged.setRules(mergeRules(agent.getRules(), taskLevel.getRules()));
        return merged;
    }

    /**
     * Agent entries first, then task entries not already present. Null when neither
     * side has any — an all-null merge stays "gate inert", identical to an agent
     * that never configured a gate.
     */
    private static List<String> unionPreservingOrder(List<String> agent, List<String> task) {
        if ((agent == null || agent.isEmpty()) && (task == null || task.isEmpty())) {
            return agent != null ? agent : task;
        }
        var union = new LinkedHashSet<String>();
        if (agent != null) {
            union.addAll(agent);
        }
        if (task != null) {
            union.addAll(task);
        }
        return List.copyOf(union);
    }

    /**
     * The task's policy, with the one demotion that matters: a task may not
     * introduce {@code AUTO_APPROVE} the agent did not grant. A null task policy
     * has no opinion and inherits the agent's — including, deliberately, an
     * agent-level {@code AUTO_APPROVE}, which the runtime's own inherited-policy
     * demotion then handles exactly as it does today.
     */
    private static HitlTimeoutPolicy strictTimeoutPolicy(HitlTimeoutPolicy agent, HitlTimeoutPolicy task) {
        if (task == null) {
            return agent;
        }
        if (task == HitlTimeoutPolicy.AUTO_APPROVE && agent != HitlTimeoutPolicy.AUTO_APPROVE) {
            return HitlTimeoutPolicy.WAIT_INDEFINITELY;
        }
        return task;
    }

    /**
     * Task rules first — each with {@code AUTO_APPROVE} demoted, since a rule is
     * exactly where a narrow auto-approval would hide — then the agent's rules
     * unchanged. On an identical match the task's (demoted-safe) rule sorts first
     * at equal specificity, so a task can tighten a specific rule but never loosen
     * one.
     */
    private static List<ApprovalRule> mergeRules(List<ApprovalRule> agentRules, List<ApprovalRule> taskRules) {
        if ((taskRules == null || taskRules.isEmpty())) {
            return agentRules;
        }
        var merged = new ArrayList<ApprovalRule>(taskRules.size() + (agentRules == null ? 0 : agentRules.size()));
        for (ApprovalRule rule : taskRules) {
            if (rule == null) {
                continue;
            }
            if (rule.getTimeoutPolicy() == HitlTimeoutPolicy.AUTO_APPROVE) {
                var demoted = new ApprovalRule();
                demoted.setMatch(rule.getMatch());
                demoted.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
                demoted.setApprovalTimeout(rule.getApprovalTimeout());
                demoted.setPauseReason(rule.getPauseReason());
                demoted.setPendingMessage(rule.getPendingMessage());
                merged.add(demoted);
            } else {
                merged.add(rule);
            }
        }
        if (agentRules != null) {
            merged.addAll(agentRules);
        }
        return List.copyOf(merged);
    }

    /**
     * The auto-approval budget may only shrink. An unset agent value is NOT "no
     * cap" — the runtime resolves it to
     * {@link ToolApprovalsConfig#DEFAULT_MAX_AUTO_APPROVALS_PER_TURN}, so a task
     * stating a larger number against an unset agent value would raise the
     * effective budget. (Today a fixed no-progress threshold downstream happens to
     * bound the damage; this contract must not depend on it.)
     */
    private static Integer strictAutoApprovalBudget(Integer agent, Integer task) {
        if (task == null) {
            return agent;
        }
        int effectiveAgentCap = agent != null ? agent : ToolApprovalsConfig.DEFAULT_MAX_AUTO_APPROVALS_PER_TURN;
        return Math.min(effectiveAgentCap, task);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

}
