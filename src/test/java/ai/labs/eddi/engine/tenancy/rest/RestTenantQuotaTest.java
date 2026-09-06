/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy.rest;

import ai.labs.eddi.engine.tenancy.InMemoryTenantQuotaStore;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the tenant quota REST API.
 */
class RestTenantQuotaTest {

    private static final String TENANT_ID = "default";
    private RestTenantQuota restTenantQuota;
    private InMemoryTenantQuotaStore quotaStore;
    private TenantQuotaService quotaService;

    @BeforeEach
    void setUp() {
        var meterRegistry = new SimpleMeterRegistry();
        var defaultQuota = TenantQuota.unlimited(TENANT_ID);
        quotaStore = new InMemoryTenantQuotaStore(defaultQuota);
        quotaService = new TenantQuotaService(quotaStore, meterRegistry, TENANT_ID);
        restTenantQuota = new RestTenantQuota(quotaStore, quotaService);
    }

    @Test
    void shouldReturnQuotaForTenant() {
        TenantQuota quota = restTenantQuota.getQuota(TENANT_ID);

        assertNotNull(quota);
        assertEquals(TENANT_ID, quota.tenantId());
        assertEquals(-1, quota.maxConversationsPerDay());
        assertFalse(quota.enabled());
    }

    @Test
    void shouldReturn404ForUnknownTenant() {
        assertThrows(NotFoundException.class, () -> restTenantQuota.getQuota("unknown-tenant"));
    }

    @Test
    void shouldUpdateQuota() {
        var update = new TenantQuota(TENANT_ID, 100, 10, 50, 500.0, true);

        try (Response response = restTenantQuota.updateQuota(TENANT_ID, update)) {
            assertEquals(200, response.getStatus());
        }

        TenantQuota stored = quotaStore.getQuota(TENANT_ID);
        assertEquals(100, stored.maxConversationsPerDay());
        assertEquals(10, stored.maxAgentsPerTenant());
        assertEquals(50, stored.maxApiCallsPerMinute());
        assertEquals(500.0, stored.maxMonthlyCostUsd());
        assertTrue(stored.enabled());
    }

    /**
     * Finding 21. {@code updateQuota} dereferenced the body with no null check and
     * applied no range check, so an empty body was a 500 rather than a 400, and
     * {@code maxConversationsPerDay=-5} was stored and — because every store reads
     * only {@code limit < 0} as unlimited — behaved as <em>unlimited</em>, the
     * opposite of what an admin typing a negative number means. A blank tenant id
     * created a row nothing could reach.
     */
    @Test
    void shouldRejectAnEmptyBodyWithBadRequestNot500() {
        assertThrows(BadRequestException.class, () -> restTenantQuota.updateQuota(TENANT_ID, null));
    }

    @Test
    void shouldRejectABlankTenantId() {
        var valid = new TenantQuota(" ", 100, 10, 50, 500.0, true);
        assertThrows(BadRequestException.class, () -> restTenantQuota.updateQuota(" ", valid));
    }

    /**
     * The other half of the same guard. A null path segment cannot arrive over
     * HTTP, but this bean is also called in-process, and a null tenant id reaching
     * the store would write a row no gate can ever read back — the quota would look
     * saved in the UI and enforce nothing.
     */
    @Test
    void shouldRejectANullTenantId() {
        var valid = new TenantQuota(TENANT_ID, 100, 10, 50, 500.0, true);
        assertThrows(BadRequestException.class, () -> restTenantQuota.updateQuota(null, valid));
        assertEquals(-1, quotaStore.getQuota(TENANT_ID).maxConversationsPerDay(),
                "a rejected request must not have written anything");
    }

    @Test
    void shouldRejectLimitsBelowTheUnlimitedSentinel() {
        assertThrows(BadRequestException.class,
                () -> restTenantQuota.updateQuota(TENANT_ID, new TenantQuota(TENANT_ID, -5, 10, 50, 500.0, true)));
        assertThrows(BadRequestException.class,
                () -> restTenantQuota.updateQuota(TENANT_ID, new TenantQuota(TENANT_ID, 100, -2, 50, 500.0, true)));
        assertThrows(BadRequestException.class,
                () -> restTenantQuota.updateQuota(TENANT_ID, new TenantQuota(TENANT_ID, 100, 10, -3, 500.0, true)));
        assertThrows(BadRequestException.class,
                () -> restTenantQuota.updateQuota(TENANT_ID, new TenantQuota(TENANT_ID, 100, 10, 50, -2.0, true)));
        assertThrows(BadRequestException.class,
                () -> restTenantQuota.updateQuota(TENANT_ID, new TenantQuota(TENANT_ID, 100, 10, 50, Double.NaN, true)));

        assertEquals(-1, quotaStore.getQuota(TENANT_ID).maxConversationsPerDay(), "nothing may have been stored");
    }

