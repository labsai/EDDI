/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.serialization.DocumentBuilder;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared Testcontainers base for MongoDB adapter integration tests.
 * <p>
 * Provides a single {@link MongoDBContainer} (mongo:6.0) shared across all
 * subclasses. Each test class gets its own {@link MongoDatabase} via
 * {@link #getDatabase()}.
 *
 * @since 6.0.0
 */
@Testcontainers
public abstract class MongoTestBase {

    private static final String DB_NAME = "eddi_test";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    protected static MongoClient mongoClient;
    protected static MongoDatabase database;
    protected static ObjectMapper objectMapper;
    protected static IJsonSerialization jsonSerialization;
    protected static IDocumentBuilder documentBuilder;

    @BeforeAll
    static void initMongo() {
        mongoClient = MongoClients.create(MONGO.getConnectionString());
        database = mongoClient.getDatabase(DB_NAME);

        // Configure ObjectMapper to match Quarkus production settings
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        SerializationCustomizer.configureObjectMapper(objectMapper, false);

        jsonSerialization = new JsonSerialization(objectMapper);
        documentBuilder = new DocumentBuilder(jsonSerialization);
    }

    @AfterAll
    static void closeMongo() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    protected static MongoDatabase getDatabase() {
        return database;
    }

    /**
     * Drop all documents from the named collections for test isolation.
     */
    protected static void dropCollections(String... collectionNames) {
        for (String name : collectionNames) {
            database.getCollection(name).drop();
        }
    }
}
