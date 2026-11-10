/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.PushOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.expr;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.ne;

/**
 * MongoDB {@link ITeamBudgetStore}: one document per {@code (groupId, month)}
 * with {@code _id = groupId:month}.
 * <p>
 * Spend is a {@code $inc} upsert; the first-insert duplicate-key race is
 * retried as a plain update, as in {@code MongoToolQuotaStore}. A debit is one
 * {@code findOneAndUpdate} whose filter carries the "cap stays at or above
 * spend" predicate, so concurrent debits on any node cannot both pass. A TTL
 * index on {@code expiresAt} is the retention.
 */
@ApplicationScoped
@DefaultBean
public class MongoTeamBudgetStore implements ITeamBudgetStore {

    static final String COLLECTION = "team_budget_months";

    private static final String F_GROUP = "groupId";
    private static final String F_MONTH = "month";
    private static final String F_SPENT = "spentUsd";
    private static final String F_ADJ = "adjustmentUsd";
    private static final String F_TRANSFERS = "appliedTransferIds";
    private static final String F_HISTORY = "adjustments";
    private static final String F_EXPIRES = "expiresAt";

    private final MongoCollection<Document> months;

    @Inject
    public MongoTeamBudgetStore(MongoDatabase database) {
        this.months = database.getCollection(COLLECTION);
        this.months.createIndex(Indexes.ascending(F_GROUP, F_MONTH), new IndexOptions().name("idx_team_budget_group_month").background(true));
        this.months.createIndex(Indexes.ascending(F_EXPIRES),
                new IndexOptions().name("idx_team_budget_ttl").expireAfter(0L, TimeUnit.SECONDS).background(true));
    }

    static String id(String groupId, String month) {
        return groupId + ":" + month;
    }