    /**
     * The gap the {@code value < -1} predicate left open. {@code -0.5} is not less
     * than {@code -1}, so it passed a check whose own rejection message says "-1
     * (unlimited) or >= 0" — and every store reads any negative as unlimited, so
     * the admin got exactly the silent no-budget this validation exists to prevent.
     * Only the {@code double} limit can reach it; no integer sits strictly between
     * -1 and 0.
     */
    @Test
    void shouldRejectAFractionalCostLimitBetweenTheSentinelAndZero() {
        assertThrows(BadRequestException.class,
                () -> restTenantQuota.updateQuota(TENANT_ID, new TenantQuota(TENANT_ID, 100, 10, 50, -0.5, true)));
        assertThrows(BadRequestException.class,
                () -> restTenantQuota.updateQuota(TENANT_ID, new TenantQuota(TENANT_ID, 100, 10, 50, -0.000001, true)));

        assertEquals(-1, quotaStore.getQuota(TENANT_ID).maxConversationsPerDay(), "nothing may have been stored");
    }

    @Test
    void shouldAcceptTheUnlimitedSentinelAndZero() {
        try (Response response = restTenantQuota.updateQuota(TENANT_ID, new TenantQuota(TENANT_ID, -1, 0, -1, 0.0, true))) {
            assertEquals(200, response.getStatus());
        }
        assertEquals(0, quotaStore.getQuota(TENANT_ID).maxAgentsPerTenant());
    }

    /**
     * The enforcement gates read quota configuration through a short-TTL cache, so
     * an admin's change has to drop the stale entry or it would not apply on this
     * node until the TTL expired.
     */
    @Test
    void shouldMakeAnUpdatedQuotaEffectiveImmediately() {
        // Warm the cache with the permissive default.
        assertTrue(quotaService.acquireApiCallSlot().allowed());

        try (Response response = restTenantQuota.updateQuota(TENANT_ID, new TenantQuota(TENANT_ID, -1, -1, 1, -1, true))) {
            assertEquals(200, response.getStatus());
        }

        assertTrue(quotaService.acquireApiCallSlot().allowed());
        assertFalse(quotaService.acquireApiCallSlot().allowed(), "the tightened limit must apply to the very next turn");
    }

    @Test
    void shouldReturnUsage() {
        // Enable quota so counters are tracked
        quotaStore.setQuota(new TenantQuota(TENANT_ID, 100, -1, 100, -1, true));
        quotaService.acquireConversationSlot();
        quotaService.acquireApiCallSlot();

        var usage = restTenantQuota.getUsage(TENANT_ID);

        assertEquals(TENANT_ID, usage.tenantId());
        assertEquals(1, usage.conversationsToday());
        assertEquals(1, usage.apiCallsThisMinute());
    }

    @Test
    void shouldResetUsage() {
        // Enable quota so counters are tracked
        quotaStore.setQuota(new TenantQuota(TENANT_ID, 100, -1, 100, -1, true));
        quotaService.acquireConversationSlot();
        quotaService.acquireApiCallSlot();

        try (Response response = restTenantQuota.resetUsage(TENANT_ID)) {
            assertEquals(200, response.getStatus());
        }

        var usage = quotaService.getUsage(TENANT_ID);
        assertEquals(0, usage.conversationsToday());
        assertEquals(0, usage.apiCallsThisMinute());
    }

    @Test
    void shouldListAllQuotas() {
        var quotas = restTenantQuota.listQuotas();

        assertNotNull(quotas);
        assertEquals(1, quotas.size());
        assertEquals(TENANT_ID, quotas.getFirst().tenantId());
    }

