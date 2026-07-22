/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Selects the {@code ObjectMapper} used for <strong>storage</strong> rather
 * than for HTTP responses.
 *
 * <p>
 * The two must not be the same instance. The CDI/REST mapper deliberately emits
 * {@code java.time.Instant} as an ISO-8601 string so clients render timestamps
 * correctly, but the storage format has to stay numeric: several stores write
 * date fields as epoch-millis for range queries, sort server-side on persisted
 * timestamps, and read existing rows that were written numerically. Changing
 * the stored shape would silently reorder query results and strand old
 * documents rather than failing loudly.
 * </p>
 *
 * @see SerializationCustomizer
 */
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, METHOD, FIELD, PARAMETER})
public @interface PersistenceMapper {
}
