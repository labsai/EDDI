/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
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
 *
 * @since 6.0.0
 */
@DefaultBean
@ApplicationScoped
public class MongoTenantQuotaStore implements ITenantQuotaStore {

    private static final Logger LOGGER = Logger.getLogger(MongoTenantQuotaStore.class);
    private static final String QUOTAS_COLLECTION = "tenant_quotas";
    private static final String USAGE_COLLECTION = "tenant_usage";

    private final MongoCollection<Document> quotas;
    private final MongoCollection<Document> usage;

    @Inject
    public MongoTenantQuotaStore(MongoDatabase database) {
        this.quotas = database.getCollection(QUOTAS_COLLECTION);
        this.usage = database.getCollection(USAGE_COLLECTION);
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

    @Override
    public QuotaCheckResult tryIncrementConversations(String tenantId, int limit) {
        if (limit < 0) {
            return QuotaCheckResult.OK;
        }

        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);

        // Atomic: reset if expired + increment if under limit
        Document result = usage.findOneAndUpdate(
                Filters.and(
                        Filters.eq("tenantId", tenantId),
                        Filters.gte("dayStart", dayStart.toEpochMilli()),
                        Filters.lt("conversationsToday", limit)),
                Updates.combine(
                        Updates.inc("conversationsToday", 1),
                        Updates.setOnInsert("tenantId", tenantId),
                        Updates.setOnInsert("dayStart", dayStart.toEpochMilli())),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

        if (result == null) {
            // Slot not acquired — check if it's a window reset or a real limit breach
            Document existing = usage.findOneAndUpdate(
                    Filters.and(
                            Filters.eq("tenantId", tenantId),
                            Filters.lt("dayStart", dayStart.toEpochMilli())),
                    Updates.combine(
                            Updates.set("conversationsToday", 1),
                            Updates.set("dayStart", dayStart.toEpochMilli())),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

            if (existing != null) {
                return QuotaCheckResult.OK; // Window was stale, reset succeeded
            }
            return QuotaCheckResult.denied("Daily conversation limit reached (" + limit + ")");
        }
        return QuotaCheckResult.OK;
    }

    @Override
    public QuotaCheckResult tryIncrementApiCalls(String tenantId, int limit) {
        if (limit < 0) {
            return QuotaCheckResult.OK;
        }

        long minuteStart = Instant.now().truncatedTo(ChronoUnit.MINUTES).toEpochMilli();

        Document result = usage.findOneAndUpdate(
                Filters.and(
                        Filters.eq("tenantId", tenantId),
                        Filters.gte("minuteStart", minuteStart),
                        Filters.lt("apiCallsThisMinute", limit)),
                Updates.combine(
                        Updates.inc("apiCallsThisMinute", 1),
                        Updates.setOnInsert("tenantId", tenantId),
                        Updates.setOnInsert("minuteStart", minuteStart)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

        if (result == null) {
            Document existing = usage.findOneAndUpdate(
                    Filters.and(
                            Filters.eq("tenantId", tenantId),
                            Filters.lt("minuteStart", minuteStart)),
                    Updates.combine(
                            Updates.set("apiCallsThisMinute", 1),
                            Updates.set("minuteStart", minuteStart)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

            if (existing != null) {
                return QuotaCheckResult.OK;
            }
            return QuotaCheckResult.denied("API rate limit reached (" + limit + "/min)");
        }
        return QuotaCheckResult.OK;
    }

    @Override
    public QuotaCheckResult tryAddCost(String tenantId, double cost, double limit) {
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        String monthKey = currentMonth.toString();

        // Always add the cost (post-call accounting)
        Document result = usage.findOneAndUpdate(
                Filters.and(
                        Filters.eq("tenantId", tenantId),
                        Filters.eq("costMonth", monthKey)),
                Updates.combine(
                        Updates.inc("monthlyCostUsd", cost),
                        Updates.setOnInsert("tenantId", tenantId),
                        Updates.setOnInsert("costMonth", monthKey)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

        if (result == null) {
            // Stale month — reset
            usage.findOneAndUpdate(
                    Filters.eq("tenantId", tenantId),
                    Updates.combine(
                            Updates.set("monthlyCostUsd", cost),
                            Updates.set("costMonth", monthKey)),
                    new FindOneAndUpdateOptions().upsert(true));
            return QuotaCheckResult.OK;
        }

        double totalCost = result.getDouble("monthlyCostUsd");
        if (limit >= 0 && totalCost > limit) {
            return QuotaCheckResult.denied(
                    "Monthly cost budget exceeded ($%.2f / $%.2f)".formatted(totalCost, limit));
        }
        return QuotaCheckResult.OK;
    }

    // ─── Usage Reporting ───

    @Override
    public UsageSnapshot getUsage(String tenantId) {
        Document doc = usage.find(Filters.eq("tenantId", tenantId)).first();
        if (doc == null) {
            return UsageSnapshot.empty(tenantId);
        }
        return toSnapshot(tenantId, doc);
    }

    @Override
    public double getMonthlyCost(String tenantId) {
        Document doc = usage.find(Filters.eq("tenantId", tenantId)).first();
        if (doc == null) {
            return 0.0;
        }
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        String monthKey = doc.getString("costMonth");
        if (monthKey == null || !monthKey.equals(currentMonth.toString())) {
            return 0.0; // Stale month
        }
        return doc.getDouble("monthlyCostUsd") != null ? doc.getDouble("monthlyCostUsd") : 0.0;
    }

    @Override
    public void resetUsage(String tenantId) {
        usage.deleteOne(Filters.eq("tenantId", tenantId));
        LOGGER.infof("Reset usage counters for tenant '%s'", tenantId);
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
