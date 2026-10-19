/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared assertion for the stores' refusal of a document carrying the
 * read-redaction marker ({@link RedactionMarkerGuard}).
 */
public final class RedactionMarkerAssertions {

    private RedactionMarkerAssertions() {
    }

    /**
     * Asserts {@code write} is refused with a 400 whose JSON body names
     * {@code field} and tells the caller what to do.
     */
    @SuppressWarnings("unchecked")
    public static void assertRefusedNaming(String field, Executable write) {
        BadRequestException refused = assertThrows(BadRequestException.class, write);
        assertEquals(400, refused.getResponse().getStatus());
        Map<String, Object> body = (Map<String, Object>) refused.getResponse().getEntity();
        List<String> fields = (List<String>) body.get("fields");
        assertTrue(fields.contains(field), () -> "expected " + field + " in " + fields);
        assertTrue(String.valueOf(body.get("message")).contains("${vault:"), () -> String.valueOf(body.get("message")));
    }
}
