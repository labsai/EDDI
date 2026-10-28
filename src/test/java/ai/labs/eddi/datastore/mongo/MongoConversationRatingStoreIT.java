/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.ratings.ConversationRatingStoreContract;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.mongo.ConversationRatingStore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConversationRatingStore} against a real MongoDB (Testcontainers): the
 * shared {@link ConversationRatingStoreContract} plus the indexes.
 */
@DisplayName("ConversationRatingStore (MongoDB)")
class MongoConversationRatingStoreIT extends MongoTestBase implements ConversationRatingStoreContract {

    private static final String COLLECTION = "conversationratings";

    private IConversationRatingStore store;

    @BeforeEach
    void setUp() {
        dropCollections(COLLECTION);
        store = new ConversationRatingStore(getDatabase());
    }

    @Override
    public IConversationRatingStore store() {
        return store;
    }

    @Test
    void createsTheUniqueKeyAndQueryIndexes() {
        List<Document> indexes = getDatabase().getCollection(COLLECTION).listIndexes().into(new ArrayList<>());
        List<String> names = indexes.stream().map(index -> index.getString("name")).toList();
        assertTrue(
                names.containsAll(
                        List.of("idx_rating_key", "idx_rating_agent_created", "idx_rating_agent_version", "idx_rating_user", "idx_rating_attention")),
                names.toString());
        Document key = indexes.stream().filter(index -> "idx_rating_key".equals(index.getString("name"))).findFirst().orElseThrow();
        assertTrue(key.getBoolean("unique", false));
    }
}
