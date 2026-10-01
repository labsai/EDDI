/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Date;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;

/**
 * MongoDB implementation of {@link IMigrationLogStore}. Annotated
 * {@code @DefaultBean} so PostgreSQL can override.
 */
@ApplicationScoped
@DefaultBean
public class MigrationLogStore implements IMigrationLogStore {
    private static final String COLLECTION_MIGRATION_LOG = "migrationlog";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_ENTRIES = "entries";
    private static final String FIELD_FINISHED = "finished";
    private static final String FIELD_TIMESTAMP = "timestamp";

    private final MongoDatabase database;
    private final MongoCollection<MigrationLog> collection;

    @Inject
    public MigrationLogStore(MongoDatabase database) {
        RuntimeUtilities.checkNotNull(database, "database");
        this.database = database;
        this.collection = database.getCollection(COLLECTION_MIGRATION_LOG, MigrationLog.class);
    }

    @Override
    public MigrationLog readMigrationLog(String name) {
        return collection.find(new Document(FIELD_NAME, name)).first();
    }

    @Override
    public void createMigrationLog(MigrationLog migrationLog) {
        collection.insertOne(migrationLog);
    }

    /**
     * The {@code entries} of the record named {@code name}, read as a plain
     * document: {@link MigrationLog} is a completion marker and has no such field.
     */
    @Override
    public List<String> readMigrationEntries(String name) {
        Document record = entriesCollection().find(eq(FIELD_NAME, name)).first();
        if (record == null) {
            return List.of();
        }
        List<String> entries = record.getList(FIELD_ENTRIES, String.class);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * Upserts the record, marked unfinished so it never reads as a completion
     * marker; an empty list deletes it.
     */
    @Override
    public void writeMigrationEntries(String name, List<String> entries) {
        if (entries.isEmpty()) {
            entriesCollection().deleteOne(eq(FIELD_NAME, name));
            return;
        }
        entriesCollection().updateOne(eq(FIELD_NAME, name),
                Updates.combine(Updates.set(FIELD_ENTRIES, entries), Updates.set(FIELD_FINISHED, false),
                        Updates.set(FIELD_TIMESTAMP, new Date())),
                new UpdateOptions().upsert(true));
    }

    private MongoCollection<Document> entriesCollection() {
        return database.getCollection(COLLECTION_MIGRATION_LOG);
    }
}
