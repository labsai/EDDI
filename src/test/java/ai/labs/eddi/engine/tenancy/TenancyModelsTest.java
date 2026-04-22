package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import ai.labs.eddi.engine.tenancy.rest.QuotaExceededExceptionMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenancyModelsTest {

    // --- QuotaCheckResult ---

    @Nested
    class QuotaCheckResultTests {

        @Test
        void ok_isAllowed() {
            assertTrue(QuotaCheckResult.OK.allowed());
            assertNull(QuotaCheckResult.OK.reason());
        }

        @Test
        void denied_notAllowed() {
            var result = QuotaCheckResult.denied("Limit exceeded");
            assertFalse(result.allowed());
            assertEquals("Limit exceeded", result.reason());
        }
    }

    // --- TenantQuota ---

    @Nested
    class TenantQuotaTests {

        @Test
        void construction() {
            var q = new TenantQuota("t1", 100, 10, 500, 50.0, true);
            assertEquals("t1", q.tenantId());
            assertEquals(100, q.maxConversationsPerDay());
            assertEquals(10, q.maxAgentsPerTenant());
            assertEquals(500, q.maxApiCallsPerMinute());
            assertEquals(50.0, q.maxMonthlyCostUsd());
            assertTrue(q.enabled());
        }

        @Test
        void disabled() {
            var q = new TenantQuota("t2", -1, -1, -1, -1.0, false);
            assertFalse(q.enabled());
            assertEquals(-1, q.maxConversationsPerDay());
        }
    }

    // --- UsageSnapshot ---

    @Nested
    class UsageSnapshotTests {

        @Test
        void construction() {
            var now = Instant.now();
            var month = YearMonth.now(ZoneOffset.UTC);
            var snap = new UsageSnapshot("t1", 5, 10, 3.5, now, now, month);

            assertEquals("t1", snap.tenantId());
            assertEquals(5, snap.conversationsToday());
            assertEquals(10, snap.apiCallsThisMinute());
            assertEquals(3.5, snap.monthlyCostUsd());
        }

        @Test
        void empty_allZeros() {
            var snap = UsageSnapshot.empty("test");
            assertEquals("test", snap.tenantId());
            assertEquals(0, snap.conversationsToday());
            assertEquals(0, snap.apiCallsThisMinute());
            assertEquals(0.0, snap.monthlyCostUsd());
            assertNotNull(snap.minuteWindowStart());
            assertNotNull(snap.dayStart());
            assertNotNull(snap.costMonth());
        }
    }

    // --- QuotaExceededException ---

    @Test
    void quotaExceededException_message() {
        var ex = new QuotaExceededException("Daily limit reached");
        assertEquals("Daily limit reached", ex.getMessage());
    }

    // --- QuotaExceededExceptionMapper ---

    @Nested
    class QuotaExceededMapperTests {

        private final QuotaExceededExceptionMapper mapper = new QuotaExceededExceptionMapper();

        @Test
        void toResponse_returns429() {
            var ex = new QuotaExceededException("Limit exceeded");
            Response response = mapper.toResponse(ex);
            assertEquals(429, response.getStatus());
        }

        @Test
        void toResponse_hasRetryAfter() {
            var ex = new QuotaExceededException("test");
            Response response = mapper.toResponse(ex);
            assertEquals("60", response.getHeaderString("Retry-After"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void toResponse_hasErrorBody() {
            var ex = new QuotaExceededException("Budget exceeded");
            Response response = mapper.toResponse(ex);
            Map<String, String> body = (Map<String, String>) response.getEntity();
            assertEquals("quota_exceeded", body.get("error"));
            assertEquals("Budget exceeded", body.get("message"));
        }

        @Test
        void toResponse_jsonContentType() {
            var ex = new QuotaExceededException("test");
            Response response = mapper.toResponse(ex);
            assertTrue(response.getMediaType().toString().contains("application/json"));
        }
    }
}
