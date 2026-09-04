/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Tenant quota enforcement engine.
 * <p>
 * Provides <strong>atomic</strong> quota acquisition methods that merge the
 * check ("am I within budget?") and record ("increment the counter") into a
 * single indivisible operation, eliminating TOCTOU races.
 * <p>
 * Single-instance atomicity is guaranteed by the in-memory store's per-tenant
 * locks. Cluster-safe atomicity requires a DB-backed {@link ITenantQuotaStore}
 * that uses storage-level atomic operations.
 * <p>
 * In single-tenant mode, all operations use the default tenant ID.
 */
@ApplicationScoped
public class TenantQuotaService {

    private static final Logger LOGGER = Logger.getLogger(TenantQuotaService.class);

    @Inject
    ITenantQuotaStore quotaStore;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ICacheFactory cacheFactory;

    /** Name of the short-TTL cache behind {@link #quotaFor}. */
    static final String QUOTA_CACHE_NAME = "tenantQuotas";

    /**
     * How long a tenant's quota configuration is reused before it is read again.
     * <p>
     * Short enough that a change made on another cluster node takes effect within
     * seconds, long enough that the configuration is not fetched twice per
     * conversation turn. {@code RestTenantQuota} invalidates explicitly on write,
     * so this only bounds the cross-node case.
     */
    static final Duration QUOTA_CACHE_TTL = Duration.ofSeconds(5);

    /**
     * Quota configuration, cached per tenant.
     * <p>
     * Every gate in this class opened with {@code quotaStore.getQuota(tenantId)},
     * and {@code ConversationService} calls two of them per request — so each turn
     * paid a store round trip (two pooled connection checkouts on PostgreSQL) on
     * the hottest path in the system, usually only to learn that quotas are
     * disabled. Only non-null results are cached: a tenant with no quota row is the
     * "unlimited" case, it is rare once the default tenant is bootstrapped, and a
     * {@code ConcurrentMap} cannot hold a null anyway.
     */
    private ICache<String, TenantQuota> quotaCache;

    @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default")
    String defaultTenantId;

    // Metrics
    //
    // There is deliberately no aggregate (untagged) counter for denials. A
    // PrometheusMeterRegistry keeps only the first tag-key shape registered
    // under a given name and silently drops every later one — no exception, no
    // warning. Registering "eddi.tenant.quota.denied" with no tags here meant
    // the tagged {tenant, type} increments below never reached /q/metrics at
    // all, so the per-tenant breakdown documented in docs/metrics.md did not
    // exist. Every denial is now recorded once, tagged; the aggregate is
    // sum(rate(eddi_tenant_quota_denied_total[...])) at query time.
    //
    // "eddi.tenant.quota.allowed" has a single untagged shape everywhere and is
    // therefore safe to hold as a field.
    private Counter quotaAllowedCounter;

    @PostConstruct
    void init() {
        quotaAllowedCounter = meterRegistry.counter("eddi.tenant.quota.allowed");
        quotaCache = cacheFactory.getCache(QUOTA_CACHE_NAME, QUOTA_CACHE_TTL);
        LOGGER.info("Tenant quota service initialized");
    }

    /**
     * CDI constructor.
     */
    public TenantQuotaService() {
    }

    /**
     * Test-only constructor — skips CDI.
     */
    public TenantQuotaService(ITenantQuotaStore quotaStore, MeterRegistry meterRegistry, String defaultTenantId) {
        this.quotaStore = quotaStore;
        this.meterRegistry = meterRegistry;
        this.defaultTenantId = defaultTenantId;
        this.quotaAllowedCounter = meterRegistry.counter("eddi.tenant.quota.allowed");
        this.cacheFactory = new CacheFactory();
        this.quotaCache = this.cacheFactory.getCache(QUOTA_CACHE_NAME, QUOTA_CACHE_TTL);
    }

    /**
     * The tenant's quota configuration, from the short-TTL cache when possible.
     *
     * @return the quota, or null when the tenant has none configured
     */
    private TenantQuota quotaFor(String tenantId) {
        if (tenantId == null || quotaCache == null) {
            return quotaStore.getQuota(tenantId);
        }
        TenantQuota cached = quotaCache.get(tenantId);
        if (cached != null) {
            return cached;
        }
        TenantQuota resolved = quotaStore.getQuota(tenantId);
        if (resolved != null) {
            quotaCache.put(tenantId, resolved);
        }
        return resolved;
    }

