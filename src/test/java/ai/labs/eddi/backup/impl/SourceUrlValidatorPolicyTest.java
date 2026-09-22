/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.impl.SourceUrlValidator.SyncSourcePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The part of {@link SourceUrlValidator} an operator can configure.
 * <p>
 * {@link SourceUrlValidatorTest} covers the strict default. This covers what a
 * deployment may relax and, just as importantly, what it may not: the rules are
 * off by default, each one has to be turned off deliberately, and turning one
 * off does not turn the others off with it.
 */
@DisplayName("SourceUrlValidator — configurable policy")
class SourceUrlValidatorPolicyTest {

    private static final String PUBLIC_HTTP = "http://example.com:7070";
    private static final String PRIVATE_HTTPS = "https://10.0.0.5:7443";

    @Nested
    @DisplayName("requireHttps")
    class RequireHttps {

        @Test
        @DisplayName("off — a plain http source between trusted instances is accepted")
        void httpAcceptedWhenNotRequired() {
            assertDoesNotThrow(() -> SourceUrlValidator.validate(PUBLIC_HTTP,
                    new SyncSourcePolicy(false, false, Set.of())));
        }

        @Test
        @DisplayName("on — http is refused, and the refusal names the setting")
        void httpRefusedByDefault() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate(PUBLIC_HTTP, SyncSourcePolicy.STRICT));

            assertTrue(ex.getMessage().contains(SourceUrlValidator.REQUIRE_HTTPS_PROPERTY),
                    "an operator has to be told which setting would allow this, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("off does not also allow a private address")
        void relaxingHttpsDoesNotRelaxAddresses() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://10.0.0.5:7070",
                            new SyncSourcePolicy(false, false, Set.of())));

            assertTrue(ex.getMessage().contains(SourceUrlValidator.ALLOW_PRIVATE_PROPERTY),
                    "expected the address refusal, got: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("allowPrivateTargets")
    class AllowPrivateTargets {

        @Test
        @DisplayName("on — an instance on the internal network is accepted")
        void privateAcceptedWhenAllowed() {
            assertDoesNotThrow(() -> SourceUrlValidator.validate(PRIVATE_HTTPS,
                    new SyncSourcePolicy(true, true, Set.of())));
        }

        @Test
        @DisplayName("on — loopback is accepted too, which is what a one-host test setup needs")
        void loopbackAcceptedWhenAllowed() {
            assertDoesNotThrow(() -> SourceUrlValidator.validate("https://localhost:7443",
                    new SyncSourcePolicy(true, true, Set.of())));
        }

        @Test
        @DisplayName("on does not also allow http")
        void relaxingAddressesDoesNotRelaxHttps() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://10.0.0.5:7070",
                            new SyncSourcePolicy(true, true, Set.of())));

            assertTrue(ex.getMessage().contains(SourceUrlValidator.REQUIRE_HTTPS_PROPERTY),
                    "expected the HTTPS refusal, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("on — a host that does not resolve is still refused")
        void unresolvableStillRefused() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("https://this-host-does-not-exist.invalid:7443",
                            new SyncSourcePolicy(true, true, Set.of())));

            assertTrue(ex.getMessage().contains("could not be resolved"), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("allowedSources")
    class AllowedSources {

        @Test
        @DisplayName("an exact origin is accepted although both other rules refuse it")
        void namedOriginOverridesBoth() {
            assertDoesNotThrow(() -> SourceUrlValidator.validate("http://10.0.0.5:7070/backup/import",
                    new SyncSourcePolicy(true, false, Set.of("http://10.0.0.5:7070"))));
        }

        @Test
        @DisplayName("a different port is a different origin and is still refused")
        void portIsPartOfTheOrigin() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://10.0.0.5:9999",
                            new SyncSourcePolicy(true, false, Set.of("http://10.0.0.5:7070"))));
        }

        @Test
        @DisplayName("a different scheme is a different origin and is still refused")
        void schemeIsPartOfTheOrigin() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://10.0.0.5:7070",
                            new SyncSourcePolicy(true, false, Set.of("https://10.0.0.5:7070"))));
        }

        @Test
        @DisplayName("case and surrounding whitespace do not decide whether an origin matches")
        void originComparisonIsNormalized() {
            assertDoesNotThrow(() -> SourceUrlValidator.validate("https://Staging.Example.COM",
                    new SyncSourcePolicy(true, false, Set.of("  HTTPS://staging.example.com  "))));
        }
    }

    @Nested
    @DisplayName("parseAllowedSources")
    class ParseAllowedSources {

        @Test
        @DisplayName("reduces each entry to its origin, so a pasted URL still matches")
        void reducesToOrigin() {
            assertEquals(Set.of("https://staging.example.com:7443"),
                    SourceUrlValidator.parseAllowedSources("https://staging.example.com:7443/backup/export?x=1"));
        }

        @Test
        @DisplayName("takes a list, and ignores blanks")
        void parsesList() {
            assertEquals(Set.of("https://a.example.com", "http://b.example.com:8080"),
                    SourceUrlValidator.parseAllowedSources(" https://a.example.com , , http://b.example.com:8080 "));
        }

        @Test
        @DisplayName("drops an entry that names no host rather than failing startup")
        void dropsUnusableEntry() {
            // Fails closed: the URL it would have allowed is simply refused.
            assertEquals(Set.of("https://good.example.com"),
                    SourceUrlValidator.parseAllowedSources("not-a-url, https://good.example.com"));
        }

        @Test
        @DisplayName("empty configuration means no exceptions at all")
        void emptyConfiguration() {
            assertEquals(Set.of(), SourceUrlValidator.parseAllowedSources(null));
            assertEquals(Set.of(), SourceUrlValidator.parseAllowedSources("   "));
        }
    }

    @Nested
    @DisplayName("originOf")
    class OriginOf {

        @Test
        @DisplayName("keeps scheme, host and port, and nothing else")
        void keepsOnlyTheOrigin() {
            assertEquals("https://example.com:7443",
                    SourceUrlValidator.originOf(URI.create("https://user@example.com:7443/a/b?c=d#e")));
        }

        @Test
        @DisplayName("omits the port when the URL states none")
        void omitsAbsentPort() {
            assertEquals("https://example.com", SourceUrlValidator.originOf(URI.create("https://example.com")));
        }

        @Test
        @DisplayName("answers empty for something that is not an origin, which matches nothing")
        void emptyForNonOrigin() {
            assertEquals("", SourceUrlValidator.originOf(URI.create("/just/a/path")));
            assertEquals("", SourceUrlValidator.originOf(null));
        }
    }

    @Test
    @DisplayName("no policy at all is the strict one, never the permissive one")
    void nullPolicyIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> SourceUrlValidator.validate(PUBLIC_HTTP, (SyncSourcePolicy) null));
        assertThrows(IllegalArgumentException.class, () -> SourceUrlValidator.validate(PRIVATE_HTTPS, (SyncSourcePolicy) null));
    }
}
