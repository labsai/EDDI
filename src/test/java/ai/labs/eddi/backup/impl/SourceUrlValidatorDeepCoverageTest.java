/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SourceUrlValidator Deep Branch Coverage Tests")
class SourceUrlValidatorDeepCoverageTest {

    @Nested
    @DisplayName("validate() — null/empty checks")
    class NullEmptyTests {

        @Test
        @DisplayName("null URL throws")
        void nullUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate(null, true));
        }

        @Test
        @DisplayName("empty URL throws")
        void emptyUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("", true));
        }

        @Test
        @DisplayName("blank URL throws")
        void blankUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("   ", true));
        }
    }

    @Nested
    @DisplayName("validate() — scheme checks")
    class SchemeTests {

        @Test
        @DisplayName("no scheme throws")
        void noScheme() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("justhost.com", true));
            assertTrue(ex.getMessage().contains("scheme"));
        }

        @Test
        @DisplayName("HTTPS required in production — HTTP rejected")
        void httpRejectedInProd() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://public-server.example.com", false));
            assertTrue(ex.getMessage().contains("HTTPS"));
        }

        @Test
        @DisplayName("ftp scheme rejected")
        void ftpRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("ftp://files.example.com/data", true));
        }

        @Test
        @DisplayName("HTTPS allowed in production")
        void httpsAllowedInProd() throws Exception {
            // This resolves to a non-private IP (or fails DNS which is also an error)
            // The validator should not throw on scheme or host format
            // It may throw on DNS resolution in test env, but that's the private IP check
            try {
                SourceUrlValidator.validate("https://public-api.example.com", false);
            } catch (IllegalArgumentException e) {
                // Only acceptable if it's a DNS resolution error, not a scheme error
                assertTrue(e.getMessage().contains("could not be resolved") ||
                        e.getMessage().contains("private"),
                        "Should only fail on DNS or private IP, not scheme");
            }
        }

        @Test
        @DisplayName("HTTP allowed when allowHttp is true")
        void httpAllowedInDev() throws Exception {
            try {
                SourceUrlValidator.validate("http://public-api.example.com", true);
            } catch (IllegalArgumentException e) {
                // DNS resolution error is OK in test environment
                assertTrue(e.getMessage().contains("could not be resolved") ||
                        e.getMessage().contains("private"));
            }
        }
    }

    @Nested
    @DisplayName("validate() — host checks")
    class HostTests {

        @Test
        @DisplayName("no host throws")
        void noHost() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://", true));
        }

        @Test
        @DisplayName("localhost blocked")
        void localhostBlocked() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://localhost:8080/api", true));
        }

        @Test
        @DisplayName("127.0.0.1 blocked")
        void loopback127Blocked() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://127.0.0.1:8080/api", true));
        }

        @Test
        @DisplayName("127.x.x.x blocked")
        void loopback127RangeBlocked() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://127.1.2.3:8080/api", true));
        }

        @Test
        @DisplayName("::1 IPv6 loopback blocked")
        void ipv6LoopbackBlocked() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://[::1]:8080/api", true));
        }

        @Test
        @DisplayName("private IP 10.x.x.x blocked")
        void privateIp10Blocked() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://10.0.0.1:8080/api", true));
        }

        @Test
        @DisplayName("private IP 192.168.x.x blocked")
        void privateIp192Blocked() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://192.168.1.1:8080/api", true));
        }

        @Test
        @DisplayName("private IP 172.16.x.x blocked")
        void privateIp172Blocked() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://172.16.0.1:8080/api", true));
        }

        @Test
        @DisplayName("unresolvable host throws (DNS fail-closed)")
        void unresolvableHost() {
            // This will fail DNS resolution and should throw
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate(
                            "http://this-host-definitely-does-not-exist-xyz123.invalid", true));
        }
    }
}