    @Override
    public double addSpend(String groupId, String month, double usd, Instant expiresAt) {
        Bson filter = eq("_id", id(groupId, month));
        Bson update = Updates.combine(Updates.inc(F_SPENT, usd), initialFields(groupId, month, expiresAt, false));
        try {
            Document after;
            try {
                after = months.findOneAndUpdate(filter, update, new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
            } catch (MongoException e) {
                if (ErrorCategory.fromErrorCode(e.getCode()) != ErrorCategory.DUPLICATE_KEY) {
                    throw e;
                }
                // Two callers created the first document of the month at once.
                after = months.findOneAndUpdate(filter, update, new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
            }
            return number(after, F_SPENT);
        } catch (MongoException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not record team spend", e);
        }
    }

    /** $setOnInsert for everything except the field the caller increments. */
    private static Bson initialFields(String groupId, String month, Instant expiresAt, boolean withSpent) {
        List<Bson> fields = new ArrayList<>();
        fields.add(Updates.setOnInsert(F_GROUP, groupId));
        fields.add(Updates.setOnInsert(F_MONTH, month));
        fields.add(Updates.setOnInsert(F_EXPIRES, Date.from(expiresAt)));
        fields.add(Updates.setOnInsert(F_ADJ, 0.0));
        fields.add(Updates.setOnInsert(F_TRANSFERS, List.of()));
        fields.add(Updates.setOnInsert(F_HISTORY, List.of()));
        if (withSpent) {
            fields.add(Updates.setOnInsert(F_SPENT, 0.0));
        }
        return Updates.combine(fields);
    }

    @Override
    public Optional<TeamBudgetMonth> read(String groupId, String month) {
        try {
            Document found = months.find(eq("_id", id(groupId, month))).first();
            return Optional.ofNullable(found).map(MongoTeamBudgetStore::toMonth);
        } catch (MongoException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not read team budget", e);
        }
    }

    @Override
    public List<TeamBudgetMonth> readMany(Collection<String> groupIds, String month) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        try {
            List<String> ids = groupIds.stream().map(g -> id(g, month)).toList();
            List<TeamBudgetMonth> found = new ArrayList<>();
            for (Document document : months.find(in("_id", ids))) {
                found.add(toMonth(document));
            }
            return found;
        } catch (MongoException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not read team budgets", e);
        }
    }

    @Override
    public List<TeamBudgetMonth> history(String groupId, int count) {
        try {
            List<TeamBudgetMonth> found = new ArrayList<>();
            for (Document document : months.find(eq(F_GROUP, groupId)).sort(Sorts.descending(F_MONTH)).limit(Math.max(1, count))) {
                found.add(toMonth(document));
            }
            return found;
        } catch (MongoException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not read team budget history", e);
        }
    }

    @Override
    public AdjustOutcome applyAdjustment(String groupId, String month, double deltaUsd, double baseUsd, TeamBudgetMonth.Adjustment entry,
                                         String transferId, Instant expiresAt) {
        String id = id(groupId, month);
        try {
            // The document must exist before a conditional update can match it.
            try {
                months.updateOne(eq("_id", id), initialFields(groupId, month, expiresAt, true), new UpdateOptions().upsert(true));
            } catch (MongoException e) {
                if (ErrorCategory.fromErrorCode(e.getCode()) != ErrorCategory.DUPLICATE_KEY) {
                    throw e;
                }
            }
            List<Bson> conditions = new ArrayList<>();
            conditions.add(eq("_id", id));
            if (transferId != null) {
                conditions.add(ne(F_TRANSFERS, transferId));
            }
            if (deltaUsd < 0.0) {
                // base + adjustment + delta >= spent: a cap never drops below the spend.
                conditions.add(expr(new Document("$gte", List.of(new Document("$add", List.of(baseUsd, "$" + F_ADJ, deltaUsd)), "$" + F_SPENT))));
            }
            List<Bson> updates = new ArrayList<>();
            updates.add(Updates.inc(F_ADJ, deltaUsd));
            updates.add(Updates.pushEach(F_HISTORY, List.of(toDocument(entry)), new PushOptions().slice(-MAX_ADJUSTMENTS)));
            if (transferId != null) {
                updates.add(Updates.pushEach(F_TRANSFERS, List.of(transferId), new PushOptions().slice(-MAX_TRANSFER_IDS)));
            }
            Document applied = months.findOneAndUpdate(and(conditions), Updates.combine(updates),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (applied != null) {
                return AdjustOutcome.APPLIED;
            }
            Document current = months.find(eq("_id", id)).first();
            if (transferId != null && current != null) {
                List<?> done = current.getList(F_TRANSFERS, Object.class, List.of());
                if (done.contains(transferId)) {
                    return AdjustOutcome.DUPLICATE;
                }
            }
            return AdjustOutcome.REFUSED;
        } catch (MongoException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not adjust the team budget", e);
        }
    }

    @Override
    public int deleteExpired(Instant now) {
        try {
            return (int) months.deleteMany(lt(F_EXPIRES, Date.from(now))).getDeletedCount();
        } catch (MongoException e) {
            throw new TeamBudgetAccountingUnavailableException("Could not delete expired team budgets", e);
        }
    }

    private static Document toDocument(TeamBudgetMonth.Adjustment a) {
        return new Document("at", Date.from(a.at())).append("actor", a.actor()).append("kind", a.kind()).append("deltaUsd", a.deltaUsd())
                .append("capAfterUsd", a.capAfterUsd()).append("reason", a.reason()).append("counterparty", a.counterparty())
                .append("transferId", a.transferId());
    }

    private static TeamBudgetMonth toMonth(Document d) {
        List<TeamBudgetMonth.Adjustment> history = new ArrayList<>();
        for (Document a : d.getList(F_HISTORY, Document.class, List.of())) {
            Date at = a.getDate("at");
            history.add(new TeamBudgetMonth.Adjustment(at == null ? null : at.toInstant(), a.getString("actor"), a.getString("kind"),
                    number(a, "deltaUsd"), number(a, "capAfterUsd"), a.getString("reason"), a.getString("counterparty"), a.getString("transferId")));
        }
        return new TeamBudgetMonth(d.getString(F_GROUP), d.getString(F_MONTH), number(d, F_SPENT), number(d, F_ADJ),
                d.getList(F_TRANSFERS, String.class, List.of()), history);
    }

    private static double number(Document d, String field) {
        Object value = d.get(field);
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
