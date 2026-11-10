/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.tenancy.QuotaAccountingUnavailableException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * The facade every team-budget caller uses (F6): the month's snapshot, the
 * admission decision for a new discussion, and the admin operations that move a
 * cap.
 * <p>
 * The month is the UTC calendar month, like the tenant's. The effective cap is
 * {@code base (current group config) + adjustment (this month)}; a mid-month
 * edit of the base therefore survives a transfer, and a new month starts at the
 * base again.
 * <p>
 * <b>Fail-closed.</b> A budget nobody can read is not a budget with room: the
 * admission decision refuses when the store or the tenant counter is
 * unreadable. Metering ({@link TeamSpendMeter}) is the opposite and never fails
 * the work.
 */
@ApplicationScoped
public class TeamBudgetService {

    private static final Logger LOGGER = Logger.getLogger(TeamBudgetService.class);

    /** The limit on a free-text reason. */
    public static final int MAX_REASON_LENGTH = 1000;

    /** Hex characters of the request fingerprint in a transfer step id. */
    private static final int FINGERPRINT_LENGTH = 16;

    /** Where an admission was asked; a metric tag. */
    public enum Surface {
        DISCUSSION, CADENCE, FOLLOWUP,
        /** A cross-team handoff into a team that cannot pay for it. */
        HANDOFF
    }

    /**
     * The admission outcome. {@code allowed} false carries a {@code reason} of
     * {@code exhausted}, {@code tenant} or {@code unavailable}.
     */
    public record Decision(boolean allowed, String reason, String message, TeamBudgetSnapshot snapshot) {
        static Decision ok(TeamBudgetSnapshot snapshot) {
            return new Decision(true, null, null, snapshot);
        }
    }

    /** What a transfer did. */
    public record TransferResult(TeamBudgetSnapshot from, TeamBudgetSnapshot to, String transferId) {
    }

    private final ITeamBudgetStore store;
    private final IAgentGroupStore groupStore;
    private final TenantQuotaService tenantQuotaService;
    private final MeterRegistry meterRegistry;
    private final TeamBudgetAudit audit;
    private final Clock clock;

    @ConfigProperty(name = "eddi.groups.team-budget.retention-months", defaultValue = "13")
    int retentionMonths = 13;

    /**
     * {@code eddi.groups.team-budget.metering-enabled}: the single kill switch.
     * Off, the admission gate admits everything as well, so a group with a cap is
     * neither metered nor refused.
     */
    @ConfigProperty(name = "eddi.groups.team-budget.metering-enabled", defaultValue = "true")
    boolean meteringEnabled = true;

    @Inject
    public TeamBudgetService(ITeamBudgetStore store, IAgentGroupStore groupStore, TenantQuotaService tenantQuotaService,
            MeterRegistry meterRegistry, TeamBudgetAudit audit) {
        this(store, groupStore, tenantQuotaService, meterRegistry, audit, Clock.systemUTC());
    }

    /** With an explicit clock, for tests. */
    public TeamBudgetService(ITeamBudgetStore store, IAgentGroupStore groupStore, TenantQuotaService tenantQuotaService, MeterRegistry meterRegistry,
            TeamBudgetAudit audit, Clock clock) {
        this.store = store;
        this.groupStore = groupStore;
        this.tenantQuotaService = tenantQuotaService;
        this.meterRegistry = meterRegistry;
        this.audit = audit;
        this.clock = clock;
        for (Surface surface : Surface.values()) {
            for (String reason : new String[]{"exhausted", "tenant", "unavailable"}) {
                meterRegistry.counter("eddi.team.budget.refusals", "surface", surface.name().toLowerCase(Locale.ROOT), "reason", reason);
            }
        }
        for (String kind : new String[]{"set", "transfer"}) {
            meterRegistry.counter("eddi.team.budget.adjustments", "kind", kind);
        }
    }

    public ITeamBudgetStore store() {
        return store;
    }

