/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import java.io.IOException;

/**
 * JSON serialization for model objects.
 * <p>
 * Thin, shared wrapper over the configured object mapper so serialization
 * settings are declared once rather than per call site. Used wherever a model
 * crosses a boundary as JSON — REST payloads, stored documents, and values
 * handed to external systems.
 */
public interface IJsonSerialization {
    String serialize(Object model) throws IOException;

    <T> T deserialize(String json) throws IOException;

    <T> T deserialize(String json, Class<T> type) throws IOException;
}
