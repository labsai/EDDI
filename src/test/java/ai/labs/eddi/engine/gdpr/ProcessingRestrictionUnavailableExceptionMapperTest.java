/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The mapper shipped with no test at all, which mattered because it is the only
 * thing standing between an unreadable Art. 18 flag and a 403 saying the user's
 * processing is restricted when nobody restricted it.
 * <p>
 * It fires only on the synchronous entry points — {@code say} and
 * {@code sayStreaming} are resumed through an {@code AsyncResponse} and carry
 * explicit branches instead — so this test pins the shape those branches have
 * to reproduce. See
 * {@code RestAgentEngineTest.gdprRestrictionStatusUnavailable}.
 */
@DisplayName("ProcessingRestrictionUnavailableExceptionMapper")
class ProcessingRestrictionUnavailableExceptionMapperTest {

    private final ProcessingRestrictionUnavailableExceptionMapper mapper = new ProcessingRestrictionUnavailableExceptionMapper();

    @Test
    @DisplayName("maps to 503 with a retryable, machine-readable body — not the 403 the fail-closed path used to answer")
    void mapsToServiceUnavailable() {
        var exception = new ProcessingRestrictionUnavailableException(
                "Cannot determine processing-restriction status right now; the request was not processed",
                new RuntimeException("connection refused"));

        Response response = mapper.toResponse(exception);

        assertEquals(503, response.getStatus(), "403 would be a false legal statement about the user");
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        assertEquals("5", response.getHeaderString("Retry-After"), "the flag is unreadable now, not forever");
        assertEquals(Map.of("error", "restriction_status_unavailable",
                "message", "Cannot determine processing-restriction status right now; the request was not processed"),
                response.getEntity());
    }

    /**
     * {@code Map.of} throws {@link NullPointerException} on a null value, so a
     * thrower that supplies no message would make this mapper throw — and a mapper
     * that throws produces exactly the opaque 500 the class exists to replace. The
     * guard matters more on the {@code AsyncResponse} branch in
     * {@code RestAgentEngine.sayInternal}: an exception raised inside a catch
     * clause is not seen by the sibling catches, so the response would never be
     * resumed and the request would hang to its timeout.
     * <p>
     * Unreachable today — the exception's only constructor takes
     * {@code (String, Throwable)} and its single call site passes a literal — which
     * is why it is pinned here rather than left to the day a second thrower is
     * added.
     */
    @Test
    @DisplayName("a null message falls back to a fixed text instead of throwing out of the mapper")
    void nullMessageFallsBackToAFixedText() {
        var exception = new ProcessingRestrictionUnavailableException(null, new RuntimeException("connection refused"));

        Response response = mapper.toResponse(exception);

        assertEquals(503, response.getStatus());
        assertEquals(Map.of("error", "restriction_status_unavailable",
                "message", "Processing-restriction status unavailable"), response.getEntity());
        assertEquals("Processing-restriction status unavailable",
                ProcessingRestrictionUnavailableExceptionMapper.messageOf(exception),
                "the two AsyncResponse branches reproduce this body through the accessor, so it has to agree");
    }
}