    /**
     * A budget that is accepted, stored and shown in the UI but never enforced is
     * the kind of thing an operator only discovers from the bill.
     * {@code TenantQuotaService.checkCostBudget} reads an accumulator only
     * {@code recordCost} writes, and {@code recordCost} has no production caller,
     * so the recorded cost is always 0 and the gate always allows. The value is
     * still stored — it is configuration a later release will enforce — so the WARN
     * is the only thing that tells anyone, which makes it worth a test rather than
     * a hope.
     * <p>
     * Captured through a JUL handler on the class logger, the same way
     * {@code ConnectionStartupGuardTest} asserts its startup findings.
     * {@code src/test/resources/logging.properties} silences the whole
     * {@code ai.labs.eddi} namespace, so the level has to be raised for the records
     * to reach a handler at all.
     */
    @Test
    void shouldWarnThatAMonthlyCostBudgetIsNotEnforcedYet() {
        var records = new ArrayList<String>();
        Logger restLogger = Logger.getLogger(RestTenantQuota.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                // warnf keeps its arguments out of the message, so both halves are kept.
                records.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
            }

            @Override
            public void flush() {
                // nothing is buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        Level previousLevel = restLogger.getLevel();
        boolean previousUseParentHandlers = restLogger.getUseParentHandlers();
        restLogger.setLevel(Level.ALL);
        restLogger.setUseParentHandlers(false);
        restLogger.addHandler(handler);
        try {
            // Enabled, with a real (non-sentinel) budget — the combination that is
            // silently unenforceable.
            try (Response response = restTenantQuota.updateQuota(TENANT_ID,
                    new TenantQuota(TENANT_ID, -1, -1, -1, 250.0, true))) {
                assertEquals(200, response.getStatus());
            }
            assertTrue(records.stream().anyMatch(r -> r.contains("maxMonthlyCostUsd") && r.contains("NOT enforced")),
                    "an admin who sets a budget must be told nothing records cost against it, was: " + records);
            assertEquals(250.0, quotaStore.getQuota(TENANT_ID).maxMonthlyCostUsd(),
                    "the value is still stored — it is configuration, not a rejected input");

            records.clear();
            // The sentinel means "no budget", so there is nothing to warn about.
            try (Response response = restTenantQuota.updateQuota(TENANT_ID,
                    new TenantQuota(TENANT_ID, -1, -1, -1, -1, true))) {
                assertEquals(200, response.getStatus());
            }
            assertTrue(records.stream().noneMatch(r -> r.contains("NOT enforced")),
                    "an unlimited budget must not produce the warning, was: " + records);

            records.clear();
            // A budget on a tenant whose enforcement is switched off entirely. Nothing
            // about it is enforced, budget included, so the specific "cost is not
            // recorded" warning would be noise — and noise is how the case above stops
            // being read.
            try (Response response = restTenantQuota.updateQuota(TENANT_ID,
                    new TenantQuota(TENANT_ID, -1, -1, -1, 250.0, false))) {
                assertEquals(200, response.getStatus());
            }
            assertTrue(records.stream().noneMatch(r -> r.contains("NOT enforced")),
                    "a disabled tenant must not produce the warning, was: " + records);
            assertEquals(250.0, quotaStore.getQuota(TENANT_ID).maxMonthlyCostUsd(),
                    "and the value is still stored, exactly as in the enabled case");
        } finally {
            restLogger.removeHandler(handler);
            restLogger.setLevel(previousLevel);
            restLogger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    /**
     * The warning above asserts that the limit <em>is</em> stored and will apply
     * once cost recording is wired. It used to be emitted before {@code setQuota}
     * was called at all, so a store that then threw left that claim standing in the
     * log with nothing written behind it — and that one line is the only signal
     * this feature has, so an operator reading it goes looking for a row that does
     * not exist.
     */
    @Test
    void shouldNotClaimAStoredBudgetWhenTheQuotaWriteFailed() {
        var failingService = mock(TenantQuotaService.class);
        doThrow(new IllegalStateException("quota store unreachable")).when(failingService).setQuota(any());
        var restWithFailingStore = new RestTenantQuota(quotaStore, failingService);

        var records = new ArrayList<String>();
        Logger restLogger = Logger.getLogger(RestTenantQuota.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
            }

            @Override
            public void flush() {
                // nothing is buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        Level previousLevel = restLogger.getLevel();
        boolean previousUseParentHandlers = restLogger.getUseParentHandlers();
        restLogger.setLevel(Level.ALL);
        restLogger.setUseParentHandlers(false);
        restLogger.addHandler(handler);
        try {
            // Enabled, with a real budget: the exact combination that warns, so the
            // only thing keeping the line out of the log is the failed write.
            assertThrows(IllegalStateException.class, () -> restWithFailingStore.updateQuota(TENANT_ID,
                    new TenantQuota(TENANT_ID, -1, -1, -1, 250.0, true)));

            assertTrue(records.stream().noneMatch(r -> r.contains("NOT enforced")),
                    "the warning asserts a stored limit, so it must not outlive a write that never landed, was: " + records);
        } finally {
            restLogger.removeHandler(handler);
            restLogger.setLevel(previousLevel);
            restLogger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    void shouldReturnExceptionMapperResponse() {
        var mapper = new QuotaExceededExceptionMapper();
        var exception = new QuotaExceededException("Test limit exceeded");

        try (Response response = mapper.toResponse(exception)) {
            assertEquals(429, response.getStatus());
            assertEquals("60", response.getHeaderString("Retry-After"));
        }
    }
}
