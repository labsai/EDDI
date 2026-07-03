/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.jackson.ObjectMapperCustomizer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;

@Singleton
public class SerializationCustomizer implements ObjectMapperCustomizer {
    private final Boolean prettyPrint;

    @Inject
    public SerializationCustomizer(@ConfigProperty(name = "json.prettyPrint") Boolean prettyPrint) {
        this.prettyPrint = prettyPrint;
    }

    @Override
    public void customize(ObjectMapper objectMapper) {
        configureObjectMapper(objectMapper, prettyPrint);
    }

    public static ObjectMapper configureObjectMapper(ObjectMapper objectMapper, Boolean prettyPrint) {
        objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(INDENT_OUTPUT, prettyPrint);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // java.time support (Instant etc.) as ISO-8601 strings — the JSONB-backed
        // Postgres resource storage serializes snapshots through this mapper, and
        // HITL bookmarks carry an Instant (hitlPausedAt). Without the module a
        // non-null Instant fails serialization ("Java 8 date/time type not
        // supported"), which would break HITL pause persistence on Postgres.
        // Mirrors the BSON mapper in PersistenceModule so both backends agree.
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
