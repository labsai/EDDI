/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgContextTest {

    static final OrgId ACME = OrgId.of("acme");
    static final OrgId GLOBEX = OrgId.of("globex");

    /** Records observer events. */
    static final class Recorder implements OrgContextObserver {
        final List<String> fallbacks = new ArrayList<>();
        final List<String> platformScopes = new ArrayList<>();

        @Override
        public void fallback(String site) {
            fallbacks.add(site);
        }

        @Override
        public void platformScope(String reason) {
            platformScopes.add(reason);
        }
    }

    @AfterEach
    void reset() {
        OrgContext.configure(OrgMode.SINGLE, OrgContextObserver.NONE);
        OrgContext.resetWarnings();
    }

    @Nested
    class SingleMode {

        @Test
        void unboundRequireFallsBackToDefaultAndIsCountedAtTheCallSite() {
            Recorder recorder = new Recorder();
            OrgContext.configure(OrgMode.SINGLE, recorder);

            assertEquals(OrgId.DEFAULT, OrgContext.require());
            assertEquals(OrgId.DEFAULT, OrgContext.require());

            assertEquals(2, recorder.fallbacks.size());
            assertTrue(recorder.fallbacks.get(0).startsWith(OrgContextTest.class.getName() + "$SingleMode#"), recorder.fallbacks.get(0));
        }

        @Test
        void boundDefaultIsNotAFallback() throws Exception {
            Recorder recorder = new Recorder();
            OrgContext.configure(OrgMode.SINGLE, recorder);

            assertEquals(OrgId.DEFAULT, OrgContext.callAs(OrgId.DEFAULT, OrgContext::require));
            assertTrue(recorder.fallbacks.isEmpty());
        }

        @Test
        void onlyTheDefaultOrgCanBeBound() {
            assertThrows(OrgBindingException.class, () -> OrgContext.runAs(ACME, () -> {
            }));
        }

        @Test
        void unboundStateReportsNothing() {
            assertEquals(Optional.empty(), OrgContext.current());
            assertFalse(OrgContext.isPlatform());
            assertEquals(0L, OrgContext.epoch());
        }

        @Test
        void aFallbackThroughOrgQuotaKeysNamesTheCallerNotTheHelper() {
            Recorder recorder = new Recorder();
            OrgContext.configure(OrgMode.SINGLE, recorder);

            new OrgQuotaKeys("default").current();

            assertTrue(recorder.fallbacks.get(0).endsWith("#aFallbackThroughOrgQuotaKeysNamesTheCallerNotTheHelper"),
                    recorder.fallbacks.get(0));
        }

        @Test
        void callSitesBeyondTheCapAreCountedAsOverflow() {
            Recorder recorder = new Recorder();
            OrgContext.configure(OrgMode.SINGLE, recorder);
            for (int i = 0; i < OrgContext.MAX_FALLBACK_SITES; i++) {
                OrgContext.warnedSites().add("filler#" + i);
            }

            OrgContext.require();

            assertEquals(List.of(OrgContext.OVERFLOW_TAG), recorder.fallbacks);
            assertEquals(OrgContext.MAX_FALLBACK_SITES, OrgContext.warnedSites().size());
        }
    }

    @Nested
    class MultiMode {

        @Test
        void unboundRequireFailsClosed() {
            OrgContext.configure(OrgMode.MULTI, null);
            OrgContextMissingException e = assertThrows(OrgContextMissingException.class, OrgContext::require);
            assertTrue(e.getMessage().startsWith("[SECURITY]"));
        }

        @Test
        void bindsAndUnbindsAroundTheWork() throws Exception {
            OrgContext.configure(OrgMode.MULTI, null);

            assertEquals(ACME, OrgContext.callAs(ACME, OrgContext::require));
            assertEquals(Optional.of(ACME), OrgContext.callAs(ACME, OrgContext::current));
            assertEquals(Optional.empty(), OrgContext.current());
        }

        @Test
        void rebindingTheSameOrgIsANoOpUnderTheSameEpoch() throws Exception {
            OrgContext.configure(OrgMode.MULTI, null);

            long[] epochs = OrgContext.callAs(ACME, () -> {
                long outer = OrgContext.epoch();
                long inner = OrgContext.callAs(ACME, OrgContext::epoch);
                return new long[]{outer, inner};
            });
            assertTrue(epochs[0] > 0);
            assertEquals(epochs[0], epochs[1]);
        }

        @Test
        void crossingOrgsThrowsAndTheMessageNamesNoOrg() {
            OrgContext.configure(OrgMode.MULTI, null);

            OrgBindingException e = assertThrows(OrgBindingException.class, () -> OrgContext.runAs(ACME, () -> OrgContext.runAs(GLOBEX, () -> {
            })));
            assertFalse(e.getMessage().contains("acme"));
            assertFalse(e.getMessage().contains("globex"));
        }

        @Test
        void everyBindingGetsAUniqueEpoch() throws Exception {
            OrgContext.configure(OrgMode.MULTI, null);

            long first = OrgContext.callAs(ACME, OrgContext::epoch);
            long second = OrgContext.callAs(ACME, OrgContext::epoch);
            assertNotEquals(first, second);
        }

        @Test
        void aNewThreadDoesNotInheritTheBinding() throws Exception {
            OrgContext.configure(OrgMode.MULTI, null);
            AtomicReference<Optional<OrgId>> seen = new AtomicReference<>();

            OrgContext.callAs(ACME, () -> {
                Thread t = Thread.ofVirtual().start(() -> seen.set(OrgContext.current()));
                t.join();
                return null;
            });
            assertEquals(Optional.empty(), seen.get());
        }

        @Test
        void checkedExceptionsPropagateUnchanged() {
            OrgContext.configure(OrgMode.MULTI, null);
            Exception boom = new Exception("boom");

            Exception thrown = assertThrows(Exception.class, () -> OrgContext.callAs(ACME, () -> {
                throw boom;
            }));
            assertEquals(boom, thrown);
        }
    }

    @Nested
    class Platform {

        @Test
        void requireInsidePlatformThrowsUntilANestedRunAs() throws Exception {
            OrgContext.configure(OrgMode.MULTI, null);

            OrgScope.platform("test:require", () -> {
                assertTrue(OrgContext.isPlatform());
                assertEquals(Optional.empty(), OrgContext.current());
                assertThrows(OrgContextMissingException.class, OrgContext::require);
                assertEquals(ACME, OrgContext.callAs(ACME, OrgContext::require));
                assertEquals(GLOBEX, OrgContext.callAs(GLOBEX, OrgContext::require));
                return null;
            });
        }

        @Test
        void orgMayEnterPlatformAndPlatformIsCountedOnceWhenNested() throws Exception {
            Recorder recorder = new Recorder();
            OrgContext.configure(OrgMode.MULTI, recorder);

            OrgContext.callAs(ACME, () -> OrgScope.platform("test:outer", () -> OrgScope.platform("test:inner", () -> {
                assertTrue(OrgContext.isPlatform());
                return null;
            })));
            assertEquals(List.of("test:outer"), recorder.platformScopes);
        }

        @Test
        void platformRequiresAReason() {
            assertThrows(IllegalArgumentException.class, () -> OrgScope.platform(" ", () -> null));
            assertThrows(IllegalArgumentException.class, () -> OrgScope.runPlatform(null, () -> {
            }));
        }

        @Test
        void runPlatformRethrowsRuntimeExceptions() {
            IllegalStateException boom = new IllegalStateException("boom");
            assertEquals(boom, assertThrows(IllegalStateException.class, () -> OrgScope.runPlatform("test:boom", () -> {
                throw boom;
            })));
        }

        @Test
        void platformHasItsOwnEpoch() throws Exception {
            long epoch = OrgScope.platform("test:epoch", OrgContext::epoch);
            assertTrue(epoch > 0);
            assertNull(OrgContext.bindingOrNull());
        }
    }
}
