/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrgConfigTest {

    @AfterEach
    void reset() {
        OrgContext.configure(OrgMode.SINGLE, OrgContextObserver.NONE);
        OrgContext.resetWarnings();
    }

    private static OrgResolution resolution(String name) {
        OrgResolution resolution = mock(OrgResolution.class);
        when(resolution.resolverName()).thenReturn(name);
        return resolution;
    }

    @Test
    void singleModeIsAppliedAndTheResolverIsSelectedEagerly() {
        OrgResolution resolution = resolution("single");
        OrgContext.configure(OrgMode.MULTI, null);

        new OrgConfig("single", resolution, new SimpleMeterRegistry()).apply();

        assertEquals(OrgMode.SINGLE, OrgContext.mode());
        verify(resolution).resolver();
    }

    @Test
    void multiModeIsRefusedInThisRelease() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new OrgConfig("multi", resolution("single"), new SimpleMeterRegistry()).apply());
        assertTrue(e.getMessage().contains("eddi.org.mode=multi is not available"));
    }

    @Test
    void anUnknownModeIsRefused() {
        assertThrows(IllegalStateException.class, () -> new OrgConfig("shared", resolution("single"), new SimpleMeterRegistry()).apply());
    }

    @Test
    void theClassLoadModeComesFromTheConfigurationAndDefaultsToSingle() {
        // the test classpath carries application.properties with eddi.org.mode=single
        assertEquals(OrgMode.SINGLE, OrgContext.initialMode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aResolverNameInAnotherCaseIsAccepted() {
        Instance<OrgResolver> resolvers = mock(Instance.class);
        when(resolvers.stream()).thenAnswer(i -> Stream.of(new SingleOrgResolver()));
        OrgResolution real = new OrgResolution(resolvers, mock(Instance.class), mock(Instance.class), " Single ", () -> false);
        OrgContext.configure(OrgMode.MULTI, null);

        new OrgConfig("single", real, new SimpleMeterRegistry()).apply();

        assertEquals(OrgMode.SINGLE, OrgContext.mode());
    }

    @Test
    void modeIsParsedCaseInsensitively() {
        assertEquals(OrgMode.SINGLE, OrgConfig.parseMode(" Single "));
        assertEquals(OrgMode.MULTI, OrgConfig.parseMode("MULTI"));
    }

    @Test
    void anyResolverOtherThanSingleIsRefusedInThisRelease() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new OrgConfig("single", resolution("keycloak-organizations"), new SimpleMeterRegistry()).apply());
        assertTrue(e.getMessage().contains("eddi.org.resolver=keycloak-organizations"));
    }

    @Test
    void fallbacksAndPlatformScopesAreCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OrgConfig("single", resolution("single"), registry).apply();

        OrgContext.require();
        OrgScope.runPlatform("test:metrics", () -> {
        });

        double fallbacks = registry.find(OrgConfig.FALLBACK_METRIC).counters().stream().mapToDouble(c -> c.count()).sum();
        assertEquals(1.0, fallbacks);
        assertEquals(1.0, registry.get(OrgConfig.PLATFORM_METRIC).tag("reason", "test:metrics").counter().count());
    }

    @Test
    void platformReasonTagsAreCapped() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var observer = new OrgConfig.MetricsObserver(registry);

        for (int i = 0; i < OrgConfig.MAX_REASON_TAGS + 5; i++) {
            observer.platformScope("reason-" + i);
        }

        assertEquals(OrgConfig.MAX_REASON_TAGS + 1, registry.find(OrgConfig.PLATFORM_METRIC).counters().size());
        assertEquals(5.0, registry.get(OrgConfig.PLATFORM_METRIC).tag("reason", OrgContext.OVERFLOW_TAG).counter().count());
    }
}
