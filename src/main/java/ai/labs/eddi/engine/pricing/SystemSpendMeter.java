/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.documents.spend.DocumentSpendGate;
import ai.labs.eddi.engine.internal.groups.GroupCostLedger;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
 *
 * <h2>Document owners</h2> Model calls made for a document owner outside any
 * turn (a summary job, the modality bridge) are charged with the owner variant,
 * {@code charge(SpendOwner, …)}: one call prices the usage, adds it to the
 * owner's daily user and team buckets ({@link DocumentSpendGate}), charges the
 * owner's tenant and reports a {@link SpendEvent.Kind#DOCUMENT} event to the
 * sinks. Admission for those calls is {@link DocumentSpendGate#admit}, which
 * also checks the daily ceilings.
 */
@Startup
@ApplicationScoped
public class SystemSpendMeter {

    private static final Logger LOGGER = Logger.getLogger(SystemSpendMeter.class);

    private static volatile SystemSpendMeter instance;

    /** The {@code site} under which unpriced document calls are counted. */
    static final String DOCUMENT_SITE = "document";

    private final TenantQuotaService tenantQuotaService;
    private final SpendSinks sinks;
    private final DocumentSpendGate documentSpend;

    /** Without daily document counters (tests of the group path). */
    public SystemSpendMeter(TenantQuotaService tenantQuotaService, SpendSinks sinks) {
        this(tenantQuotaService, sinks, null);
    }

    @Inject
    public SystemSpendMeter(TenantQuotaService tenantQuotaService, SpendSinks sinks, DocumentSpendGate documentSpend) {
        this.tenantQuotaService = tenantQuotaService;
        this.sinks = sinks;
        this.documentSpend = documentSpend;
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
            meter.chargeTenant(new Pending(null, SpendEvent.Kind.GROUP_SYSTEM, null, gc.getGroupId(), gc.getId(), detail, delta,
                    priced.unpricedCalls(), asLong(usage, "inputTokens"), asLong(usage, "outputTokens")), "Group " + sanitize(gc.getId()));
        }
        return usd;
    }

    /**
     * Prices {@code usage} made on behalf of a document owner and charges it: one
     * atomic increment of the owner's user and team buckets for today (UTC), the
     * owner's tenant, and a {@link SpendEvent.Kind#DOCUMENT} event for the sinks.
     * Admit first with {@link DocumentSpendGate#admit}.
     * <p>
     * Unlike the group variant there is no ledger to charge by replacement: every
     * call adds its full cost, so a caller must not charge the same model call
     * twice (a cached result is not a call and is not charged). An unpriced model
     * costs $0 and is counted as an unpriced sighting under site
     * {@value #DOCUMENT_SITE}; its call still counts in the daily buckets. Never
     * throws.
     *
     * @param owner
     *            whom the spend is charged to; null prices only
     * @param key
     *            the operation, for attribution in log lines (a summary leaf, a
     *            bridge description); not a deduplication key
     * @param detail
     *            a short label for the {@link SpendEvent}; {@code key} when null
     * @return the priced cost of this call (0 when unpriced)
     */
    public static double charge(SpendOwner owner, String key, PriceOverride override, Map<?, ?> usage, String detail) {
        PricedSpend priced = ModelPriceResolver.price(override, usage);
        if (priced.unpricedCalls() > 0) {
            UnpricedSightings.record(usage != null && usage.get("pricingModel") instanceof String m ? m : null, DOCUMENT_SITE,
                    priced.unpricedCalls());
        }
        double usd = priced.usd();
        SystemSpendMeter meter = instance;
        if (owner == null || meter == null) {
            return usd;
        }
        try {
            meter.chargeOwner(owner, key, detail != null ? detail : key, usd, priced, usage);
        } catch (RuntimeException e) {
            LOGGER.warnf("Document spend for %s could not be charged: %s", sanitize(key), sanitize(e.getMessage()));
        }
        return usd;
    }

    private void chargeOwner(SpendOwner owner, String key, String detail, double usd, PricedSpend priced, Map<?, ?> usage) {
        boolean positive = Double.isFinite(usd) && usd > 0.0;
        if (documentSpend != null) {
            // Before the tenant, and whatever the tenant counter answers: the daily
            // ceilings must see every charge, and a carried tenant charge is never
            // added to them a second time. Guarded on its own, so a failure here (a
            // store outage, a malformed ceiling property) never costs the tenant charge.
            try {
                documentSpend.record(owner, positive ? usd : 0.0);
            } catch (RuntimeException e) {
                LOGGER.warnf("Document work %s: the daily spend counters could not be updated: %s", sanitize(key), sanitize(e.getMessage()));
            }
        }
        if (positive) {
            chargeTenant(new Pending(owner.tenantId(), SpendEvent.Kind.DOCUMENT, owner.userId(), null, null, detail, usd, priced.unpricedCalls(),
                    asLong(usage, "inputTokens"), asLong(usage, "outputTokens")), "Document work " + sanitize(key));
        }
    }

    /**
     * One system charge, with the attribution its spend event reports.
     *
     * @param tenantId
     *            the tenant to charge; null means the default tenant
     */
    private record Pending(String tenantId, SpendEvent.Kind kind, String userId, String groupId, String groupConversationId, String detail,
            double usd, int unpricedCalls, long inputTokens, long outputTokens) {
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

    private void chargeTenant(Pending fresh, String origin) {
        // Charges the tenant counter could not take are carried to the next one, as
        // DreamService does: the ledger delta (or the daily buckets) has already
        // advanced, so without this the amount would be lost for good. Each carries
        // its own attribution and tenant, so a recovery reports every charge under
        // what incurred it and charges every tenant only its own amounts.
        List<Pending> batch = takePending();
        batch.add(fresh);
        Map<String, List<Pending>> byTenant = new LinkedHashMap<>();
        for (Pending entry : batch) {
            byTenant.computeIfAbsent(entry.tenantId(), ignored -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, List<Pending>> group : byTenant.entrySet()) {
            chargeTenant(group.getKey(), group.getValue(), group.getValue().contains(fresh) ? origin : "Carried system charges");
        }
    }

    private void chargeTenant(String requestedTenantId, List<Pending> entries, String origin) {
        double charge = entries.stream().mapToDouble(Pending::usd).sum();
        String tenantId = requestedTenantId;
        try {
            if (tenantQuotaService != null) {
                if (tenantId == null) {
                    tenantId = tenantQuotaService.getDefaultTenantId();
                }
                QuotaCheckResult result = tenantQuotaService.recordCost(tenantId, charge);
                if (result != null && result.accountingUnavailable()) {
                    // An unreadable quota store is a returned result, not an exception. The tenant
                    // counter did not take the charge, so no sink may see it ("a sink sees exactly
                    // the charges the tenant counter saw").
                    restorePending(entries);
                    LOGGER.warnf("%s: system spend of $%.6f was not charged to the tenant — quota accounting unavailable; "
                            + "carried to the next system charge", origin, charge);
                    return;
                }
            }
        } catch (RuntimeException e) {
            // The work already ran; a denied or unavailable result must not fail it.
            restorePending(entries);
            LOGGER.warnf("%s: system spend of $%.6f could not be charged to the tenant (carried to the next charge): %s", origin, charge,
                    sanitize(e.getMessage()));
            return;
        }
        for (Pending entry : entries) {
            sinks.fire(new SpendEvent(tenantId, null, null, null, entry.userId(), entry.groupId(), entry.groupConversationId(), entry.kind(),
                    entry.detail(), entry.usd(), entry.unpricedCalls(), entry.inputTokens(), entry.outputTokens(), Instant.now()));
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
            LOGGER.warnf("Dropping a carried system charge of $%.6f: more than %d are waiting for the quota store",
                    dropped.usd(), MAX_PENDING);
        }
    }

    private static long asLong(Map<?, ?> usage, String key) {
        return usage != null && usage.get(key) instanceof Number n ? n.longValue() : 0L;
    }
}