    /**
     * Store a tenant's quota and make it effective on this node immediately.
     * <p>
     * The enforcement gates read configuration through {@link #QUOTA_CACHE_TTL}, so
     * a write straight to the store would not apply until the entry expired.
     * Writing through here is the supported path; a write made on another cluster
     * node is still bounded by the TTL.
     */
    public void setQuota(TenantQuota quota) {
        quotaStore.setQuota(quota);
        if (quota != null) {
            invalidateQuotaCache(quota.tenantId());
        }
    }

    /**
     * Forget a tenant's cached quota configuration. Call after any write that did
     * not go through {@link #setQuota}, so the change takes effect on this node
     * immediately rather than after {@link #QUOTA_CACHE_TTL}.
     */
    public void invalidateQuotaCache(String tenantId) {
        if (quotaCache != null && tenantId != null) {
            quotaCache.remove(tenantId);
        }
    }

    /**
     * Get the current default tenant ID.
     */
    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    // ─── Atomic Slot Acquisition ───

    /**
     * Atomically acquire a conversation slot for the default tenant. If the daily
     * limit has been reached, returns a denied result. Otherwise, increments the
     * counter and returns OK.
     */
    public QuotaCheckResult acquireConversationSlot() {
        return acquireConversationSlot(defaultTenantId);
    }

    /**
     * Atomically acquire a conversation slot for a specific tenant. If the daily
     * limit has been reached, returns a denied result. Otherwise, increments the
     * counter and returns OK.
     */
    public QuotaCheckResult acquireConversationSlot(String tenantId) {
        TenantQuota quota = quotaFor(tenantId);
        if (quota == null || !quota.enabled()) {
            return QuotaCheckResult.OK;
        }

        int limit = quota.maxConversationsPerDay();
        QuotaCheckResult result = quotaStore.tryIncrementConversations(tenantId, limit);

        if (result.allowed()) {
            quotaAllowedCounter.increment();
            meterRegistry.counter("eddi.tenant.usage.conversations", "tenant", tenantId).increment();
        } else {
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "conversation").increment();
            LOGGER.warn(result.reason());
        }

