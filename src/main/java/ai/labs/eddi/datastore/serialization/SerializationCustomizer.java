/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.jackson.ObjectMapperCustomizer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Instant;

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

        // REST-ONLY. Emit java.time.Instant as an ISO-8601 string instead of the
        // fractional epoch SECONDS that quarkus.jackson.write-dates-as-timestamps=true
        // produces via JavaTimeModule. Clients call new Date(value), which expects
        // MILLIS, so ~1.7e9 rendered as a 1970 date on every timestamp in the UI.
        //
        // This deliberately lives here and NOT in configureObjectMapper: that static is
        // the shared recipe, and the @PersistenceMapper mapper is built from it.
        // Storage
        // must keep the numeric shape — MongoScheduleStore normalizes dates to
        // epoch-millis for range queries, GroupConversationStore sorts server-side on a
        // persisted Instant (mixed numeric/string rows would interleave wrongly in both
        // Mongo and Postgres), and existing rows were written numerically. See commit
        // dc117cddc, which reverted a broader version of this change for breaking
        // findDueSchedules.
        //
        // configOverride lives in MapperConfig._configOverrides, not the
        // SerializationFeature bitmask, so it is order-independent with respect to when
        // Quarkus applies write-dates-as-timestamps: JSR310FormattedSerializerBase
        // .createContextual consults the per-type override first and only falls back to
        // the feature flag when none exists. Deserialization is unaffected —
        // InstantDeserializer dispatches on the JSON token type, not the shape hint —
        // so
        // numeric values written before this change still parse.
        //
        // Scoped to Instant only: java.util.Date already emits epoch millis and its
        // consumers are correct today, so widening this would break working paths.
        objectMapper.configOverride(Instant.class).setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING));
    }

    public static ObjectMapper configureObjectMapper(ObjectMapper objectMapper, Boolean prettyPrint) {
        objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(INDENT_OUTPUT, prettyPrint);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // java.time support (Instant etc.) — the JSONB-backed Postgres resource
        // storage serializes snapshots through this mapper, and HITL bookmarks carry
        // an Instant (hitlPausedAt). Without the module a non-null Instant fails
        // serialization ("Java 8 date/time type not supported"), which would break
        // HITL pause persistence on Postgres. The date FORMAT is intentionally left
        // at the default (numeric timestamps): MongoScheduleStore normalizes date
        // fields to epoch-millis for numeric range queries and expects numbers, so
        // this only ADDS Instant support without changing the wire format.
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }
}
