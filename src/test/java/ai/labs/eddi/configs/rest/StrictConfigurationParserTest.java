/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both write surfaces must answer the same payload the same way.
 * <p>
 * The strictness used to live inside a JAX-RS {@code ReaderInterceptor}, which
 * only fires on a real inbound HTTP body. MCP's {@code create_resource} calls
 * the very same REST stores in-process with its own lenient parse, so the same
 * document had opposite outcomes: REST returned
 * {@code 400 Unknown field 'setProperties' … Known fields: [setOnActions]},
 * while MCP returned {@code 201 created} and stored {@code {"setOnActions":[]}}
 * — an emptied configuration that then read back happily and did nothing at
 * runtime. {@code update_resource} went on to report {@code newVersion: 2} for
 * it. That path is reachable by every MCP client, the Platform Operator agent
 * included.
 */
@DisplayName("configuration writes are strict on every surface")
class StrictConfigurationParserTest {

    private final StrictConfigurationParser parser = new StrictConfigurationParser(new ObjectMapper());

    /** The exact payload from the sweep: a plausible but undeclared field name. */
    private static final String UNKNOWN_FIELD_PAYLOAD = "{\"setProperties\":[{\"name\":\"x\",\"valueString\":\"y\"}]}";

    @Test
    @DisplayName("an undeclared field is rejected, not dropped")
    void unknownFieldIsRejected() {
        var rejected = assertThrows(BadRequestException.class,
                () -> parser.parse(UNKNOWN_FIELD_PAYLOAD, PropertySetterConfiguration.class));

        String body = String.valueOf(rejected.getResponse().getEntity());
        assertTrue(body.contains("setProperties"), "the message must name the offending field: " + body);
        assertTrue(body.contains("setOnActions"), "the message must list the legal fields: " + body);
    }

    @Test
    @DisplayName("rejectUnknownFields agrees with parse — one implementation, two entry points")
    void byteEntryPointAgrees() {
        assertThrows(BadRequestException.class,
                () -> parser.rejectUnknownFields(UNKNOWN_FIELD_PAYLOAD.getBytes(), PropertySetterConfiguration.class));
    }

    @Test
    @DisplayName("a valid document parses")
    void validDocumentParses() {
        var config = assertDoesNotThrow(
                () -> parser.parse("{\"setOnActions\":[]}", PropertySetterConfiguration.class));

        assertNotNull(config);
    }

    @Test
    @DisplayName("malformed JSON is left to the caller's existing error path")
    void malformedJsonIsNotThisClassesBusiness() {
        // parse() propagates the IOException; rejectUnknownFields() swallows it so the
        // regular message-body reader still produces the response it always has.
        assertThrows(Exception.class, () -> parser.parse("{not json", PropertySetterConfiguration.class));
        assertDoesNotThrow(() -> parser.rejectUnknownFields("{not json".getBytes(), PropertySetterConfiguration.class));
    }
}
