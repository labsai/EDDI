/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import org.bson.Document;

import java.io.IOException;
import java.util.Map;

/**
 * Converts between database documents and the project's model classes.
 * <p>
 * {@link #build} maps a raw document map onto a typed model on read, and
 * {@link #toDocument} goes the other way on write. Sits between the storage
 * implementations and the configuration models so persistence code never
 * hand-rolls that mapping.
 */
public interface IDocumentBuilder {
    <T> T build(Map<?, ?> doc, Class<T> type) throws IOException;

    String toString(Object document) throws IOException;

    Document toDocument(Object obj) throws IOException;
}
