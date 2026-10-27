/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.engine.schedule.mongo.MongoScheduleStore;
import ai.labs.eddi.datastore.postgres.PostgresScheduleStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.datastore.postgres.PostgresConversationRatingStore;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.mongo.ConversationRatingStore;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class DataStoreProducersTest {

    private DataStoreProducers producers;
    private Instance<MongoScheduleStore> mongoInstance;
    private Instance<PostgresScheduleStore> postgresInstance;
    private MongoScheduleStore mongoStore;
    private PostgresScheduleStore postgresStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        producers = new DataStoreProducers();
        mongoInstance = mock(Instance.class);
        postgresInstance = mock(Instance.class);

        mongoStore = mock(MongoScheduleStore.class);
        postgresStore = mock(PostgresScheduleStore.class);

        when(mongoInstance.get()).thenReturn(mongoStore);
        when(postgresInstance.get()).thenReturn(postgresStore);
    }

    @Test
    void shouldProduceMongoStoreByDefault() {
        // Given
        producers.datastoreType = "mongodb";

        // When
        IScheduleStore store = producers.scheduleStore(mongoInstance, postgresInstance);

        // Then
        assertEquals(mongoStore, store);
        verify(mongoInstance).get();
        verify(postgresInstance, never()).get();
    }

    @Test
    void shouldProducePostgresStoreWhenConfigured() {
        // Given
        producers.datastoreType = "postgres";

        // When
        IScheduleStore store = producers.scheduleStore(mongoInstance, postgresInstance);

        // Then
        assertEquals(postgresStore, store);
        verify(postgresInstance).get();
        verify(mongoInstance, never()).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void conversationRatingStoreFollowsTheDatastoreType() {
        Instance<ConversationRatingStore> mongo = mock(Instance.class);
        Instance<PostgresConversationRatingStore> postgres = mock(Instance.class);
        ConversationRatingStore mongoRatings = mock(ConversationRatingStore.class);
        PostgresConversationRatingStore postgresRatings = mock(PostgresConversationRatingStore.class);
        when(mongo.get()).thenReturn(mongoRatings);
        when(postgres.get()).thenReturn(postgresRatings);

        producers.datastoreType = "mongodb";
        IConversationRatingStore onMongo = producers.conversationRatingStore(mongo, postgres);
        producers.datastoreType = "postgres";
        IConversationRatingStore onPostgres = producers.conversationRatingStore(mongo, postgres);

        assertEquals(mongoRatings, onMongo);
        assertEquals(postgresRatings, onPostgres);
    }
}
