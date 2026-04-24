/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.serialization.IDocumentBuilder;

/**
 * Factory for creating {@link IResourceStorage} instances.
 * <p>
 * This is the single injection point for database backend selection. The
 * default implementation
 * ({@link ai.labs.eddi.datastore.mongo.MongoResourceStorageFactory}) creates
 * MongoDB-backed storage. Alternative implementations (e.g., PostgreSQL) can be
 * activated via configuration.
 *
 * @see ai.labs.eddi.datastore.mongo.MongoResourceStorageFactory
 */
public interface IResourceStorageFactory {

    /**
     * Create a new resource storage instance for the given collection/table.
     *
     * @param collectionName
     *            the collection (MongoDB) or table (SQL) name
     * @param documentBuilder
     *            serializer/deserializer for documents
     * @param documentType
     *            the Java class of the stored document
     * @param indexes
     *            optional field names to index
     * @param <T>
     *            the document type
     * @return a new storage instance
     */
    <T> IResourceStorage<T> create(String collectionName, IDocumentBuilder documentBuilder, Class<T> documentType, String... indexes);
}
