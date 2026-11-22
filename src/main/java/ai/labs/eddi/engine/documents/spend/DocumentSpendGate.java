/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import ai.labs.eddi.engine.documents.DocumentSettings;
import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.SubjectKind;
import ai.labs.eddi.engine.pricing.SpendOwner;
import ai.labs.eddi.engine.pricing.SystemSpendMeter;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Admits and records model spend made on behalf of a document owner (a summary
 * job, the modality bridge): the <b>cumulative daily ceilings</b> per user and
 * per team space, on top of the tenant budget.
 * <p>
 * <b>Admission order</b> ({@link #admit}): the tenant's monthly budget
 * ({@link TenantQuotaService}), then the user's daily ceiling, then the team's.
 * The per-document ceiling ({@code documents.summary.maxCostPerDocument}) is
 * the caller's, checked after this. The first ceiling that refuses is reported
 * with the spend so far and the ceiling. Admission fails closed: a budget or
 * bucket that cannot be read refuses.
 * <p>
 * Admission is a pre-flight check, not a reservation: two calls admitted at the
 * same moment can together pass a ceiling by what they then spend. The ceilings
 * bound cumulative spend over a day; the per-document ceiling and the per-user
 * job limits bound what is in flight.
 * <p>
 * <b>Recording</b> ({@link #record}) is called by the owner variant of
 * {@link SystemSpendMeter} ({@code charge(SpendOwner, …)}) — callers charge
 * through the meter, which also charges the tenant and the spend sinks. Each
 * charge is one atomic increment of the user's and the team's bucket for the
 * current UTC day.
 */
@ApplicationScoped
public class DocumentSpendGate {

    private static final Logger LOGGER = Logger.getLogger(DocumentSpendGate.class);

    /** Why an admission was refused. */
    public enum Refusal {
        /** The tenant's monthly budget would be exceeded. */
        TENANT_BUDGET,
        /** The user's daily document ceiling would be exceeded. */
        USER_DAILY_CEILING,
        /** The team space's daily document ceiling would be exceeded. */
        TEAM_DAILY_CEILING,
        /** A budget or counter could not be read; refused for safety. */
        ACCOUNTING_UNAVAILABLE
    }

    /**
     * The answer to {@link #admit}.
     *
     * @param admitted
     *            whether the work may start
     * @param refusal
     *            why not, or null when admitted
     * @param spentUsd
     *            for a daily ceiling: what the subject has spent today; NaN
     *            otherwise
     * @param ceilingUsd
     *            for a daily ceiling: the ceiling; NaN otherwise
     * @param estimateUsd
     *            the estimate that was checked
     * @param message
     *            one sentence naming the ceiling and both numbers, or null when
     *            admitted; contains no user or team identifier
     */
    public record Admission(boolean admitted, Refusal refusal, double spentUsd, double ceilingUsd, double estimateUsd, String message) {

        static Admission granted(double estimateUsd) {
            return new Admission(true, null, Double.NaN, Double.NaN, estimateUsd, null);
        }

        static Admission refused(Refusal refusal, double spentUsd, double ceilingUsd, double estimateUsd, String message) {
            return new Admission(false, refusal, spentUsd, ceilingUsd, estimateUsd, message);
        }
    }

    private final TenantQuotaService tenantQuotaService;
    private final IDocumentSpendStore store;
    private final Supplier<DocumentSpendLimits> limits;
    private final Clock clock;

    @Inject
    public DocumentSpendGate(TenantQuotaService tenantQuotaService, IDocumentSpendStore store, DocumentSettings settings) {
        this(tenantQuotaService, store, settings::spendLimits, Clock.systemUTC());
    }

    /** Explicit limits and clock (tests). */
    public DocumentSpendGate(TenantQuotaService tenantQuotaService, IDocumentSpendStore store, Supplier<DocumentSpendLimits> limits, Clock clock) {
        this.tenantQuotaService = tenantQuotaService;
        this.store = store;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Whether work estimated at {@code estimateUsd} may start for {@code owner}. An
     * unpriced model's estimate is 0: it passes every ceiling not yet reached (no
     * price is invented), and its calls are counted when charged.
     *
     * @param estimateUsd
     *            the caller's estimate; a non-finite or negative value counts as 0
     */
    public Admission admit(SpendOwner owner, double estimateUsd) {
        double estimate = Double.isFinite(estimateUsd) && estimateUsd > 0.0 ? estimateUsd : 0.0;
        Admission tenant = admitTenant(owner, estimate);
        if (tenant != null) {
            return tenant;
        }
        if (owner == null) {
            return Admission.granted(estimate);
        }
        DocumentSpendLimits current = limits.get();
        long today = today();
        Admission user = admitDaily(SubjectKind.USER, owner.userId(), current.maxPerUserPerDayUsd(), today, estimate);
        if (user != null) {
            return user;
        }
        Admission team = admitDaily(SubjectKind.TEAM, owner.teamSpaceId(), current.maxPerTeamPerDayUsd(), today, estimate);
        if (team != null) {
            return team;
        }
        return Admission.granted(estimate);
    }

    /** A refusal by the tenant budget, or null when it admits. */
    private Admission admitTenant(SpendOwner owner, double estimate) {
        if (tenantQuotaService == null) {
            return null;
        }
        QuotaCheckResult result;
        try {
            String tenantId = owner != null && owner.tenantId() != null ? owner.tenantId() : tenantQuotaService.getDefaultTenantId();
            result = tenantQuotaService.checkCostBudget(tenantId, estimate);
        } catch (RuntimeException e) {
            LOGGER.debugf("Document spend admission failed closed on the tenant budget: %s", sanitize(e.getMessage()));
            return Admission.refused(Refusal.ACCOUNTING_UNAVAILABLE, Double.NaN, Double.NaN, estimate,
                    "Document work was not started: the tenant's cost budget could not be read.");
        }
        if (result == null || result.allowed()) {
            return null;
        }
        if (result.accountingUnavailable()) {
            return Admission.refused(Refusal.ACCOUNTING_UNAVAILABLE, Double.NaN, Double.NaN, estimate,
                    "Document work was not started: the tenant's cost budget could not be read.");
        }
        return Admission.refused(Refusal.TENANT_BUDGET, Double.NaN, Double.NaN, estimate,
                "Document work was not started: the tenant's monthly cost budget is exhausted (estimate " + usd(estimate) + ").");
    }

    /** A refusal by one daily ceiling, or null when it admits or is not set. */
    private Admission admitDaily(SubjectKind kind, String subject, OptionalDouble ceiling, long today, double estimate) {
        if (ceiling.isEmpty() || subject == null || subject.isBlank()) {
            return null;
        }
        double limit = ceiling.getAsDouble();
        double spent;
        try {
            spent = store.spent(kind, subject, today);
        } catch (RuntimeException e) {
            LOGGER.debugf("Document spend admission failed closed on the %s counter: %s", kind, sanitize(e.getMessage()));
            return Admission.refused(Refusal.ACCOUNTING_UNAVAILABLE, Double.NaN, limit, estimate,
                    "Document work was not started: today's document spend could not be read.");
        }
        if (spent >= limit || spent + estimate > limit) {
            String who = kind == SubjectKind.USER ? "this user" : "this team";
            return Admission.refused(kind == SubjectKind.USER ? Refusal.USER_DAILY_CEILING : Refusal.TEAM_DAILY_CEILING, spent, limit, estimate,
                    "Document work was not started: today's document spend for " + who + " is " + usd(spent) + " of the " + usd(limit)
                            + " daily ceiling, and this work is estimated at " + usd(estimate) + ".");
        }
        return null;
    }

    /**
     * Adds one charge of {@code usd} to the owner's user and team buckets for today
     * (UTC). Never throws: the work already ran, so a store failure is logged and
     * that amount is missing from the daily counters.
     */
    public void record(SpendOwner owner, double usd) {
        if (owner == null) {
            return;
        }
        long today = today();
        long oldestRetained = today - limits.get().retentionDays() + 1;
        recordOne(SubjectKind.USER, owner.userId(), today, usd, oldestRetained);
        recordOne(SubjectKind.TEAM, owner.teamSpaceId(), today, usd, oldestRetained);
    }

    private void recordOne(SubjectKind kind, String subject, long today, double usd, long oldestRetained) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        try {
            store.add(kind, subject, today, usd, oldestRetained);
        } catch (RuntimeException e) {
            LOGGER.warnf("Document spend of $%.6f could not be added to the daily %s counter: %s", usd, kind, sanitize(e.getMessage()));
        }
    }

    /** Today, as a UTC epoch day. */
    long today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toEpochDay();
    }

    static String usd(double value) {
        return String.format(Locale.ROOT, value != 0.0 && Math.abs(value) < 0.01 ? "$%.4f" : "$%.2f", value);
    }
}
