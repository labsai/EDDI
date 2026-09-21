/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.schema;

import com.fasterxml.jackson.core.JsonProcessingException;

public interface IJsonSchemaCreator {

    /**
     * The JSON schema for a configuration class.
     * <p>
     * Narrowed from {@code throws Exception}: the only checked failure is Jackson
     * failing to serialize the generated schema, and the blanket declaration forced
     * every caller through {@code sneakyThrow}.
     */
    String generateSchema(Class<?> clazz) throws JsonProcessingException;
}
