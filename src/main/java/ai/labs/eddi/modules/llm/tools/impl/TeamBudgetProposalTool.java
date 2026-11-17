/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.groups.budget.TeamBudgetAccountingUnavailableException;
import ai.labs.eddi.engine.groups.budget.TeamBudgetMonth;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.groups.budget.TeamBudgetSnapshot;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.modules.llm.model.AgentOpsConfig;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * {@code propose_team_budget_raise} and {@code propose_team_budget_transfer}:
 * an agent's request to move team budget, applied <b>only when a human approves
 * it</b>.
 * <p>
 * <b>The approval is the existing tool-call approval, not a second
 * mechanism.</b> The provider assembles these tools only when the agent's
 * effective {@code toolApprovals} gates both of them, so a call pauses the
 * conversation with the usual approval card (Manager, Slack, e-mail, MCP)
 * before this code runs. The method body therefore runs after approval — and it
 * re-checks that the caller is an {@code eddi-admin}, because a team-budget
 * move is admin-only everywhere else (REST, MCP) and an approver who is merely
 * the conversation's owner or an {@code eddi-approver} must not widen that. A
 * timeout auto-approval, or an earned-autonomy exemption, carries no admin
 * caller and is refused the same way. Nothing here ever applies a change on the
 * model's say-so.
 * <p>
 * The change goes through {@link TeamBudgetService} exactly as the REST and MCP
 * admin operations do: the same floor (not below spend), the same audit-ledger
 * entry, the same adjustment history — with the approving admin as the actor
 * and the proposing agent in the reason.
 * <p>
 * Caps: calls per turn, an amount ceiling, and proposals applied per agent per
 * rolling day (counted from the budget's own adjustment history, so a restart
 * does not reset it). Idempotent: a raise names the cap it wants, so a replay
 * finds the cap already in place and is refused ("must be above the current
 * cap") instead of raising it twice, and a transfer's idempotency key is
 * derived from the agent, conversation, teams, amount, reason and month, so a
 * replay moves nothing twice.
 *
 * @since 6.7.0
 */
@Vetoed
public class TeamBudgetProposalTool {

    private static final Logger LOGGER = Logger.getLogger(TeamBudgetProposalTool.class);

    public static final String RAISE_TOOL = "propose_team_budget_raise";
    public static final String TRANSFER_TOOL = "propose_team_budget_transfer";
    /** The glob an agent designer puts in {@code toolApprovals.requireApproval}. */
    public static final String GATE_PATTERN = "propose_team_budget_*";

    /** Marker the applied reason carries; the daily cap counts it. */
    static final String REASON_MARKER = "[agent:";
    static final int MAX_AGENT_REASON_CHARS = 600;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentOpsScope scope;
    private final AgentOpsConfig.BudgetProposal limits;
    private final TeamBudgetService budgetService;
    private final Supplier<CallerIdentity> caller;
    private final String agentId;
    private final String conversationId;
    private final Clock clock;
    private final AtomicInteger calls = new AtomicInteger();

    public TeamBudgetProposalTool(AgentOpsScope scope, AgentOpsConfig.BudgetProposal limits, TeamBudgetService budgetService,
            Supplier<CallerIdentity> caller, String agentId, String conversationId, Clock clock) {
        this.scope = scope;
        this.limits = limits != null ? limits : new AgentOpsConfig.BudgetProposal();
        this.budgetService = budgetService;
        this.caller = caller;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.clock = clock;
    }

    @Tool(name = RAISE_TOOL, value = "Propose raising a team's budget cap for the current month to a new total. A human administrator "
            + "must approve the call before anything changes; say in the reason what the team needs the money for and what the "
            + "scorecard shows. Read the scorecard first: the new cap must be above the current one.")
    public String proposeRaise(@P("Group id of the team whose cap should rise") String groupId,
                               @P("The new monthly cap in USD for the current month, above the current cap") double newCapUsd,
                               @P("Why: what the team needs it for, with the numbers behind it") String reason) {
        String refusal = admit(reason);
        if (refusal != null) {
            return refusal;
        }
        if (groupId == null || !scope.contains(groupId.strip())) {
            return refused("That team is not one of yours, or you may not view it. Use read_team_scorecard to see the teams you can name.");
        }
        String group = groupId.strip();
        try {
            TeamBudgetSnapshot before = budgetService.snapshot(group);
            if (before.capUsd() == null) {
                return refused("That team has no team budget, so there is no cap to raise.");
            }
            if (!(newCapUsd > before.capUsd()) || !Double.isFinite(newCapUsd)) {
                return refused(String.format(Locale.ROOT, "The new cap must be above the current cap of $%.2f.", before.capUsd()));
            }
            double increase = newCapUsd - before.capUsd();
            if (increase > limits.effectiveMaxAmountUsd()) {
                return refused(String.format(Locale.ROOT, "A proposal may add at most $%.2f to a cap; this one adds $%.2f. Propose a smaller step.",
                        limits.effectiveMaxAmountUsd(), increase));
            }
            String dayCap = dailyCapRefusal(List.of(group));
            if (dayCap != null) {
                return dayCap;
            }
            String actor = approverId();
            TeamBudgetSnapshot after = budgetService.setCurrentMonthCap(group, newCapUsd, appliedReason(reason), actor);
            LOGGER.infof("Agent %s's budget raise for group %s applied, approved by %s", LogSanitizer.sanitize(agentId), LogSanitizer.sanitize(group),
                    LogSanitizer.sanitize(actor));
            return json(
                    Map.of("applied", true, "kind", "RAISE", "month", after.month(), "capBeforeUsd", before.capUsd(), "capAfterUsd", after.capUsd(),
                            "remainingUsd", after.remainingUsd()));
        } catch (IllegalArgumentException e) {
            return refused(e.getMessage());
        } catch (TeamBudgetAccountingUnavailableException e) {
            return refused("The budget cannot be read right now, so nothing was changed. Try again later.");
        }
    }

    @Tool(name = TRANSFER_TOOL, value = "Propose moving part of this month's cap from one team to another (for example from a team that "
            + "will not use its budget to one that has run out). A human administrator must approve the call before anything changes. "
            + "Both teams must be teams you can read; the source keeps at least what it has already spent.")
    public String proposeTransfer(@P("Group id of the team giving up budget") String fromGroupId,
                                  @P("Group id of the team receiving it") String toGroupId,
                                  @P("Amount in USD to move") double amountUsd,
                                  @P("Why: what the receiving team needs it for and why the source can spare it") String reason) {
        String refusal = admit(reason);
        if (refusal != null) {
            return refusal;
        }
        if (fromGroupId == null || toGroupId == null || !scope.contains(fromGroupId.strip()) || !scope.contains(toGroupId.strip())) {
            return refused("Both teams must be teams you can read. Use read_team_scorecard to see the teams you can name.");
        }
        String from = fromGroupId.strip();
        String to = toGroupId.strip();
        if (from.equals(to)) {
            return refused("A transfer needs two different teams.");
        }
        if (!Double.isFinite(amountUsd) || !(amountUsd > 0.0)) {
            return refused("The amount must be a positive number of dollars.");
        }
        if (amountUsd > limits.effectiveMaxAmountUsd()) {
            return refused(String.format(Locale.ROOT, "A proposal may move at most $%.2f; this one moves $%.2f. Propose a smaller step.",
                    limits.effectiveMaxAmountUsd(), amountUsd));
        }
        try {
            String dayCap = dailyCapRefusal(List.of(from, to));
            if (dayCap != null) {
                return dayCap;
            }
            String actor = approverId();
            String key = transferKey(from, to, amountUsd, reason);
            TeamBudgetService.TransferResult result = budgetService.transfer(from, to, amountUsd, appliedReason(reason), actor, key);
            LOGGER.infof("Agent %s's budget transfer %s -> %s applied, approved by %s", LogSanitizer.sanitize(agentId), LogSanitizer.sanitize(from),
                    LogSanitizer.sanitize(to), LogSanitizer.sanitize(actor));
            return json(Map.of("applied", true, "kind", "TRANSFER", "transferId", result.transferId(), "fromCapUsd", result.from().capUsd(),
                    "toCapUsd", result.to().capUsd()));
        } catch (IllegalArgumentException e) {
            return refused(e.getMessage());
        } catch (TeamBudgetAccountingUnavailableException e) {
            return refused("The budget cannot be read right now, so nothing was changed. Try again later.");
        } catch (RuntimeException e) {
            // A failed credit: the service reversed or recorded the debit.
            LOGGER.warnf("Agent %s's budget transfer failed: %s", LogSanitizer.sanitize(agentId), LogSanitizer.sanitize(e.getMessage()));
            return refused("The transfer failed and was not applied. An administrator can see the audit ledger entry.");
        }
    }

    /** Turn cap, reason check; null when the call may proceed. */
    private String admit(String reason) {
        if (calls.incrementAndGet() > limits.effectiveMaxPerTurn()) {
            return refused("You have already made " + limits.effectiveMaxPerTurn() + " budget proposal(s) this turn. Wait for the next turn.");
        }
        // Before anything is read: a call nobody approved learns nothing about the
        // budgets.
        if (approverId() == null) {
            return refused("Not applied: moving team budget needs the approval of an eddi-admin, and this call was not approved by one. "
                    + "Ask an administrator to approve it, or to make the change from the Manager's Quotas page.");
        }
        if (reason == null || reason.isBlank()) {
            return refused("A reason is required: say what the team needs the money for.");
        }
        return null;
    }

    /**
     * The identity that approved this call, which must be an administrator.
     * Resolved when the approved call runs (the resumer binds the approver for
     * exactly that call), so a call nobody approved has no such caller.
     */
    private String approverId() {
        CallerIdentity identity = caller.get();
        if (identity == null || !identity.admin() || identity.userId() == null || identity.userId().isBlank()) {
            return null;
        }
        return identity.userId();
    }

    /**
     * Refuses once this agent already has {@code maxPerDay} applied proposals in
     * the last 24 hours on any of the groups involved. Reads the budget's own
     * adjustment history — the only record of what was applied — over this and the
     * previous month so the window survives a month boundary.
     */
    private String dailyCapRefusal(List<String> groupIds) {
        Instant since = clock.instant().minus(Duration.ofHours(24));
        String marker = REASON_MARKER + agentId + "]";
        int worst = 0;
        for (String groupId : groupIds) {
            int count = 0;
            for (TeamBudgetMonth month : budgetService.history(groupId, 2)) {
                for (TeamBudgetMonth.Adjustment adjustment : month.adjustments()) {
                    boolean ours = adjustment.reason() != null && adjustment.reason().startsWith(marker);
                    boolean out = !"TRANSFER_IN".equals(adjustment.kind()) && !"TRANSFER_OUT_REVERSED".equals(adjustment.kind());
                    if (ours && out && adjustment.at() != null && !adjustment.at().isBefore(since)) {
                        count++;
                    }
                }
            }
            worst = Math.max(worst, count);
        }
        if (worst >= limits.effectiveMaxPerDay()) {
            return refused("This agent has already had " + worst + " budget change(s) applied in the last 24 hours; the limit is "
                    + limits.effectiveMaxPerDay() + ". Try again tomorrow, or ask an administrator.");
        }
        return null;
    }

    private String appliedReason(String reason) {
        String text = reason.strip();
        if (text.length() > MAX_AGENT_REASON_CHARS) {
            text = text.substring(0, MAX_AGENT_REASON_CHARS) + "…";
        }
        return REASON_MARKER + agentId + "] " + text;
    }

    /**
     * Deterministic per proposal and month: the same agent asking in the same
     * conversation for the same move, for the same reason, is one transfer however
     * many times the call is replayed.
     */
    String transferKey(String from, String to, double amountUsd, String reason) {
        String material = String.join("|", String.valueOf(agentId), String.valueOf(conversationId), from, to,
                String.format(Locale.ROOT, "%.6f", amountUsd), reason.strip(), budgetService.currentMonth());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "agentops-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String refused(String message) {
        return json(Map.of("applied", false, "message", message));
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(new LinkedHashMap<>((Map<?, ?>) value));
        } catch (Exception e) {
            return "{\"applied\":false,\"message\":\"The result could not be rendered.\"}";
        }
    }
}
