/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.configs.groups.model.TeamBudgetState;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Charges a root discussion's spend to its team's monthly budget (F6).
 * <p>
 * <b>Who pays.</b> The root discussion's group. A nested child
 * ({@code depth > 0}) charges nothing: its spend already rolls into the
 * parent's ledger, so charging it as well would pay twice. A handed-off task is
 * therefore paid by the receiving team, whose cadence is the root.
 * <p>
 * <b>What is charged.</b> The increase of {@code gc.getTotalCost()} -- the one
 * sum that already holds member conversations, nested children and the group's
 * own system calls (summaries, stance, judge) -- since the last charge. The
 * ledger records by replacement, so a delta of it cannot double-count the way
 * adding up per-call events could. This is also why the per-charge
 * {@code ISpendSink} feed is not used here: it attributes a nested child to the
 * child's own group and fires only for tenant-accepted charges, while the
 * ceiling this class maintains has to be expressed in the discussion's own
 * {@code totalCost}.
 * <p>
 * Never throws and never fails the discussion: a store outage parks the amount
 * in {@code pendingUsd} and the next call charges it. A crash between the store
 * write and the persist of the discussion can charge one delta again after a
 * resume; the tenant meter has the same bound.
 */
@ApplicationScoped
public class TeamSpendMeter {

    private static final Logger LOGGER = Logger.getLogger(TeamSpendMeter.class);

    static final String METERING = "eddi.team.budget.metering";

    private final TeamBudgetService service;
    private final MeterRegistry meterRegistry;

    /**
     * {@code eddi.groups.team-budget.metering-enabled}: off stops all team
     * accounting.
     */
    @ConfigProperty(name = "eddi.groups.team-budget.metering-enabled", defaultValue = "true")
    boolean meteringEnabled = true;

    @Inject
    public TeamSpendMeter(TeamBudgetService service, MeterRegistry meterRegistry) {
        this.service = service;
        this.meterRegistry = meterRegistry;
        for (String outcome : new String[]{"recorded", "unavailable", "error"}) {
            meterRegistry.counter(METERING, "outcome", outcome);
        }
    }

    /**
     * Start of a leg (a fresh run, a resume, a follow-up): sweeps what the previous
     * leg left uncharged, then sets the team ceiling for this leg.
     */
    public void beginLeg(GroupConversation gc, AgentGroupConfiguration config) {
        try {
            if (!meteringEnabled || gc == null || gc.getDepth() != 0) {
                return;
            }
            TeamBudgetConfig budget = config == null ? null : config.getTeamBudget();
            if (budget == null || budget.monthlyBudgetUsd() == null) {
                // Off when absent: a group with no teamBudget is neither metered nor
                // gated. Dropping a state left by an earlier leg stops its charging.
                gc.setTeamBudgetState(null);
                return;
            }
            TeamBudgetState state = stateOf(gc);
            charge(gc);
            CostPolicy policy = budget.onExhausted() != null
                    ? budget.onExhausted()
                    : config.getProtocol() != null ? config.getProtocol().onCostExceeded() : CostPolicy.SYNTHESIZE_NOW;
            try {
                TeamBudgetSnapshot snapshot = service.snapshot(gc.getGroupId(), config);
                synchronized (state) {
                    state.setPolicy(policy);
                    state.setMonth(snapshot.month());
                    state.setCapUsd(snapshot.capUsd());
                    state.setTenantCeilingUsd(snapshot.tenantRemainingUsd() == null ? null : state.getChargedUsd() + snapshot.tenantRemainingUsd());
                    refreshCeiling(state, snapshot.spentUsd());
                }
            } catch (TeamBudgetAccountingUnavailableException e) {
                // Keep the last-known ceiling. A leg that never had one runs under the
                // protocol alone, but the start gate has already refused a budgeted
                // team it could not read, so this is a resume or a race.
                LOGGER.warnf("Team budget of group %s unreadable at leg start; keeping the last-known ceiling: %s",
                        LogSanitizer.sanitize(gc.getGroupId()), e.getMessage());
                synchronized (state) {
                    state.setPolicy(policy);
                }
            }
        } catch (RuntimeException e) {
            count("error");
            LOGGER.errorf(e, "Setting the team budget ceiling of group conversation %s failed -- the discussion is unaffected",
                    LogSanitizer.sanitize(gc != null ? gc.getId() : null));
        }
    }

