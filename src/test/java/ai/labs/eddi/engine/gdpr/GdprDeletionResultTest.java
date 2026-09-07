/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code complete()} is the single field a DPO acts on: false means some of the
 * subject's data may still exist and the Art. 17 request must not be filed as
 * fulfilled. Two things can quietly break that.
 * <p>
 * It is a derived method rather than a record component, so Jackson left it out
 * of the REST entity entirely while {@code McpGdprTools} put it into the MCP
 * payload by hand — the same result reported in two shapes, and a client
 * written against the MCP JSON reading null from the REST one. And
 * {@code failedSteps} arriving absent (a deserialized or hand-built result)
 * must become an empty list rather than a null the caller dereferences.
 */
@DisplayName("GdprDeletionResult")
class GdprDeletionResultTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    @DisplayName("an absent failedSteps list is normalised to empty, and the result reads as complete")
    void absentFailedStepsBecomesAnEmptyList() {
        var result = new GdprDeletionResult("user-1", 1, 2, 3, 4, 5,
                0, 0, 0, 0, 0, 0, null, COMPLETED_AT);

        assertNotNull(result.failedSteps(), "a null here is dereferenced by every caller that reports the outcome");
        assertTrue(result.failedSteps().isEmpty());
        assertTrue(result.complete());
    }

    @Test
    @DisplayName("a supplied failedSteps list is defensively copied, so a later mutation cannot flip complete()")
    void suppliedFailedStepsIsCopied() {
        var steps = new ArrayList<>(List.of("userMemories"));
        var result = new GdprDeletionResult("user-1", 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, steps, COMPLETED_AT);

        steps.clear();

        assertEquals(List.of("userMemories"), result.failedSteps());
        assertFalse(result.complete());
        assertThrows(UnsupportedOperationException.class, () -> result.failedSteps().add("attachments"));
    }

    /**
     * The compatibility constructor is what pre-cascade callers use; it must report
     * a complete erasure rather than an unknown one.
     */
    @Test
    @DisplayName("the seven-argument constructor reports a complete cascade")
    void compatibilityConstructorIsComplete() {
        var result = new GdprDeletionResult("user-1", 5, 3, 2, 10, 15, COMPLETED_AT);

        assertTrue(result.failedSteps().isEmpty());
        assertTrue(result.complete());
        assertEquals(5, result.memoriesDeleted());
        assertEquals(COMPLETED_AT, result.completedAt());
    }

    /**
     * The regression the {@code @JsonProperty} annotation fixes: without it the
     * REST entity carried no {@code complete} field at all, while the MCP tool put
     * one in — so the two surfaces disagreed about the only field that decides
     * whether an erasure may be reported as fulfilled.
     */
    @Test
    @DisplayName("complete() is serialised into the REST entity, matching the MCP payload")
    void completeIsSerialised() throws Exception {
        var failed = new GdprDeletionResult("user-1", 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, List.of("attachments"), COMPLETED_AT);

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(failed);

        assertTrue(json.contains("\"complete\":false"),
                "a client reading the REST bundle must see the same verdict the MCP tool reports: " + json);
    }

    /**
     * And it must stay read-only: {@code complete} is derived, so the canonical
     * record constructor has no component for it and a payload carrying one would
     * otherwise fail to deserialize.
     */
    @Test
    @DisplayName("complete is read-only, so a round-tripped payload still deserializes")
    void completeIsReadOnly() throws Exception {
        assertEquals(JsonProperty.Access.READ_ONLY,
                GdprDeletionResult.class.getMethod("complete").getAnnotation(JsonProperty.class).access());

        var mapper = new ObjectMapper().findAndRegisterModules();
        var original = new GdprDeletionResult("user-1", 1, 2, 3, 4, 5,
                0, 0, 0, 0, 0, 0, List.of("attachments"), COMPLETED_AT);

        var roundTripped = mapper.readValue(mapper.writeValueAsString(original), GdprDeletionResult.class);

        assertEquals(List.of("attachments"), roundTripped.failedSteps());
        assertFalse(roundTripped.complete());
    }
}
