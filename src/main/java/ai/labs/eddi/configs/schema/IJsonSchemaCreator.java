/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.schema;

public interface IJsonSchemaCreator {
    String generateSchema(Class<?> clazz) throws Exception;
}
