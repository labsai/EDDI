/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.mongo;

import ai.labs.eddi.modules.llm.impl.IProviderCooldownStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of {@link IProviderCooldownStore}: one document per
 * provider quota in {@code provider_cooldowns}, keyed by the throttle key.
 */
@ApplicationScoped
@DefaultBean
public class MongoProviderCooldownStore implements IProviderCooldownStore {

    static final String COLLECTION = "provider_cooldowns";

    private static final String FIELD_ID = "_id";
    private static final String FIELD_UNTIL = "until";
    private static final String FIELD_DAILY = "daily";
    private static final String FIELD_OBSERVED_AT = "observedAt";

    private final MongoCollection<Document> cooldowns;

    @Inject
    public MongoProviderCooldownStore(MongoDatabase database) {
        this.cooldowns = database.getCollection(COLLECTION);
    }

    @Override
    public Optional<Cooldown> read(String throttleKey) {
        Document found = cooldowns.find(Filters.eq(FIELD_ID, throttleKey)).first();
        if (found == null || found.getDate(FIELD_UNTIL) == null) {
            return Optional.empty();
        }
        return Optional.of(new Cooldown(throttleKey, found.getDate(FIELD_UNTIL).toInstant(),
                Boolean.TRUE.equals(found.getBoolean(FIELD_DAILY))));
    }

    @Override
    public void extend(String throttleKey, Instant until, boolean daily) {
        Date later = Date.from(until);
        // A pipeline update, so both fields are judged against the row as it was:
        // only ever later, and the kind follows the time that wins.
        Document recordedUntil = new Document("$ifNull", List.of("$" + FIELD_UNTIL, new Date(0)));
        Document wins = new Document("$gt", List.of(later, recordedUntil));
        List<Bson> update = List.of(new Document("$set", new Document()
                .append(FIELD_DAILY, new Document("$cond", List.of(wins, daily, "$" + FIELD_DAILY)))
                .append(FIELD_UNTIL, new Document("$cond", List.of(wins, later, "$" + FIELD_UNTIL)))
                .append(FIELD_OBSERVED_AT, new Date())));
        cooldowns.updateOne(Filters.eq(FIELD_ID, throttleKey), update, new UpdateOptions().upsert(true));
    }
}
