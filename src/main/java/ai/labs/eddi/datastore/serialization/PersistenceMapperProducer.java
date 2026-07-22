/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Produces the {@link PersistenceMapper}-qualified {@code ObjectMapper}.
 *
 * <p>
 * Built from the same {@link SerializationCustomizer#configureObjectMapper}
 * recipe as the CDI/REST mapper, but <em>without</em> the REST-only
 * {@code Instant} format override that {@code customize()} applies. Everything
 * that affects how a document is shaped — null inclusion, unknown-property
 * tolerance, {@code JavaTimeModule} — is shared, so stored documents keep
 * exactly the shape they have today.
 * </p>
 */
@ApplicationScoped
public class PersistenceMapperProducer {

    @Produces
    @ApplicationScoped
    @PersistenceMapper
    public ObjectMapper persistenceMapper() {
        ObjectMapper mapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);

        // Redundant against today's Jackson default (WRITE_DATES_AS_TIMESTAMPS is
        // enabled out of the box), but stated explicitly on purpose: the numeric
        // storage format is a correctness requirement, not a preference. Stores write
        // epoch-millis for range queries and sort server-side on persisted timestamps,
        // so silently inheriting a flipped library default would reorder query results
        // rather than fail. This repo has already been bitten once by an implicit date
        // format (commit dc117cddc).
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        return mapper;
    }
}
