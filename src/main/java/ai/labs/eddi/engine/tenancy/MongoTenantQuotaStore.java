/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import ai.labs.eddi.utils.LogSanitizer;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB-backed tenant quota store. Uses {@code findOneAndModify} for atomic
 * counter operations — safe for multi-instance deployments.
 * <p>
 * Collections:
 * <ul>
 * <li>{@code tenant_quotas} — quota configuration (limits, enabled flag)</li>
 * <li>{@code tenant_usage} — rolling usage counters (daily conversations,
 * per-minute API calls, monthly cost)</li>
 * </ul>
 * <p>
 * <strong>Document identity:</strong> {@code tenant_usage} holds exactly ONE
 * document per tenant, carrying all three counters (daily conversations,
 * per-minute API calls, monthly cost), guarded by a unique index on
 * {@code tenantId}. Every write that may insert therefore filters on
 * {@code tenantId} and nothing else — see {@link #ensureUsageDocument(String)}.
 * Mixing a window predicate ({@code dayStart} / {@code minuteStart} /
 * {@code costMonth}) into an upsert filter would make the filter miss the
 * existing document and attempt a second insert, which the unique index rejects
 * with an E11000 duplicate-key error.
 *
 * @since 6.0.0
 */
@DefaultBean
@ApplicationScoped
public class MongoTenantQuotaStore implements ITenantQuotaStore {

    private static final Logger LOGGER = Logger.getLogger(MongoTenantQuotaStore.class);
    private static final String QUOTAS_COLLECTION = "tenant_quotas";
    private static final String USAGE_COLLECTION = "tenant_usage";

    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_CONVERSATIONS_TODAY = "conversationsToday";
    private static final String FIELD_DAY_START = "dayStart";
    private static final String FIELD_API_CALLS_THIS_MINUTE = "apiCallsThisMinute";
    private static final String FIELD_MINUTE_START = "minuteStart";
    private static final String FIELD_MONTHLY_COST = "monthlyCostUsd";
    private static final String FIELD_COST_MONTH = "costMonth";

    private final MongoCollection<Document> quotas;
    private final MongoCollection<Document> usage;

    @Inject
    public MongoTenantQuotaStore(MongoDatabase database,
            @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId,
            @ConfigProperty(name = "eddi.tenant.quota.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "eddi.tenant.quota.max-conversations-per-day", defaultValue = "-1") int maxConvPerDay,
            @ConfigProperty(name = "eddi.tenant.quota.max-agents-per-tenant", defaultValue = "-1") int maxAgents,
            @ConfigProperty(name = "eddi.tenant.quota.max-api-calls-per-minute", defaultValue = "-1") int maxApiCalls,
            @ConfigProperty(name = "eddi.tenant.quota.max-monthly-cost-usd", defaultValue = "-1") double maxCost) {

        this.quotas = database.getCollection(QUOTAS_COLLECTION);
        this.usage = database.getCollection(USAGE_COLLECTION);

        ensureUniqueTenantIdIndex(quotas, QUOTAS_COLLECTION);
        ensureUniqueTenantIdIndex(usage, USAGE_COLLECTION);

        // Bootstrap default tenant quota if none exists (parity with
        // InMemoryTenantQuotaStore).
        // Uses $setOnInsert so an existing quota is never overwritten, even under
        // races.
        quotas.findOneAndUpdate(
                Filters.eq("tenantId", defaultTenantId),
                Updates.combine(
                        Updates.setOnInsert("tenantId", defaultTenantId),
                        Updates.setOnInsert("maxConversationsPerDay", maxConvPerDay),
                        Updates.setOnInsert("maxAgentsPerTenant", maxAgents),
                        Updates.setOnInsert("maxApiCallsPerMinute", maxApiCalls),
                        Updates.setOnInsert("maxMonthlyCostUsd", maxCost),
                        Updates.setOnInsert("enabled", enabled)),
                new FindOneAndUpdateOptions().upsert(true));
        LOGGER.infof("Ensured default tenant quota exists: tenantId=%s, enabled=%s, maxConv=%d, maxAgents=%d, maxApi=%d, maxCost=%.2f",
                defaultTenantId, enabled, maxConvPerDay, maxAgents, maxApiCalls, maxCost);
    }

    /**
     * Test-only constructor — no CDI injection, no bootstrap.
     */
    MongoTenantQuotaStore(MongoDatabase database) {
        this.quotas = database.getCollection(QUOTAS_COLLECTION);
        this.usage = database.getCollection(USAGE_COLLECTION);

        ensureUniqueTenantIdIndex(quotas, QUOTAS_COLLECTION);
        ensureUniqueTenantIdIndex(usage, USAGE_COLLECTION);
    }

    /**
     * Creates the unique {@code tenantId} index, tolerating failure.
     * <p>
     * A deployment that ran an earlier build with quotas enabled may already hold
     * duplicate {@code tenantId} rows (they were produced by upserts whose filter
     * pinned a rolling window, so they could miss the existing document). Index
     * creation then fails, and an unguarded {@code createIndex} would take down
     * bean construction — and with it application startup. The store no longer
     * depends on the index for correctness: every write that can insert filters on
     * {@code tenantId} alone, so the index is a safety net, not a precondition. Log
     * loudly and carry on.
     */
    private static void ensureUniqueTenantIdIndex(MongoCollection<Document> collection, String collectionName) {
        try {
            collection.createIndex(new Document(FIELD_TENANT_ID, 1), new IndexOptions().unique(true));
        } catch (RuntimeException e) {
            LOGGER.errorf(e,
                    "Could not create the unique tenantId index on '%s'. The collection most likely contains "
                            + "duplicate tenantId documents written by an earlier build; de-duplicate it and restart "
                            + "to restore the safety net. Quota accounting continues to work without the index.",
                    collectionName);
        }
    }

    // ─── Quota Configuration ───

    @Override
    public TenantQuota getQuota(String tenantId) {
        Document doc = quotas.find(Filters.eq("tenantId", tenantId)).first();
        return doc != null ? toQuota(doc) : null;
    }

    @Override
    public void setQuota(TenantQuota quota) {
        quotas.findOneAndUpdate(
                Filters.eq("tenantId", quota.tenantId()),
                Updates.combine(
                        Updates.set("tenantId", quota.tenantId()),
                        Updates.set("maxConversationsPerDay", quota.maxConversationsPerDay()),
                        Updates.set("maxAgentsPerTenant", quota.maxAgentsPerTenant()),
                        Updates.set("maxApiCallsPerMinute", quota.maxApiCallsPerMinute()),
                        Updates.set("maxMonthlyCostUsd", quota.maxMonthlyCostUsd()),
                        Updates.set("enabled", quota.enabled())),
                new FindOneAndUpdateOptions().upsert(true));
    }

    @Override
    public List<TenantQuota> listQuotas() {
        List<TenantQuota> result = new ArrayList<>();
        for (Document doc : quotas.find()) {
            result.add(toQuota(doc));
        }
        return result;
    }

    @Override
    public void deleteQuota(String tenantId) {
        quotas.deleteOne(Filters.eq("tenantId", tenantId));
        usage.deleteOne(Filters.eq("tenantId", tenantId));
    }

    // ─── Atomic Usage Operations ───
    //
    // All three mutating operations share the same three-step shape:
    //
    // 1. FAST PATH — one conditional findOneAndUpdate (window current AND under
    // limit) with NO upsert. In steady state this is the only round trip.
    // 2. MATERIALISE — ensureUsageDocument(), the ONLY write that may insert. Its
    // filter is `tenantId` alone, so it can never miss the existing document and
    // attempt a duplicate insert against the unique index.
    // 3. ROLL + RETRY — reset the expired window (conditional, no upsert), then
    // re-run the fast-path update.
    //
    // Steps 2-3 only run when the fast path misses: first call for a tenant,
    // window rollover, or an actual limit breach.
    //
    // Note: there is a minor TOCTOU race at window boundaries in multi-instance
    // deployments — between the roll and the retry another instance may also
    // roll. Document-level atomicity in MongoDB means at most one roll wins, so
    // the consequence is at worst a single false denial per window transition,
    // never over- or under-counting. Not a data corruption risk.

    @Override
    public QuotaCheckResult tryIncrementConversations(String tenantId, int limit) {
        if (limit < 0) {
            return QuotaCheckResult.OK;
        }

        long dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS).toEpochMilli();

        if (tryConsumeSlot(tenantId, FIELD_CONVERSATIONS_TODAY, FIELD_DAY_START, dayStart, limit)) {
            return QuotaCheckResult.OK;
        }

        ensureUsageDocument(tenantId);
        rollWindowIfExpired(tenantId, FIELD_CONVERSATIONS_TODAY, FIELD_DAY_START, dayStart);

        if (tryConsumeSlot(tenantId, FIELD_CONVERSATIONS_TODAY, FIELD_DAY_START, dayStart, limit)) {
            return QuotaCheckResult.OK;
        }
        return QuotaCheckResult.denied("Daily conversation limit reached (" + limit + ")");
    }

    @Override
    public QuotaCheckResult tryIncrementApiCalls(String tenantId, int limit) {
        if (limit < 0) {
            return QuotaCheckResult.OK;
        }

        long minuteStart = Instant.now().truncatedTo(ChronoUnit.MINUTES).toEpochMilli();

        if (tryConsumeSlot(tenantId, FIELD_API_CALLS_THIS_MINUTE, FIELD_MINUTE_START, minuteStart, limit)) {
            return QuotaCheckResult.OK;
        }

        ensureUsageDocument(tenantId);
        rollWindowIfExpired(tenantId, FIELD_API_CALLS_THIS_MINUTE, FIELD_MINUTE_START, minuteStart);

        if (tryConsumeSlot(tenantId, FIELD_API_CALLS_THIS_MINUTE, FIELD_MINUTE_START, minuteStart, limit)) {
            return QuotaCheckResult.OK;
        }
        return QuotaCheckResult.denied("API rate limit reached (" + limit + "/min)");
    }

    @Override
    public QuotaCheckResult tryAddCost(String tenantId, double cost, double limit) {
        String monthKey = YearMonth.now(ZoneOffset.UTC).toString();

        // Fast path: the document already carries the current month.
        Document result = addCostWithinMonth(tenantId, monthKey, cost);

        if (result == null) {
            ensureUsageDocument(tenantId);
            // Roll the month. $ne also matches documents that have no costMonth at
            // all, which is exactly the freshly materialised case.
            usage.updateOne(
                    Filters.and(
                            Filters.eq(FIELD_TENANT_ID, tenantId),
                            Filters.ne(FIELD_COST_MONTH, monthKey)),
                    Updates.combine(
                            Updates.set(FIELD_MONTHLY_COST, 0.0),
                            Updates.set(FIELD_COST_MONTH, monthKey)));
            result = addCostWithinMonth(tenantId, monthKey, cost);
        }

        if (result == null) {
            // The usage document was removed concurrently (resetUsage / deleteQuota).
            // Nothing to account against; do not deny the caller over a bookkeeping race.
            LOGGER.warnf("Could not record cost for tenant '%s' — usage document disappeared mid-update",
                    LogSanitizer.sanitize(tenantId));
            return QuotaCheckResult.OK;
        }

        Double stored = result.getDouble(FIELD_MONTHLY_COST);
        double totalCost = stored != null ? stored : cost;
        // >=, not >, to agree with TenantQuotaService.checkCostBudget (currentCost >=
        // limit) and InMemoryTenantQuotaStore. With > the pre-call gate denied at
        // exactly the limit while post-call accounting allowed.
        if (limit >= 0 && totalCost >= limit) {
            return QuotaCheckResult.denied(
                    "Monthly cost budget exceeded ($%.2f / $%.2f)".formatted(totalCost, limit));
        }
        return QuotaCheckResult.OK;
    }

    /**
     * Materialises the single {@code tenant_usage} document for a tenant.
     * <p>
     * This is the ONLY write in this class that is allowed to insert, and its
     * filter is deliberately the unique-index key ({@code tenantId}) and nothing
     * else. All counters are seeded together so that whichever operation runs first
     * for a tenant, the other two find their fields present rather than triggering
     * a second insert.
     */
    private void ensureUsageDocument(String tenantId) {
        long now = Instant.now().toEpochMilli();
        usage.updateOne(
                Filters.eq(FIELD_TENANT_ID, tenantId),
                Updates.combine(
                        Updates.setOnInsert(FIELD_TENANT_ID, tenantId),
                        Updates.setOnInsert(FIELD_CONVERSATIONS_TODAY, 0),
                        Updates.setOnInsert(FIELD_DAY_START,
                                Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.DAYS).toEpochMilli()),
                        Updates.setOnInsert(FIELD_API_CALLS_THIS_MINUTE, 0),
                        Updates.setOnInsert(FIELD_MINUTE_START,
                                Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.MINUTES).toEpochMilli()),
                        Updates.setOnInsert(FIELD_MONTHLY_COST, 0.0)),
                new UpdateOptions().upsert(true));
    }

    /**
     * Conditional increment: succeeds only when the tenant's window is current and
     * the counter is still below the limit. Never inserts, so it can never collide
     * with the unique index.
     */
    private boolean tryConsumeSlot(String tenantId, String counterField, String windowField,
                                   long windowStart, int limit) {
        Document updated = usage.findOneAndUpdate(
                Filters.and(
                        Filters.eq(FIELD_TENANT_ID, tenantId),
                        Filters.gte(windowField, windowStart),
                        Filters.lt(counterField, limit)),
                Updates.inc(counterField, 1),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return updated != null;
    }

    /**
     * Resets an expired rolling window to zero. Also repairs legacy documents that
     * predate single-document accounting and are missing the window field entirely.
     */
    private void rollWindowIfExpired(String tenantId, String counterField, String windowField, long windowStart) {
        usage.updateOne(
                Filters.and(
                        Filters.eq(FIELD_TENANT_ID, tenantId),
                        Filters.or(
                                Filters.exists(windowField, false),
                                Filters.lt(windowField, windowStart))),
                Updates.combine(
                        Updates.set(counterField, 0),
                        Updates.set(windowField, windowStart)));
    }

    /**
     * Adds cost only if the stored month matches. Never inserts.
     */
    private Document addCostWithinMonth(String tenantId, String monthKey, double cost) {
        return usage.findOneAndUpdate(
                Filters.and(
                        Filters.eq(FIELD_TENANT_ID, tenantId),
                        Filters.eq(FIELD_COST_MONTH, monthKey)),
                Updates.inc(FIELD_MONTHLY_COST, cost),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    // ─── Usage Reporting ───

    @Override
    public UsageSnapshot getUsage(String tenantId) {
        Document doc = usage.find(Filters.eq(FIELD_TENANT_ID, tenantId)).first();
        if (doc == null) {
            return UsageSnapshot.empty(tenantId);
        }
        return toSnapshot(tenantId, doc);
    }

    @Override
    public double getMonthlyCost(String tenantId) {
        Document doc = usage.find(Filters.eq(FIELD_TENANT_ID, tenantId)).first();
        if (doc == null) {
            return 0.0;
        }
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        String monthKey = doc.getString(FIELD_COST_MONTH);
        if (monthKey == null || !monthKey.equals(currentMonth.toString())) {
            return 0.0; // Stale month
        }
        return doc.getDouble(FIELD_MONTHLY_COST) != null ? doc.getDouble(FIELD_MONTHLY_COST) : 0.0;
    }

    @Override
    public void resetUsage(String tenantId) {
        usage.deleteOne(Filters.eq(FIELD_TENANT_ID, tenantId));
        LOGGER.infof("Reset usage counters for tenant '%s'", LogSanitizer.sanitize(tenantId));
    }

    // ─── Mapping ───

    private TenantQuota toQuota(Document doc) {
        return new TenantQuota(
                doc.getString("tenantId"),
                doc.getInteger("maxConversationsPerDay", -1),
                doc.getInteger("maxAgentsPerTenant", -1),
                doc.getInteger("maxApiCallsPerMinute", -1),
                doc.getDouble("maxMonthlyCostUsd") != null ? doc.getDouble("maxMonthlyCostUsd") : -1.0,
                doc.getBoolean("enabled", false));
    }

    private UsageSnapshot toSnapshot(String tenantId, Document doc) {
        Instant minuteStart = doc.getLong("minuteStart") != null
                ? Instant.ofEpochMilli(doc.getLong("minuteStart"))
                : Instant.now();
        Instant dayStart = doc.getLong("dayStart") != null
                ? Instant.ofEpochMilli(doc.getLong("dayStart"))
                : Instant.now();
        YearMonth costMonth = doc.getString("costMonth") != null
                ? YearMonth.parse(doc.getString("costMonth"))
                : YearMonth.now(ZoneOffset.UTC);
        return new UsageSnapshot(
                tenantId,
                doc.getInteger("conversationsToday", 0),
                doc.getInteger("apiCallsThisMinute", 0),
                doc.getDouble("monthlyCostUsd") != null ? doc.getDouble("monthlyCostUsd") : 0.0,
                minuteStart, dayStart, costMonth);
    }
}
