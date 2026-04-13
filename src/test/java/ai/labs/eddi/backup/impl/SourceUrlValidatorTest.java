package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SourceUrlValidator} covering scheme validation,
 * loopback/private IP detection, and dev/prod mode differences.
 */
class SourceUrlValidatorTest {

    @Nested
    @DisplayName("Scheme validation")
    class SchemeValidation {

        @Test
        @DisplayName("should accept HTTPS URL in production mode")
        void acceptsHttpsInProd() {
            assertDoesNotThrow(() -> SourceUrlValidator.validate("https://example.com", false));
        }

        @Test
        @DisplayName("should reject HTTP URL in production mode")
        void rejectsHttpInProd() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://example.com", false));
            assertTrue(ex.getMessage().contains("HTTPS"));
        }

        @Test
        @DisplayName("should accept HTTP URL in dev mode")
        void acceptsHttpInDev() {
            assertDoesNotThrow(() -> SourceUrlValidator.validate("http://example.com", true));
        }

        @Test
        @DisplayName("should reject non-HTTP schemes")
        void rejectsNonHttpSchemes() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("ftp://example.com", true));
        }

        @Test
        @DisplayName("should reject URL without scheme")
        void rejectsNoScheme() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("example.com", true));
        }
    }

    @Nested
    @DisplayName("Loopback detection")
    class LoopbackDetection {

        @Test
        @DisplayName("should reject localhost")
        void rejectsLocalhost() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://localhost:8080", true));
            assertTrue(ex.getMessage().contains("localhost"));
        }

        @Test
        @DisplayName("should reject 127.0.0.1")
        void rejectsLoopbackIp() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://127.0.0.1:8080", true));
        }

        @Test
        @DisplayName("should reject ::1")
        void rejectsIpv6Loopback() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://[::1]:8080", true));
        }
    }

    @Nested
    @DisplayName("Private IP detection")
    class PrivateIpDetection {

        @Test
        @DisplayName("should reject 10.x.x.x addresses")
        void rejects10Network() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://10.0.0.5:8080", true));
        }

        @Test
        @DisplayName("should reject 192.168.x.x addresses")
        void rejects192Network() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://192.168.1.100:8080", true));
        }

        @Test
        @DisplayName("should reject 172.16.x.x addresses")
        void rejects172Network() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://172.16.0.1:8080", true));
        }

        @Test
        @DisplayName("should reject unresolvable hosts (fail closed)")
        void rejectsUnresolvableHost() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("http://this-host-does-not-exist.invalid:8080", true));
            assertTrue(ex.getMessage().contains("could not be resolved"));
        }
    }

    @Nested
    @DisplayName("Empty/null validation")
    class EmptyValidation {

        @Test
        @DisplayName("should reject null URL")
        void rejectsNull() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate(null, true));
        }

        @Test
        @DisplayName("should reject empty URL")
        void rejectsEmpty() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("", true));
        }

        @Test
        @DisplayName("should reject blank URL")
        void rejectsBlank() {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceUrlValidator.validate("   ", true));
        }
    }

    @Nested
    @DisplayName("Valid public URLs")
    class ValidPublicUrls {

        @Test
        @DisplayName("should accept valid HTTPS URL with port")
        void acceptsHttpsWithPort() {
            assertDoesNotThrow(
                    () -> SourceUrlValidator.validate("https://example.com:8443", false));
        }

        @Test
        @DisplayName("should accept valid HTTPS URL without port")
        void acceptsHttpsWithoutPort() {
            assertDoesNotThrow(
                    () -> SourceUrlValidator.validate("https://eddi.labs.ai", false));
        }

        @Test
        @DisplayName("should accept valid HTTP URL in dev mode with path")
        void acceptsHttpWithPath() {
            assertDoesNotThrow(
                    () -> SourceUrlValidator.validate("http://example.com/api", true));
        }
    }
}
