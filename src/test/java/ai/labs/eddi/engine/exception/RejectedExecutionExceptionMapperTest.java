/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class RejectedExecutionExceptionMapperTest {

    private final RejectedExecutionExceptionMapper mapper = new RejectedExecutionExceptionMapper();

    @Test
    void toResponse_returns503() {
        var ex = new RejectedExecutionException("Capacity exceeded");
        Response response = mapper.toResponse(ex);
        assertEquals(503, response.getStatus());
    }

    @Test
    void toResponse_containsRetryAfterHeader() {
        var ex = new RejectedExecutionException("too busy");
        Response response = mapper.toResponse(ex);
        assertEquals("5", response.getHeaderString("Retry-After"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toResponse_containsErrorBody() {
        var ex = new RejectedExecutionException("Queue full");
        Response response = mapper.toResponse(ex);
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals("capacity_exceeded", body.get("error"));
        assertEquals("Queue full", body.get("message"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toResponse_nullMessage_usesDefault() {
        var ex = new RejectedExecutionException((String) null);
        Response response = mapper.toResponse(ex);
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals("Service temporarily unavailable", body.get("message"));
    }

    @Test
    void toResponse_contentTypeIsJson() {
        var ex = new RejectedExecutionException("test");
        Response response = mapper.toResponse(ex);
        assertTrue(response.getMediaType().toString().contains("application/json"));
    }
}
