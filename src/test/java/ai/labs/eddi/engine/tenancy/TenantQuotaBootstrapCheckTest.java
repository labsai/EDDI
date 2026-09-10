/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        assertTrue(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(stored, configured, true),
                "an operator who enabled quotas by env var must be told the stored row wins");
    }

    @Test
    @DisplayName("no warning when the stored row already is what the properties ask for")
    void silentWhenTheyAgree() {
        var quota = new TenantQuota(TENANT_ID, 1000, -1, 60, -1, true);

        assertFalse(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(quota, quota, true));
    }

    @Test
    @DisplayName("no warning on a genuine first boot, where nothing was stored yet")
    void silentOnFirstBoot() {
        var configured = new TenantQuota(TENANT_ID, 1000, -1, 60, -1, true);

        assertFalse(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(null, configured, true),
                "the insert just applied the properties; there is nothing to warn about");
    }

    /**
     * Finding r16. An operator who set the quota through {@code PUT
     * /administration/quotas/{id}} and left {@code eddi.tenant.quota.*} at its
     * defaults has expressed nothing for the stored row to contradict — and used to
     * get this warning on every single restart, forever, which is how the one
     * warning that matters gets tuned out.
     */
    @Test
    @DisplayName("no warning when the properties are at their defaults — the operator asked for nothing")
    void silentWhenTheConfigCarriesNoIntent() {
        var storedViaRest = new TenantQuota(TENANT_ID, 5000, 20, 300, 100.0, true);
        var untouchedConfig = TenantQuota.unlimited(TENANT_ID);

        assertFalse(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(storedViaRest, untouchedConfig, false),
                "warning every restart about a quota nobody configured by property is pure noise");
    }

    /**
     * The case the r16 silence swallowed. {@code eddi.tenant.quota.enabled}
     * <em>ships</em> as false with every limit at -1, so an operator who
     * deliberately sets {@code EDDI_TENANT_QUOTA_ENABLED=false} to switch
     * enforcement off hands this check byte-for-byte the same {@code configured}
     * object as one who touched nothing — and a stored {@code enabled=true} row
     * then goes on enforcing against their explicit instruction, silently, with the
     * INFO line not even naming the PUT that would fix it.
     * <p>
     * Only the provenance of the property can separate the two, which is why the
     * decision takes it as an input rather than inferring it from the values.
     */
    @Test
    @DisplayName("an explicitly configured 'quotas off' still warns when the stored row enforces")
    void warnsWhenEnforcementWasExplicitlyTurnedOff() {
        var storedEnforcing = new TenantQuota(TENANT_ID, 5000, 20, 300, 100.0, true);
        var explicitlyDisabled = TenantQuota.unlimited(TENANT_ID);

        assertTrue(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(storedEnforcing, explicitlyDisabled, true),
                "the operator turned enforcement off by property and the stored row is still enforcing — say so");
    }

    /**
     * And the provenance itself: with nothing but the shipped
     * {@code application.properties} supplying these values, the deployment has
     * expressed no intent.
     * <p>
     * Asserted against a configuration handed in, not against
     * {@code ConfigProvider}. Reading the ambient one made this the only test in
     * the suite whose answer depended on the machine: a developer or CI runner that
     * exports {@code EDDI_TENANT_QUOTA_ENABLED} — plausible on a host that also
     * runs the docker-compose stack from a {@code .env} — supplies a value at
     * ordinal 300 and the "no intent" baseline fails for a reason that has nothing
     * to do with the code. Both sides of the 250 boundary are pinned here instead,
     * so lowering {@code BUNDLED_PROPERTIES_ORDINAL} fails the first case and
     * raising it fails the second.
     */
    @Test
    @DisplayName("only a source that outranks the bundled application.properties counts as intent")
    void onlyASourceAboveTheBundledOrdinalCountsAsExplicitConfiguration() {
        assertFalse(TenantQuotaBootstrapCheck.quotaPropertiesWereSet(configWith("false", 250)),
                "the shipped application.properties (ordinal 250) is not the deployment saying anything");
        assertTrue(TenantQuotaBootstrapCheck.quotaPropertiesWereSet(configWith("false", 300)),
                "an environment variable (ordinal 300) outranks the bundled application.properties (250) — "
                        + "and it carries the SAME value, which is exactly why provenance and not the value decides");
        assertFalse(TenantQuotaBootstrapCheck.quotaPropertiesWereSet(configWith(null, 400)),
                "a source that outranks the bundle but supplies no value has still said nothing");
    }

    /**
     * A {@link Config} that does not know the property at all answers {@code null}
     * rather than a {@link ConfigValue} carrying a null value, and the guard has to
     * survive that too — an NPE here would abort the store's bootstrap insert,
     * which runs before the deployment's first quota check.
     */
    @Test
    @DisplayName("a configuration that does not know the property at all is not intent either")
    void anAbsentConfigValueIsNotIntent() {
        var config = mock(Config.class);
        when(config.getConfigValue(anyString())).thenReturn(null);

        assertFalse(TenantQuotaBootstrapCheck.quotaPropertiesWereSet(config));
    }

    /**
     * The ambient entry point has to fail <em>silent</em>, not loud: an
     * unanswerable "where did this value come from?" must not turn into a warning
     * on every restart, which is precisely the noise the provenance check was added
     * to remove. Forced by swapping the MicroProfile resolver, because no ordinary
     * configuration can make {@code ConfigProvider.getConfig()} throw.
     */
    @Test
    @DisplayName("a configuration that cannot be read at all answers 'no intent' instead of propagating")
    void unreadableConfigurationIsTreatedAsNoIntent() {
        var original = ConfigProviderResolver.instance();
        ConfigProviderResolver.setInstance(new ThrowingConfigProviderResolver());
        try {
            assertFalse(TenantQuotaBootstrapCheck.quotaPropertiesWereSet(),
                    "an unanswerable question must not become a warning on every restart");
        } finally {
            ConfigProviderResolver.setInstance(original);
        }
    }

    /**
     * The one-argument overload is what both DB-backed stores actually call, and
     * the only thing it adds over the three-argument form is resolving the
     * provenance itself. So it is driven over the one pair whose verdict provenance
     * alone decides — a stored row that enforces against a {@code configured}
     * object byte-for-byte identical to the shipped defaults — with the ambient
     * configuration swapped underneath it, once on each side of the answer.
     * <p>
     * Both directions are needed. Pinned only where the properties carry intent, a
     * hard-wired {@code true} passes; pinned only where they do not, a hard-wired
     * {@code false} passes. Together nothing constant survives, and the delegation
     * is what is actually being asserted rather than merely executed.
     * <p>
     * The ambient configuration is replaced rather than read, because reading it
     * asserts about the machine: a developer or CI runner exporting
     * {@code EDDI_TENANT_QUOTA_ENABLED} — plausible on a host that also runs the
     * docker-compose stack from a {@code .env} — supplies a value at ordinal 300
     * and flips the "no intent" half for a reason that has nothing to do with the
     * code.
     */
    @Test
    @DisplayName("the store-facing overload resolves provenance itself — the same pair warns or stays silent by where the properties came from")
    void storeFacingOverloadResolvesProvenanceItself() {
        var storedEnforcing = new TenantQuota(TENANT_ID, 5000, 20, 300, 100.0, true);
        var indistinguishableFromTheShippedDefaults = TenantQuota.unlimited(TENANT_ID);

        // Precondition: this pair really is decided by provenance and nothing else,
        // so the two assertions below cannot both be satisfied by one constant.
        assertTrue(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(
                storedEnforcing, indistinguishableFromTheShippedDefaults, true));
        assertFalse(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(
                storedEnforcing, indistinguishableFromTheShippedDefaults, false));

        withAmbientConfig(configWith("false", 300), () -> assertTrue(
                TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(
                        storedEnforcing, indistinguishableFromTheShippedDefaults),
                "the deployment turned enforcement off by environment variable and the stored row is still "
                        + "enforcing — the overload has to consult that, not assume it"));

        withAmbientConfig(configWith("false", 250), () -> assertFalse(
                TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(
                        storedEnforcing, indistinguishableFromTheShippedDefaults),
                "the same two quotas, with nothing but the shipped application.properties behind them, are an "
                        + "operator who configured the quota by PUT — warning them on every restart forever is the "
                        + "noise this check was tuned to remove"));
    }

    /**
     * And the store-facing overload must survive the state a store reaches when it
     * could not build a configured quota at all: silent, rather than dereferencing
     * it. This runs before the deployment's first quota check, so an NPE here
     * aborts the bootstrap insert.
     */
    @Test
    @DisplayName("the store-facing overload stays silent with nothing to compare")
    void storeFacingOverloadIsSilentWithoutAConfiguredQuota() {
        var stored = new TenantQuota(TENANT_ID, 5000, 20, 300, 100.0, true);

        assertFalse(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(stored, null));
        assertFalse(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(null, null));
    }

    /**
     * Runs {@code assertion} with
     * {@link org.eclipse.microprofile.config.ConfigProvider} answering
     * {@code config}, and puts the real resolver back afterwards —
     * {@code setInstance} is process-wide, and a leaked one would change what every
     * later test in this fork considers configured.
     */
    private static void withAmbientConfig(Config config, Runnable assertion) {
        var original = ConfigProviderResolver.instance();
        ConfigProviderResolver.setInstance(new FixedConfigProviderResolver(config));
        try {
            assertion.run();
        } finally {
            ConfigProviderResolver.setInstance(original);
        }
    }

    /** A resolver that hands out one fixed configuration. */
    private static final class FixedConfigProviderResolver extends ConfigProviderResolver {

        private final Config config;

        private FixedConfigProviderResolver(Config config) {
            this.config = config;
        }

        @Override
        public Config getConfig() {
            return config;
        }

        @Override
        public Config getConfig(ClassLoader loader) {
            return config;
        }

        @Override
        public ConfigBuilder getBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerConfig(Config config, ClassLoader classLoader) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseConfig(Config config) {
            throw new UnsupportedOperationException();
        }
    }

    /** A resolver whose {@code getConfig} always fails. */
    private static final class ThrowingConfigProviderResolver extends ConfigProviderResolver {
        @Override
        public Config getConfig() {
            throw new IllegalStateException("no configuration available");
        }

        @Override
        public Config getConfig(ClassLoader loader) {
            throw new IllegalStateException("no configuration available");
        }

        @Override
        public ConfigBuilder getBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerConfig(Config config, ClassLoader classLoader) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseConfig(Config config) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A configuration in which every {@code eddi.tenant.quota.*} property resolves
     * to {@code value} from a source of the given ordinal.
     */
    private static Config configWith(String value, int ordinal) {
        var configValue = mock(ConfigValue.class);
        when(configValue.getValue()).thenReturn(value);
        when(configValue.getSourceOrdinal()).thenReturn(ordinal);
        var config = mock(Config.class);
        when(config.getConfigValue(anyString())).thenReturn(configValue);
        return config;
    }

    /**
     * The ambient-configuration entry point still has to reach the same decision —
     * a system property is ordinal 400, which outranks the bundle on any machine,
     * so this half is deterministic where the "no intent" baseline was not.
     */
    @Test
    @DisplayName("a system property outranks the bundled application.properties and counts as intent")
    void systemPropertyCountsAsExplicitConfiguration() {
        System.setProperty("eddi.tenant.quota.enabled", "false");
        try {
            assertTrue(TenantQuotaBootstrapCheck.quotaPropertiesWereSet(),
                    "a system property (ordinal 400) outranks the bundled application.properties (250)");
        } finally {
            System.clearProperty("eddi.tenant.quota.enabled");
        }
    }

    /**
     * And the case finding 17 was actually about still warns: the properties say
     * something, and the stored row is not it.
     */
    @Test
    @DisplayName("still warns when the properties say something the stored row ignores")
    void warnsWhenTheConfigCarriesIntent() {
        var stored = new TenantQuota(TENANT_ID, 5000, 20, 300, 100.0, true);
        var configured = new TenantQuota(TENANT_ID, 1000, -1, 60, -1, true);

        assertTrue(TenantQuotaBootstrapCheck.warnIfStoredQuotaDiffersFromConfig(stored, configured, false));
    }
}
