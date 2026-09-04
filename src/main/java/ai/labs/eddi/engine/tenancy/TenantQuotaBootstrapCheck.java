/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Startup check shared by the DB-backed quota stores.
 *
 * @since 6.0.0
 */
final class TenantQuotaBootstrapCheck {

    private static final Logger LOGGER = Logger.getLogger(TenantQuotaBootstrapCheck.class);

    private TenantQuotaBootstrapCheck() {
    }

    /**
     * Tell the operator when {@code eddi.tenant.quota.*} no longer describes what
     * is enforced.
     * <p>
     * Both DB-backed stores bootstrap the default tenant's quota insert-only
     * ({@code $setOnInsert} / {@code ON CONFLICT DO NOTHING}), so the properties
     * take effect exactly once — on the first start against an empty database. An
     * operator who later enables quotas or raises a limit by environment variable
     * and redeploys changes nothing, and nothing used to say so: the stored row
     * silently won and the config surface was effectively write-once. (The
     * in-memory store, by contrast, always reflects the properties, so the same
     * configuration behaved differently per backend.)
     * <p>
     * Overwriting the stored row instead is not an option — it would clobber limits
     * set through {@code PUT /administration/quotas/&#123;id&#125;} on every
     * restart.
     *
     * @param stored
     *            the quota already in the database, or null if none
     * @param configured
     *            what {@code eddi.tenant.quota.*} asks for
     * @return whether a warning was emitted — so a test can assert the condition
     *         without scraping the log
     */
    static boolean warnIfStoredQuotaDiffersFromConfig(TenantQuota stored, TenantQuota configured) {
        if (stored == null || configured == null || stored.equals(configured)) {
            return false;
        }
        LOGGER.warnf("Stored quota for tenant '%s' differs from the configured eddi.tenant.quota.* properties; "
                + "the stored values win. stored=%s configured=%s — use PUT /administration/quotas/%s "
                + "if the properties are what you meant.",
                sanitize(configured.tenantId()), stored, configured, sanitize(configured.tenantId()));
        return true;
    }
}