        return result;
    }

    /**
     * Atomically acquire an API call slot for the default tenant. If the per-minute
     * rate limit has been reached, returns a denied result. Otherwise, increments
     * the counter and returns OK.
     */
    public QuotaCheckResult acquireApiCallSlot() {
        return acquireApiCallSlot(defaultTenantId);
    }

    /**
     * Atomically acquire an API call slot for a specific tenant. If the per-minute
     * rate limit has been reached, returns a denied result. Otherwise, increments
     * the counter and returns OK.
     */
    public QuotaCheckResult acquireApiCallSlot(String tenantId) {
        TenantQuota quota = quotaFor(tenantId);
        if (quota == null || !quota.enabled()) {
            return QuotaCheckResult.OK;
        }

        int limit = quota.maxApiCallsPerMinute();
        QuotaCheckResult result = quotaStore.tryIncrementApiCalls(tenantId, limit);

        if (result.allowed()) {
            quotaAllowedCounter.increment();
            meterRegistry.counter("eddi.tenant.usage.api_calls", "tenant", tenantId).increment();
        } else {
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "api_call").increment();
            LOGGER.warn(result.reason());
        }

        return result;
    }

    // ─── Agent Capacity ───

    /**
     * Read-only capacity gate: checks whether deploying one MORE distinct agent
     * would exceed {@link TenantQuota#maxAgentsPerTenant()}.
     * <p>
     * Deliberately shaped like {@link #checkCostBudget} rather than
     * {@code acquire*Slot()}: the deployed-agent count is a <em>stock</em> derived
     * by counting current deployments, not a per-window <em>flow</em>. A stored
     * counter would drift irrecoverably, because several paths add or remove
     * deployments without passing through any acquire/release point — the scheduled
     * re-deploy sweep, the old-version undeploy sweep, teardown tooling, and the
     * lazy re-deploy on first use. Passing the count in also keeps this package
     * free of a dependency on the deployment store and keeps the method trivially
     * testable.
     * <p>
     * Like {@code checkCostBudget}, this does NOT increment
     * {@code quotaAllowedCounter} on the happy path — that counter is documented as
     * counting slot acquisitions only.
     *
     * @param currentDistinctAgents
     *            number of distinct agent ids currently deployed for this tenant,
     *            excluding the agent being deployed
     */
    public QuotaCheckResult checkAgentQuota(String tenantId, int currentDistinctAgents) {
        TenantQuota quota = quotaFor(tenantId);
        if (quota == null || !quota.enabled()) {
            return QuotaCheckResult.OK;
        }

        int limit = quota.maxAgentsPerTenant();
        if (limit >= 0 && currentDistinctAgents >= limit) {
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "agent").increment();
            String reason = String.format("Agent limit (%d) reached for tenant '%s' — undeploy an agent before deploying another", limit,
                    tenantId);
            LOGGER.warn(reason);
            return QuotaCheckResult.denied(reason);
        }

        return QuotaCheckResult.OK;
    }

    // ─── Cost Budget ───

    /**
     * Read-only pre-call gate: checks whether the monthly cost budget has been
     * exceeded. Does NOT add any cost — this is a "can I proceed?" check used
     * before an LLM call whose cost is not yet known.
     * <p>
     * Note: unlike {@code acquire*Slot()}, this method does NOT increment
     * {@code quotaAllowedCounter} on the happy path — it is a read-only gate, not
     * an acquisition. The {@code quota.allowed} metric only counts slot
     * acquisitions.
     * <p>
     * <strong>Currently cannot deny.</strong> This gate reads
     * {@link ITenantQuotaStore#getMonthlyCost}, and nothing in production ever
     * writes to that counter — see {@link #recordCost} for the missing half. Until
     * cost metering is wired, {@code currentCost} is always {@code 0.0} and this
     * method always returns OK, whatever {@code maxMonthlyCostUsd} is configured
     * to. It is wired (AgentOrchestrator calls it before each LLM turn) and will
     * start enforcing the moment {@code recordCost} has a caller — no change is
     * needed here.
     */
    public QuotaCheckResult checkCostBudget(String tenantId) {
        TenantQuota quota = quotaFor(tenantId);
        if (quota == null || !quota.enabled()) {
            return QuotaCheckResult.OK;
        }

        double currentCost = quotaStore.getMonthlyCost(tenantId);
        double limit = quota.maxMonthlyCostUsd();
        if (limit >= 0 && currentCost >= limit) {
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "cost").increment();
            String reason = String.format("Monthly cost budget ($%.2f) exceeded for tenant '%s'", limit, tenantId);
            LOGGER.warn(reason);
            return QuotaCheckResult.denied(reason);
        }

        return QuotaCheckResult.OK;
    }

    /**
     * Post-call accounting: atomically adds the actual cost incurred and checks
     * whether the budget has been exceeded. Use the returned result to decide
     * whether to block subsequent calls or emit metrics.
     * <p>
     * <strong>NOT WIRED — this method has no production callers.</strong> It is
     * reachable only from tests. Consequently the monthly cost counter is never
     * written, {@link #checkCostBudget} always sees {@code 0.0}, and
     * {@code eddi.tenant.quota.max-monthly-cost-usd} is unenforceable today. Do not
     * read the presence of this method as "cost budgets work".
     * <p>
     * It is deliberately kept rather than deleted: it is the post-call half of a
     * design whose pre-call half ({@code checkCostBudget}) IS wired, and its store
     * counterpart {@link ITenantQuotaStore#tryAddCost} is implemented and tested in
     * all three backends. Deleting it would remove the seam without removing the
     * gap.
     * <p>
     * Two things must land before this can be called for real:
     * <ol>
     * <li><em>Per-call cost figures that are not zero.</em> Built-in tool
     * executions are currently priced at $0.00, and there is no token-cost metering
     * for LLM turns at all — so wiring this today would meter nothing but add write
     * load.</li>
     * <li><em>A decision on where to meter.</em> The candidate seams are the
     * ChatResponse-holding call sites tracked as C5 in
     * {@code planning/manager-coverage-backend-design.md}.</li>
     * </ol>
     * <p>
     * Note: internally calls {@link ITenantQuotaStore#tryAddCost} which resets all
     * expired time windows (minute, day, month) under the per-tenant lock. This is
     * harmless (lazy reset is idempotent), but means this method has broader side
     * effects than its name suggests.
     */
    public QuotaCheckResult recordCost(String tenantId, double cost) {
        TenantQuota quota = quotaFor(tenantId);
        if (quota == null || !quota.enabled()) {
            return QuotaCheckResult.OK;
        }

        double limit = quota.maxMonthlyCostUsd();
        QuotaCheckResult result = quotaStore.tryAddCost(tenantId, cost, limit);
        meterRegistry.counter("eddi.tenant.usage.cost", "tenant", tenantId).increment(cost);

        if (!result.allowed()) {
            meterRegistry.counter("eddi.tenant.quota.denied", "tenant", tenantId, "type", "cost").increment();
            LOGGER.warn(result.reason());
        }

        return result;
    }

    // ─── Usage Reporting ───

    /**
     * Get usage snapshot for a tenant.
     */
    public UsageSnapshot getUsage(String tenantId) {
        return quotaStore.getUsage(tenantId);
    }

    /**
     * Reset all usage counters for a tenant.
     */
    public void resetUsage(String tenantId) {
        quotaStore.resetUsage(tenantId);
    }
}
