/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.internal.groups.GroupCostLedger;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.llm.pricing.ModelPriceResolver;
import ai.labs.eddi.modules.llm.pricing.PriceOverride;
import ai.labs.eddi.modules.llm.pricing.PricedSpend;
import ai.labs.eddi.utils.LogSanitizer;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Meters model spend that a group conversation's own machinery incurs — the
 * context-window summary and the stance summaries — the way a member turn's
 * spend is metered: priced through {@link ModelPriceResolver}, recorded in the
 * group's cost ledger, <b>charged to the tenant</b>, and delivered to every
 * {@link ISpendSink}. Before this, that spend reached the group ledger only: no
 * tenant budget saw it and no gate admitted it.
 * <p>
 * The group engines are static and built with {@code new}, so the meter is
 * reached statically ({@link #admit()}, {@link #charge}); this bean registers
 * its collaborators at startup. Without it (unit tests) the meter only writes
 * the ledger, as before.
 *
 * <h2>For other features</h2> A feature that calls a model on behalf of a group
 * (a convergence judge, a facilitator outside a member turn) asks
 * {@link #admit()} before the call and hands the response's
 * {@code ToolContextBudget.tokenUsageMap} to {@link #charge} after it.
 */
@Startup
@ApplicationScoped
public class SystemSpendMeter {

    private static final Logger LOGGER = Logger.getLogger(SystemSpendMeter.class);

    private static volatile SystemSpendMeter instance;

    private final TenantQuotaService tenantQuotaService;
    private final SpendSinks sinks;

    @Inject
    public SystemSpendMeter(TenantQuotaService tenantQuotaService, SpendSinks sinks) {
        this.tenantQuotaService = tenantQuotaService;
        this.sinks = sinks;
    }

    @PostConstruct
    void register() {
        instance = this;
    }

    @PreDestroy
    void unregister() {
        if (instance == this) {
            instance = null;
        }
    }

    /** Installs {@code meter} as the static instance (tests); null removes it. */
    static void install(SystemSpendMeter meter) {
        instance = meter;
    }

    /**
     * Whether the tenant budget admits another system call. Fails closed — a budget
     * that cannot be read refuses — and the callers fall back to their zero-cost
     * path (plain truncation, an extracted stance). True without a registered
     * meter.
     */
    public static boolean admit() {
        SystemSpendMeter meter = instance;
        if (meter == null || meter.tenantQuotaService == null) {
            return true;
        }
        try {
            return meter.tenantQuotaService.checkCostBudget(meter.tenantQuotaService.getDefaultTenantId(), 0.0).allowed();
        } catch (RuntimeException e) {
            LOGGER.debugf("System spend admission failed closed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Whether the default tenant has a monthly dollar budget in force; false
     * without a registered meter. Never throws (a failure reads as budgeted).
     */
    public static boolean tenantHasBudget() {
        SystemSpendMeter meter = instance;
        if (meter == null || meter.tenantQuotaService == null) {
            return false;
        }
        try {
            return meter.tenantQuotaService.hasCostBudget(meter.tenantQuotaService.getDefaultTenantId());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Prices {@code usage}, records it in {@code gc}'s ledger under {@code key} (by
     * replacement, so a re-run of the same operation is idempotent), charges the
     * tenant the <em>increase</em> over what that key held, and delivers it to the
     * sinks.
     *
     * @param detail
     *            a short label for the {@link SpendEvent}
     * @return the priced cost of this call (0 when unpriced)
     */
    public static double charge(GroupConversation gc, String key, PriceOverride override, Map<?, ?> usage, String detail) {
        PricedSpend priced = ModelPriceResolver.price(override, usage);
        if (priced.unpricedCalls() > 0) {
            UnpricedSightings.record(usage != null && usage.get("pricingModel") instanceof String m ? m : null, "group_system",
                    priced.unpricedCalls());
        }
        double usd = priced.usd();
        if (gc == null || key == null) {
            return usd;
        }
        double previous;
        synchronized (gc.getMemberCosts()) {
            Double held = gc.getMemberCosts().get(key);
            previous = held != null && Double.isFinite(held) ? held : 0.0;
        }
        GroupCostLedger.recordSystemCost(gc, key, usd);
        double delta = usd - previous;
        SystemSpendMeter meter = instance;
        if (meter != null && Double.isFinite(delta) && delta > 0.0) {
            meter.chargeTenant(gc, detail, delta, priced, usage);
        }
        return usd;
    }

    /** One group system charge, with the attribution its spend event reports. */
    private record Pending(String groupId, String groupConversationId, String detail, double usd, int unpricedCalls, long inputTokens,
            long outputTokens) {
    }

    /**
     * A cap on carried charges, so a long store outage cannot grow memory without
     * bound.
     */
    static final int MAX_PENDING = 256;

    /**
     * Charges the tenant counter has not recorded yet (store outage), oldest first.
     */
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();

    private void chargeTenant(GroupConversation gc, String detail, double delta, PricedSpend priced, Map<?, ?> usage) {
        // Charges the tenant counter could not take are carried to the next one, as
        // DreamService
        // does: the ledger delta has already advanced, so without this the amount would
        // be lost
        // for good. Each carries its own group and usage, so a recovery reports every
        // charge
        // under the group that incurred it.
        List<Pending> batch = takePending();
        batch.add(new Pending(gc.getGroupId(), gc.getId(), detail, delta, priced.unpricedCalls(), asLong(usage, "inputTokens"),
                asLong(usage, "outputTokens")));
        double charge = batch.stream().mapToDouble(Pending::usd).sum();
        String tenantId = null;
        try {
            if (tenantQuotaService != null) {
                tenantId = tenantQuotaService.getDefaultTenantId();
                QuotaCheckResult result = tenantQuotaService.recordCost(tenantId, charge);
                if (result != null && result.accountingUnavailable()) {
                    // An unreadable quota store is a returned result, not an exception. The tenant
                    // counter did not take the charge, so no sink may see it ("a sink sees exactly
                    // the charges the tenant counter saw").
                    restorePending(batch);
                    LOGGER.warnf("Group %s: system spend of $%.6f was not charged to the tenant — quota accounting unavailable; "
                            + "carried to the next group system charge", LogSanitizer.sanitize(gc.getId()), charge);
                    return;
                }
            }
        } catch (RuntimeException e) {
            // The work already ran; a denied or unavailable result must not fail it.
            restorePending(batch);
            LOGGER.warnf("Group %s: system spend of $%.6f could not be charged to the tenant (carried to the next charge): %s",
                    LogSanitizer.sanitize(gc.getId()), charge, LogSanitizer.sanitize(e.getMessage()));
            return;
        }
        for (Pending entry : batch) {
            sinks.fire(new SpendEvent(tenantId, null, null, null, null, entry.groupId(), entry.groupConversationId(),
                    SpendEvent.Kind.GROUP_SYSTEM, entry.detail(), entry.usd(), entry.unpricedCalls(), entry.inputTokens(), entry.outputTokens(),
                    Instant.now()));
        }
    }

    private synchronized List<Pending> takePending() {
        var taken = new ArrayList<>(pending);
        pending.clear();
        return taken;
    }

    private synchronized void restorePending(List<Pending> batch) {
        // oldest first; once over the cap the oldest are dropped (their tenant charge
        // is lost,
        // which the warning below states)
        for (Pending entry : batch) {
            pending.addLast(entry);
        }
        while (pending.size() > MAX_PENDING) {
            Pending dropped = pending.pollFirst();
            LOGGER.warnf("Dropping a carried group system charge of $%.6f: more than %d are waiting for the quota store",
                    dropped.usd(), MAX_PENDING);
        }
    }

    private static long asLong(Map<?, ?> usage, String key) {
        return usage != null && usage.get(key) instanceof Number n ? n.longValue() : 0L;
    }
}
