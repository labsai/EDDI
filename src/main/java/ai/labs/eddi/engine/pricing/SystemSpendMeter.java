/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.internal.groups.GroupCostLedger;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.llm.pricing.ModelPriceResolver;
import ai.labs.eddi.modules.llm.pricing.PriceOverride;
import ai.labs.eddi.modules.llm.pricing.PricedSpend;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
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

    private void chargeTenant(GroupConversation gc, String detail, double delta, PricedSpend priced, Map<?, ?> usage) {
        String tenantId = null;
        try {
            if (tenantQuotaService != null) {
                tenantId = tenantQuotaService.getDefaultTenantId();
                tenantQuotaService.recordCost(tenantId, delta);
            }
        } catch (RuntimeException e) {
            // The work already ran; a denied or unavailable result must not fail it.
            LOGGER.warnf("Group %s: system spend of $%.6f could not be charged to the tenant: %s", gc.getId(), delta, e.getMessage());
            return;
        }
        sinks.fire(new SpendEvent(tenantId, null, null, null, null, gc.getGroupId(), gc.getId(), SpendEvent.Kind.GROUP_SYSTEM, detail, delta,
                priced.unpricedCalls(), asLong(usage, "inputTokens"), asLong(usage, "outputTokens"), Instant.now()));
    }

    private static long asLong(Map<?, ?> usage, String key) {
        return usage != null && usage.get(key) instanceof Number n ? n.longValue() : 0L;
    }
}
