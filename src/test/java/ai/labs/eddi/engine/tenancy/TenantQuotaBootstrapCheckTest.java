/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 17. Both DB-backed stores bootstrap the default tenant's quota
 * insert-only ({@code $setOnInsert} / {@code ON CONFLICT DO NOTHING}), so
 * {@code eddi.tenant.quota.*} takes effect exactly once — on the first start
 * against an empty database. An operator who later enables quotas or raises a
 * limit by environment variable and redeploys changes nothing, and nothing used
 * to say so: the stored row silently won and the configuration surface was
 * effectively write-once. (The in-memory store always reflects the properties,
 * so the same configuration behaved differently per backend.)
 */
class TenantQuotaBootstrapCheckTest {

    private static final String TENANT_ID = "default";

    @Test
    @DisplayName("a stored row that no longer matches the configured properties is reported")
    void warnsWhenTheStoredRowDivergesFromConfig() {
        var stored = new TenantQuota(TENANT_ID, -1, -1, -1, -1, false);
        var configured = new TenantQuota(TENANT_ID, 1000, -1, 60, -1, true);

        assertTrue(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(stored, configured),
                "an operator who enabled quotas by env var must be told the stored row wins");
    }

    @Test
    @DisplayName("no warning when the stored row already is what the properties ask for")
    void silentWhenTheyAgree() {
        var quota = new TenantQuota(TENANT_ID, 1000, -1, 60, -1, true);

        assertFalse(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(quota, quota));
    }

    @Test
    @DisplayName("no warning on a genuine first boot, where nothing was stored yet")
    void silentOnFirstBoot() {
        var configured = new TenantQuota(TENANT_ID, 1000, -1, 60, -1, true);

        assertFalse(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(null, configured),
                "the insert just applied the properties; there is nothing to warn about");
    }
}