    /** The current UTC month, {@code YYYY-MM}. */
    public String currentMonth() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
    }

    Instant expiresAt(String month) {
        return YearMonth.parse(month).plusMonths(1L + Math.max(1, retentionMonths)).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    Instant resetsAt(String month) {
        return YearMonth.parse(month).plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * The group's stored configuration, or {@code null} when it cannot be read.
     */
    public AgentGroupConfiguration config(String groupId) {
        try {
            IResourceStore.IResourceId id = groupStore.getCurrentResourceId(groupId);
            return id == null ? null : groupStore.read(groupId, id.getVersion());
        } catch (IResourceStore.ResourceNotFoundException e) {
            return null;
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.warnf("Could not read group %s for its team budget: %s", LogSanitizer.sanitize(groupId), e.getMessage());
            return null;
        }
    }

    /**
     * As {@link #config}, but a read that FAILED is an exception, not {@code null}:
     * {@code null} here means the group does not exist. Callers that answer "not
     * found" use this one, so a store outage is never reported as a missing group.
     *
     * @throws TeamBudgetAccountingUnavailableException
     *             when the group store cannot answer
     */
    public AgentGroupConfiguration readConfig(String groupId) {
        try {
            IResourceStore.IResourceId id = groupStore.getCurrentResourceId(groupId);
            return id == null ? null : groupStore.read(groupId, id.getVersion());
        } catch (IResourceStore.ResourceNotFoundException e) {
            return null;
        } catch (IResourceStore.ResourceStoreException | RuntimeException e) {
            throw new TeamBudgetAccountingUnavailableException("Group configuration unreadable", e);
        }
    }

    /**
     * The group's budget this month. Works for a group without a {@code teamBudget}
     * too (spend only, no cap).
     *
     * @throws TeamBudgetAccountingUnavailableException
     *             when the store or the tenant counter cannot answer
     */
    public TeamBudgetSnapshot snapshot(String groupId) {
        return snapshot(groupId, readConfig(groupId));
    }

    public TeamBudgetSnapshot snapshot(String groupId, AgentGroupConfiguration config) {
        String month = currentMonth();
        TeamBudgetMonth stored = store.read(groupId, month).orElse(null);
        return snapshot(groupId, config, month, stored);
    }

    /** Builds a snapshot from an already-read month, for batch listings. */
    public TeamBudgetSnapshot snapshot(String groupId, AgentGroupConfiguration config, String month, TeamBudgetMonth stored) {
        double spent = stored == null ? 0.0 : stored.spentUsd();
        double adjustment = stored == null ? 0.0 : stored.adjustmentUsd();
        TeamBudgetConfig budget = config == null ? null : config.getTeamBudget();
        Double base = budget == null ? null : budget.monthlyBudgetUsd();
        Double cap = base == null ? null : base + adjustment;
        Double remaining = cap == null ? null : Math.max(0.0, cap - spent);
        boolean exhausted = cap != null && spent >= cap;
        Double tenantRemaining = tenantRemaining();
        String limitedBy = TeamBudgetSnapshot.LIMIT_NONE;
        if (remaining != null && (tenantRemaining == null || remaining <= tenantRemaining)) {
            limitedBy = TeamBudgetSnapshot.LIMIT_TEAM;
        } else if (tenantRemaining != null) {
            limitedBy = TeamBudgetSnapshot.LIMIT_TENANT;
        }
        return new TeamBudgetSnapshot(groupId, config == null ? null : config.getName(), month, base, adjustment, cap, spent, remaining,
                exhausted, resetsAt(month), tenantRemaining, limitedBy);
    }

    /**
     * What is left of the tenant's monthly budget, or null when none is in force.
     */
    public Double tenantRemaining() {
        if (tenantQuotaService == null) {
            return null;
        }
        try {
            OptionalDouble remaining = tenantQuotaService.remainingCostUsd(tenantQuotaService.getDefaultTenantId());
            return remaining.isPresent() ? remaining.getAsDouble() : null;
        } catch (QuotaAccountingUnavailableException e) {
            throw new TeamBudgetAccountingUnavailableException("Tenant quota accounting unavailable", e);
        }
    }

    /**
     * Whether a new discussion for {@code groupId} may start. A group without a
     * {@code teamBudget} is admitted without any store read.
     */
    public Decision decide(String groupId) {
        if (!meteringEnabled) {
            return Decision.ok(null);
        }
        AgentGroupConfiguration config;
        try {
            config = readConfig(groupId);
        } catch (TeamBudgetAccountingUnavailableException e) {
            // A group that does not exist, or has no teamBudget, is admitted; a read that
            // FAILED
            // cannot tell which it is, and a team with a budget must not slip past it.
            LOGGER.warnf("Group %s could not be read for its team budget -- refusing (fail closed): %s", LogSanitizer.sanitize(groupId),
                    e.getMessage());
            return new Decision(false, "unavailable", "The budget of team '" + groupId
                    + "' could not be read, so no new work is started for it. Retry shortly; an operator should check the database.", null);
        }
        if (config == null || config.getTeamBudget() == null) {
            return Decision.ok(null);
        }
        TeamBudgetSnapshot snapshot;
        try {
            snapshot = snapshot(groupId, config);
        } catch (TeamBudgetAccountingUnavailableException e) {
            LOGGER.warnf("Team budget of group %s is unreadable -- refusing (fail closed): %s", LogSanitizer.sanitize(groupId), e.getMessage());
            return new Decision(false, "unavailable", "The budget of team '" + displayName(config, groupId)
                    + "' could not be read, so no new work is started for it. Retry shortly; an operator should check the database.", null);
        }
        if (snapshot.exhausted()) {
            return new Decision(false, "exhausted", String.format(Locale.ROOT,
                    "Team '%s' has spent $%.2f of its $%.2f budget for %s; it resets %s. An eddi-admin can raise this month's cap "
                            + "(PUT /groupstore/groups/%s/budget/current-month) or transfer budget from another team.",
                    displayName(config, groupId), snapshot.spentUsd(), snapshot.capUsd(), snapshot.month(), snapshot.resetsAt(),
                    groupId), snapshot);
        }
        if (snapshot.tenantRemainingUsd() != null && snapshot.tenantRemainingUsd() <= 0.0) {
            return new Decision(false, "tenant", String.format(Locale.ROOT,
                    "The tenant's monthly cost budget is spent, so team '%s' ($%.2f of $%.2f used for %s) cannot start new work; it resets %s.",
                    displayName(config, groupId), snapshot.spentUsd(), snapshot.capUsd(), snapshot.month(), snapshot.resetsAt()), snapshot);
        }
        return Decision.ok(snapshot);
    }

    /**
     * {@link #decide} that counts a refusal and throws it.
     */
    public void admit(String groupId, Surface surface) throws TeamBudgetRefusedException {
        Decision decision = decide(groupId);
        if (!decision.allowed()) {
            countRefusal(surface, decision.reason());
            throw new TeamBudgetRefusedException(decision.message(), decision.reason(), decision.snapshot());
        }
    }

    public void countRefusal(Surface surface, String reason) {
        meterRegistry.counter("eddi.team.budget.refusals", "surface", surface.name().toLowerCase(Locale.ROOT), "reason", reason).increment();
    }

    private static String displayName(AgentGroupConfiguration config, String groupId) {
        return config != null && config.getName() != null && !config.getName().isBlank() ? config.getName() : groupId;
    }

    // ---- admin operations ----

    /**
     * Sets this month's cap to {@code capUsd} by adjusting the delta over the base.
     *
     * @throws IllegalArgumentException
     *             for a group without a budget, a missing reason, or a cap below
     *             what is already spent
     * @throws TeamBudgetAccountingUnavailableException
     *             when the store cannot answer
     */
    public TeamBudgetSnapshot setCurrentMonthCap(String groupId, double capUsd, String reason, String actor) {
        requireReason(reason);
        if (!Double.isFinite(capUsd) || !(capUsd > 0.0)) {
            throw new IllegalArgumentException("capUsd must be a positive, finite number");
        }
        AgentGroupConfiguration config = requireBudgeted(groupId);
        String month = currentMonth();
        TeamBudgetSnapshot before = snapshot(groupId, config, month, store.read(groupId, month).orElse(null));
        double delta = capUsd - before.capUsd();
        if (delta == 0.0) {
            return before;
        }
        if (capUsd < before.spentUsd()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "The cap cannot be set below what has already been spent this month ($%.2f).", before.spentUsd()));
        }
        TeamBudgetMonth.Adjustment entry = new TeamBudgetMonth.Adjustment(clock.instant(), actor, "SET", delta, capUsd, reason, null, null);
        ITeamBudgetStore.AdjustOutcome outcome = store.applyAdjustment(groupId, month, delta, before.baseUsd(), entry, null, expiresAt(month));
        if (outcome == ITeamBudgetStore.AdjustOutcome.REFUSED) {
            throw new IllegalArgumentException("The cap cannot be set below what has already been spent this month.");
        }
        meterRegistry.counter("eddi.team.budget.adjustments", "kind", "set").increment();
        audit.adjusted(actor, groupId, month, delta, before.capUsd(), capUsd, reason);
        return snapshot(groupId, config);
    }

    /**
     * Whether the team's month records this step of the transfer: the applied id
     * ({@code appliedTransferIds}, the store's dedup record) or, as a fallback, the
     * adjustment history entry. The applied ids are checked first because the
     * history is the shorter of the two bounded lists, so an entry can leave it
     * while the step's id is still retained.
     */
    private boolean hasStep(String groupId, String month, String stepKey, String kind, String transferId) {
        return store.read(groupId, month).map(m -> m.appliedTransferIds().contains(stepKey) || m.adjustments().stream()
                .anyMatch(adj -> kind.equals(adj.kind()) && transferId.equals(adj.transferId()))).orElse(false);
    }

    /**
     * The applied-id of one step ({@code out}, {@code in}, {@code rev}) of a
     * transfer: {@code <transferId>:<fingerprint>:<step>}. The fingerprint binds
     * the id to the request (source, target, amount), so the store's dedup record
     * is also the record a retry is compared with -- it lives exactly as long as
     * the dedup itself.
     */
    static String stepKey(String transferId, String fromGroupId, String toGroupId, double amountUsd, String step) {
        return transferId + ":" + fingerprint(fromGroupId, toGroupId, amountUsd) + ":" + step;
    }

    static String fingerprint(String fromGroupId, String toGroupId, double amountUsd) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((fromGroupId + '\n' + toGroupId + '\n' + amountUsd).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Whether {@code month} records a step of {@code transferId} made for a
     * different request than {@code fingerprint}.
     */
    static boolean usedForAnotherRequest(TeamBudgetMonth month, String transferId, String fingerprint) {
        if (month == null) {
            return false;
        }
        for (String applied : month.appliedTransferIds()) {
            int stepSeparator = applied.lastIndexOf(':');
            int fingerprintStart = stepSeparator - FINGERPRINT_LENGTH;
            if (stepSeparator < 0 || fingerprintStart < 1 || applied.charAt(fingerprintStart - 1) != ':') {
                continue;
            }
            if (applied.substring(0, fingerprintStart - 1).equals(transferId)
                    && !applied.substring(fingerprintStart, stepSeparator).equals(fingerprint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves {@code amountUsd} of this month's cap from one team to another.
     * <p>
     * Debit first, conditional on the source keeping a cap at or above its spend,
     * then credit; if the credit fails the debit is reversed. Money only ever
     * disappears from caps, never appears, so a failure in between is conservative.
     * Idempotent by {@code transferId} (a generated one when null), and bound to
     * the request: reusing an id for other teams or another amount is refused
     * rather than crediting what the first request never debited. The id is looked
     * up in the current UTC month only.
     */
    public TransferResult transfer(String fromGroupId, String toGroupId, double amountUsd, String reason, String actor, String transferId) {
        requireReason(reason);
        if (!Double.isFinite(amountUsd) || !(amountUsd > 0.0)) {
            throw new IllegalArgumentException("amountUsd must be a positive, finite number");
        }
        if (fromGroupId == null || toGroupId == null || fromGroupId.equals(toGroupId)) {
            throw new IllegalArgumentException("A transfer needs two different teams");
        }
        AgentGroupConfiguration from = requireBudgeted(fromGroupId);
        AgentGroupConfiguration to = requireBudgeted(toGroupId);
        String tid = transferId == null || transferId.isBlank() ? UUID.randomUUID().toString() : transferId;
        String month = currentMonth();
        Instant now = clock.instant();
        TeamBudgetMonth fromMonth = store.read(fromGroupId, month).orElse(null);
        TeamBudgetMonth toMonth = store.read(toGroupId, month).orElse(null);
        String fingerprint = fingerprint(fromGroupId, toGroupId, amountUsd);
        if (usedForAnotherRequest(fromMonth, tid, fingerprint) || usedForAnotherRequest(toMonth, tid, fingerprint)) {
            throw new IllegalArgumentException("Transfer " + tid
                    + " was already used for a different transfer (other teams or another amount); start it again with a new Idempotency-Key.");
        }
        TeamBudgetSnapshot fromBefore = snapshot(fromGroupId, from, month, fromMonth);
        TeamBudgetSnapshot toBefore = snapshot(toGroupId, to, month, toMonth);

        TeamBudgetMonth.Adjustment out = new TeamBudgetMonth.Adjustment(now, actor, "TRANSFER_OUT", -amountUsd, fromBefore.capUsd() - amountUsd,
                reason, toGroupId, tid);
        ITeamBudgetStore.AdjustOutcome debit = store.applyAdjustment(fromGroupId, month, -amountUsd, fromBefore.baseUsd(), out,
                stepKey(tid, fromGroupId, toGroupId, amountUsd, "out"),
                expiresAt(month));
        if (debit == ITeamBudgetStore.AdjustOutcome.REFUSED) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "Team '%s' has only $%.2f left this month; it cannot give up $%.2f.",
                    displayName(from, fromGroupId), fromBefore.remainingUsd(), amountUsd));
        }
        if (debit == ITeamBudgetStore.AdjustOutcome.DUPLICATE) {
            // A retry of this transfer id. The first attempt may have finished, been
            // reversed after
            // a failed credit, or died between debit and credit: only the last needs the
            // credit.
            if (hasStep(fromGroupId, month, stepKey(tid, fromGroupId, toGroupId, amountUsd, "rev"), "TRANSFER_OUT_REVERSED", tid)) {
                throw new IllegalArgumentException(
                        "Transfer " + tid + " was reversed after its credit failed; start it again with a new Idempotency-Key.");
            }
            if (hasStep(toGroupId, month, stepKey(tid, fromGroupId, toGroupId, amountUsd, "in"), "TRANSFER_IN", tid)) {
                return new TransferResult(snapshot(fromGroupId, from), snapshot(toGroupId, to), tid);
            }
        }
        TeamBudgetMonth.Adjustment in = new TeamBudgetMonth.Adjustment(now, actor, "TRANSFER_IN", amountUsd, toBefore.capUsd() + amountUsd, reason,
                fromGroupId, tid);
        try {
            store.applyAdjustment(toGroupId, month, amountUsd, toBefore.baseUsd(), in, stepKey(tid, fromGroupId, toGroupId, amountUsd, "in"),
                    expiresAt(month));
        } catch (RuntimeException creditFailure) {
            try {
                TeamBudgetMonth.Adjustment back = new TeamBudgetMonth.Adjustment(clock.instant(), actor, "TRANSFER_OUT_REVERSED", amountUsd,
                        fromBefore.capUsd(), "reversal: credit to the target failed", toGroupId, tid);
                store.applyAdjustment(fromGroupId, month, amountUsd, fromBefore.baseUsd(), back,
                        stepKey(tid, fromGroupId, toGroupId, amountUsd, "rev"), expiresAt(month));
                audit.transferred(actor, fromGroupId, toGroupId, month, amountUsd, reason, tid, "REVERSED");
            } catch (RuntimeException reverseFailure) {
                LOGGER.errorf(reverseFailure, "Team budget transfer %s PARTIAL: debited %s but could not credit %s nor reverse",
                        LogSanitizer.sanitize(tid),
                        LogSanitizer.sanitize(fromGroupId), LogSanitizer.sanitize(toGroupId));
                audit.transferred(actor, fromGroupId, toGroupId, month, amountUsd, reason, tid, "PARTIAL");
            }
            throw creditFailure;
        }
        meterRegistry.counter("eddi.team.budget.adjustments", "kind", "transfer").increment();
        audit.transferred(actor, fromGroupId, toGroupId, month, amountUsd, reason, tid, "APPLIED");
        return new TransferResult(snapshot(fromGroupId, from), snapshot(toGroupId, to), tid);
    }

    /** A group's most recent months, newest first. */
    public List<TeamBudgetMonth> history(String groupId, int months) {
        return store.history(groupId, Math.min(Math.max(1, months), Math.max(1, retentionMonths)));
    }

    private AgentGroupConfiguration requireBudgeted(String groupId) {
        AgentGroupConfiguration config = readConfig(groupId);
        if (config == null) {
            throw new IllegalArgumentException("Group '" + LogSanitizer.sanitize(groupId) + "' does not exist");
        }
        if (config.getTeamBudget() == null || config.getTeamBudget().monthlyBudgetUsd() == null) {
            throw new IllegalArgumentException("Group '" + displayName(config, groupId) + "' has no teamBudget configured");
        }
        return config;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The reason may be at most " + MAX_REASON_LENGTH + " characters");
        }
    }
}
