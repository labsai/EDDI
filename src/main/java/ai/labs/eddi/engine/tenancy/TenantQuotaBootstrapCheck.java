/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
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
     * <p>
     * <strong>Silent when the properties carry no intent.</strong> An operator who
     * set the quota through {@code PUT} and left {@code eddi.tenant.quota.*} at its
     * defaults has said nothing for the stored row to contradict, and warning them
     * on every restart forever is how the one warning that matters — an environment
     * variable that was changed and ignored — gets tuned out.
     * <p>
     * "Left at its defaults" is answered by {@link #quotaPropertiesWereSet()}, not
     * by comparing the values against {@link TenantQuota#unlimited}. The values
     * cannot answer it: {@code application.properties} ships
     * {@code eddi.tenant.quota.enabled=false} and every limit at -1, so an operator
     * who deliberately sets {@code EDDI_TENANT_QUOTA_ENABLED=false} to switch
     * enforcement off produces byte-for-byte the same {@code configured} object as
     * one who touched nothing — and that is precisely the case where a stored
     * {@code enabled=true} row keeps enforcing against their explicit instruction,
     * which is the one warning this check exists to emit. Both conditions are
     * required, so the value comparison still guards against a config source that
     * reports provenance we cannot read.
     * <p>
     * This is provenance on the <em>property</em>, not on the row. Telling a
     * config-sourced row from a REST-sourced one still needs a {@code source}
     * column in both DB-backed stores.
     *
     * @param stored
     *            the quota already in the database, or null if none
     * @param configured
     *            what {@code eddi.tenant.quota.*} asks for
     * @return whether a warning was emitted — so a test can assert the condition
     *         without scraping the log
     */
    static boolean warnIfStoredQuotaDiffersFromConfig(TenantQuota stored, TenantQuota configured) {
        return warnIfStoredQuotaDiffersFromConfig(stored, configured, quotaPropertiesWereSet());
    }

    /**
     * Same check with the property provenance supplied rather than resolved, so the
     * decision can be exercised without a running configuration.
     *
     * @param stored
     *            the quota already in the database, or null if none
     * @param configured
     *            what {@code eddi.tenant.quota.*} asks for
     * @param propertiesWereSet
     *            whether the deployment supplied any {@code eddi.tenant.quota.*}
     *            property itself, rather than inheriting the shipped defaults
     * @return whether a warning was emitted
     */
    static boolean warnIfStoredQuotaDiffersFromConfig(TenantQuota stored, TenantQuota configured, boolean propertiesWereSet) {
        if (stored == null || configured == null || stored.equals(configured)) {
            return false;
        }
        if (!propertiesWereSet && configured.equals(TenantQuota.unlimited(configured.tenantId()))) {
            LOGGER.infof("Tenant '%s' has a stored quota and eddi.tenant.quota.* is at its defaults, so the stored "
                    + "values are what is enforced. stored=%s", sanitize(configured.tenantId()), stored);
            return false;
        }
        LOGGER.warnf("Stored quota for tenant '%s' differs from the configured eddi.tenant.quota.* properties; "
                + "the stored values win. stored=%s configured=%s — use PUT /administration/quotas/%s "
                + "if the properties are what you meant.",
                sanitize(configured.tenantId()), stored, configured, sanitize(configured.tenantId()));
        return true;
    }

    /** Every property the bootstrap insert reads. */
    private static final String[] QUOTA_PROPERTIES = {
            "eddi.tenant.quota.enabled",
            "eddi.tenant.quota.max-conversations-per-day",
            "eddi.tenant.quota.max-agents-per-tenant",
            "eddi.tenant.quota.max-api-calls-per-minute",
            "eddi.tenant.quota.max-monthly-cost-usd"};

    /**
     * Ordinal of the {@code application.properties} EDDI itself ships. Anything
     * above it — an external {@code config/application.properties} (260), a
     * {@code .env} (295), an environment variable (300), a system property (400) —
     * came from the deployment.
     */
    private static final int BUNDLED_PROPERTIES_ORDINAL = 250;

    /**
     * Whether the deployment supplied any {@code eddi.tenant.quota.*} property of
     * its own.
     * <p>
     * Reads the config source each value actually came from rather than its value,
     * because the shipped defaults and a deliberate "turn enforcement off" are the
     * same five values.
     *
     * @return true if at least one quota property came from a source that outranks
     *         the bundled {@code application.properties}; false if none did, and
     *         also if the configuration cannot be read at all — an unanswerable
     *         question must not turn into a warning on every restart, which is the
     *         noise this check was tuned to avoid
     */
    static boolean quotaPropertiesWereSet() {
        try {
            return quotaPropertiesWereSet(ConfigProvider.getConfig());
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not determine where eddi.tenant.quota.* came from: %s", e.getMessage());
            return false;
        }
    }

    /**
     * The ordinal comparison itself, against a configuration handed in rather than
     * looked up.
     * <p>
     * Split out so the boundary can be pinned deterministically: asserting it
     * through {@link ConfigProvider} means asserting about the machine the test
     * runs on, and a developer or CI runner that exports
     * {@code EDDI_TENANT_QUOTA_*} (plausible on a host that also runs the
     * docker-compose stack from a {@code .env}) supplies a value at ordinal 300 and
     * flips the answer.
     *
     * @param config
     *            the configuration to interrogate
     * @return true if at least one quota property came from a source that outranks
     *         the bundled {@code application.properties}
     */
    static boolean quotaPropertiesWereSet(Config config) {
        for (String property : QUOTA_PROPERTIES) {
            ConfigValue value = config.getConfigValue(property);
            if (value != null && value.getValue() != null && value.getSourceOrdinal() > BUNDLED_PROPERTIES_ORDINAL) {
                return true;
            }
        }
        return false;
    }
}
