/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        return SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
    }
}