    /**
     * End of a leg: the last sweep, which picks up system costs recorded between
     * turns.
     */
    public void endLeg(GroupConversation gc) {
        charge(gc);
    }

    /**
     * Charges the not-yet-charged part of {@code gc.getTotalCost()} (plus anything
     * an outage held back) to the group's team, and refreshes the ceiling from the
     * team total the store returned.
     */
    public void charge(GroupConversation gc) {
        try {
            if (!meteringEnabled || gc == null || gc.getDepth() != 0 || gc.getGroupId() == null) {
                return;
            }
            // Only a group that configures a teamBudget has a state (beginLeg creates it).
            TeamBudgetState state = gc.getTeamBudgetState();
            if (state == null) {
                return;
            }
            double toSend;
            synchronized (state) {
                double delta = gc.getTotalCost() - state.getChargedUsd();
                // !(delta > 0), not delta <= 0: NaN fails every comparison.
                if (!Double.isFinite(delta) || !(delta > 0.0)) {
                    delta = 0.0;
                }
                toSend = delta + state.getPendingUsd();
                if (!(toSend > 0.0)) {
                    return;
                }
                // Claimed under the lock, so a parallel call sees nothing left to send.
                state.setChargedUsd(state.getChargedUsd() + delta);
                state.setPendingUsd(0.0);
            }
            String month = service.currentMonth();
            try {
                double spent = service.store().addSpend(gc.getGroupId(), month, toSend, service.expiresAt(month));
                count("recorded");
                synchronized (state) {
                    state.setMonth(month);
                    refreshCeiling(state, spent);
                }
            } catch (TeamBudgetAccountingUnavailableException e) {
                synchronized (state) {
                    state.setPendingUsd(state.getPendingUsd() + toSend);
                }
                count("unavailable");
                LOGGER.warnf("Could not charge $%.6f of group conversation %s to team %s -- carried to the next charge", toSend,
                        LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(gc.getGroupId()));
            }
        } catch (RuntimeException e) {
            count("error");
            LOGGER.errorf(e, "Metering the team budget of group conversation %s failed -- the discussion is unaffected",
                    LogSanitizer.sanitize(gc != null ? gc.getId() : null));
        }
    }

    /**
     * Anchors the ceiling to the discussion's own ledger: what it has accounted for
     * plus what the team (and tenant) can still absorb. Caller holds the state
     * lock.
     * <p>
     * The ceiling excludes its own boundary ({@link Math#nextDown}). A team that
     * has spent exactly its cap is exhausted -- the admission gate refuses at
     * equality, as the tenant does -- so a run that reaches the cap stops instead
     * of taking one more turn, which the ledger's "a ceiling is a budget to spend"
     * {@code <=} would otherwise allow.
     */
    static void refreshCeiling(TeamBudgetState state, double teamSpentUsd) {
        if (state.getCapUsd() == null) {
            state.setCeilingUsd(null);
            return;
        }
        double remaining = Math.max(0.0, state.getCapUsd() - teamSpentUsd - state.getPendingUsd());
        double teamCeiling = state.getChargedUsd() + remaining;
        Double tenantCeiling = state.getTenantCeilingUsd();
        if (tenantCeiling != null && tenantCeiling < teamCeiling) {
            state.setCeilingUsd(Math.nextDown(Math.max(state.getChargedUsd(), tenantCeiling)));
            state.setLimitedBy("TENANT");
        } else {
            state.setCeilingUsd(Math.nextDown(teamCeiling));
            state.setLimitedBy("TEAM");
        }
    }

    private static TeamBudgetState stateOf(GroupConversation gc) {
        synchronized (gc) {
            if (gc.getTeamBudgetState() == null) {
                gc.setTeamBudgetState(new TeamBudgetState());
            }
            return gc.getTeamBudgetState();
        }
    }

    private void count(String outcome) {
        meterRegistry.counter(METERING, "outcome", outcome).increment();
    }
}
