/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy.rest;

import ai.labs.eddi.engine.exception.RejectedExecutionExceptionMapper;
import ai.labs.eddi.engine.tenancy.QuotaAccountingUnavailableException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The synchronous half of the honest-503 contract.
 * <p>
 * {@code QuotaAccountingUnavailableException} extends
 * {@link RejectedExecutionException} so that every surface already answering
 * 503 for backpressure answers 503 for a quota-store outage too. That got the
 * status right everywhere and the <em>error code</em> right only on the two
 * {@code AsyncResponse} endpoints that catch it explicitly:
 * {@code POST /agents/&#123;environment&#125;/&#123;agentId&#125;} lets the
 * exception reach a mapper, and the nearest one was
 * {@link RejectedExecutionExceptionMapper} — which says
 * {@code capacity_exceeded}. Nothing is saturated during a quota-store outage,
 * and {@code docs/metrics.md} plus the dashboard panel both promise
 * {@code quota_accounting_unavailable} on that path.
 */
class QuotaAccountingUnavailableExceptionMapperTest {

    private final QuotaAccountingUnavailableExceptionMapper mapper = new QuotaAccountingUnavailableExceptionMapper();

    @Test
    @DisplayName("503 with the quota_accounting_unavailable code and a short Retry-After")
    void mapsToServiceUnavailableWithTheHonestCode() {
        Response response = mapper.toResponse(
                new QuotaAccountingUnavailableException("Quota accounting unavailable — denying request for safety"));

        assertEquals(503, response.getStatus());
        assertEquals("5", response.getHeaderString("Retry-After"),
                "an outage is retryable in seconds; the 60s of an over-quota denial would be the wrong signal");
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        assertEquals(Map.of("error", "quota_accounting_unavailable",
                "message", "Quota accounting unavailable — denying request for safety"),
                response.getEntity());
    }

    /**
     * The regression itself: the exception IS a {@link RejectedExecutionException},
     * so without this mapper JAX-RS resolved it to the backpressure one and
     * answered the wrong code with the right status.
     */
    @Test
    @DisplayName("the code differs from the backpressure mapper it would otherwise fall through to")
    void doesNotReportItAsCapacityExceeded() {
        var exception = new QuotaAccountingUnavailableException("Quota accounting unavailable — denying request for safety");
        assertInstanceOf(RejectedExecutionException.class, exception,
                "this is why a dedicated mapper is needed at all");

        Object viaBackpressureMapper = new RejectedExecutionExceptionMapper().toResponse(exception).getEntity();
        assertEquals(Map.of("error", "capacity_exceeded",
                "message", "Quota accounting unavailable — denying request for safety"), viaBackpressureMapper);
        assertNotEquals(viaBackpressureMapper, mapper.toResponse(exception).getEntity(),
                "a client keyed on the documented error code must not see capacity_exceeded for a store outage");
    }

    @Test
    @DisplayName("a null message still produces a usable body")
    void nullMessageFallsBackToAFixedText() {
        Response response = mapper.toResponse(new QuotaAccountingUnavailableException(null));

        assertEquals(Map.of("error", "quota_accounting_unavailable",
                "message", "Quota accounting unavailable"), response.getEntity());
    }
}
