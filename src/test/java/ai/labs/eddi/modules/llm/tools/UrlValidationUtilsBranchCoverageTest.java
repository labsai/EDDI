/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UrlValidationUtils — Branch Coverage")
class UrlValidationUtilsBranchCoverageTest {

    // ─── validateUrl(String) single-arg ───────────────────────────────────

    @Nested
    @DisplayName("validateUrl — null/blank/invalid")
    class ValidateUrlNullBlank {

        @Test
        @DisplayName("null URL throws")
        void nullUrl() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(null));
        }

        @Test
        @DisplayName("blank URL throws")
        void blankUrl() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("   "));
        }

        @Test
        @DisplayName("empty URL throws")
        void emptyUrl() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(""));
        }

        @Test
        @DisplayName("malformed URI throws")
        void malformedUri() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("://bad uri with spaces"));
            assertTrue(ex.getMessage().contains("Invalid URL syntax"));
        }
    }

    @Nested
    @DisplayName("validateUrl — scheme checks")
    class SchemeChecks {

        @Test
        @DisplayName("no scheme throws")
        void noScheme() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("example.com/path"));
            assertTrue(ex.getMessage().contains("Only http and https"));
        }

        @Test
        @DisplayName("ftp scheme rejected")
        void ftpScheme() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("ftp://example.com/file"));
            assertTrue(ex.getMessage().contains("Only http and https"));
        }

        @Test
        @DisplayName("file scheme rejected")
        void fileScheme() {
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("file:///etc/passwd"));
        }

        @Test
        @DisplayName("javascript scheme rejected")
        void jsScheme() {
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("javascript:alert(1)"));
        }
    }

    @Nested
    @DisplayName("validateUrl — host checks")
    class HostChecks {

        @Test
        @DisplayName("no host throws")
        void noHost() {
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http:///path"));
        }
    }

    @Nested
    @DisplayName("validateUrl — blocked hostnames")
    class BlockedHostnames {

        @Test
        @DisplayName("localhost blocked")
        void localhost() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://localhost/api"));
            assertTrue(ex.getMessage().contains("internal/local"));
        }

        @Test
        @DisplayName("127.0.0.1 blocked")
        void loopback() {
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://127.0.0.1:8080/test"));
        }

        @Test
        @DisplayName(".local domain blocked")
        void localDomain() {
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://myhost.local/api"));
        }

        @Test
        @DisplayName(".internal domain blocked")
        void internalDomain() {
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://myhost.internal/api"));
        }

        @Test
        @DisplayName("metadata.google.internal blocked")
        void googleMetadata() {
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://metadata.google.internal/computeMetadata"));
        }

        @Test
        @DisplayName("cloud metadata IP 169.254.169.254 blocked")
        void cloudMetadataIp() {
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://169.254.169.254/latest/meta-data"));
        }

        @Test
        @DisplayName("IPv6 loopback [::1] blocked")
        void ipv6Loopback() {
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://[::1]/api"));
        }
    }

    @Nested
    @DisplayName("validateUrl — DNS resolution with custom resolver")
    class DnsResolution {

        @Test
        @DisplayName("private IP via DNS resolution rejected")
        void privateIpViaDns() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 1})};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://myserver.example.com/api", resolver));
        }

        @Test
        @DisplayName("10.x.x.x private IP via DNS resolution rejected")
        void tenNetworkViaDns() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 1})};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://myserver.example.com/api", resolver));
        }

        @Test
        @DisplayName("172.16.x.x private IP via DNS resolution rejected")
        void rfc1918_172_16() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{(byte) 172, 16, 0, 1})};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://myserver.example.com/api", resolver));
        }

        @Test
        @DisplayName("CGNAT 100.64.x.x rejected")
        void cgnat() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{100, 64, 0, 1})};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://myserver.example.com/api", resolver));
        }

        @Test
        @DisplayName("CGNAT 100.127.x.x (edge of /10) rejected")
        void cgnatEdge() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{100, 127, (byte) 255, (byte) 254})};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("CGNAT boundary 100.128.x.x NOT rejected (outside /10)")
        void cgnatOutsideRange() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{100, (byte) 128, 0, 1})};

            // 100.128.x.x is NOT in the CGNAT range, should pass
            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com/api", resolver);
            assertNotNull(result);
        }

        @Test
        @DisplayName("multicast 224.x.x.x rejected")
        void multicast() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{(byte) 224, 0, 0, 1})};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("unspecified 0.0.0.0 rejected")
        void unspecified() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{0, 0, 0, 0})};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("0.x.x.x (this network) rejected")
        void thisNetwork() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{0, 1, 2, 3})};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("cloud metadata 169.254.169.254 via DNS rejected")
        void cloudMetadataViaDns() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254})};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("UnknownHostException thrown when DNS fails")
        void unknownHost() {
            UrlValidationUtils.HostResolver resolver = host -> {
                throw new UnknownHostException("no such host");
            };

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://nonexistent.example.com/api", resolver));
            assertTrue(ex.getMessage().contains("Cannot resolve hostname"));
        }

        @Test
        @DisplayName("public IP passes validation")
        void publicIpPasses() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};

            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com/api", resolver);
            assertNotNull(result);
            assertEquals(1, result.length);
        }

        @Test
        @DisplayName("HTTPS scheme accepted")
        void httpsAccepted() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 4, 4})};

            InetAddress[] result = UrlValidationUtils.validateUrl("https://example.com/secure", resolver);
            assertNotNull(result);
        }

        @Test
        @DisplayName("multiple addresses — one private rejects all")
        void multipleAddressesOnePrivate() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{8, 8, 8, 8}),
                    InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, 1})
            };

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }
    }

    @Nested
    @DisplayName("validateUrl — IPv6 addresses")
    class IPv6Checks {

        @Test
        @DisplayName("IPv6 ULA fc00::/7 rejected")
        void ipv6ULA() throws Exception {
            // fc00::1
            byte[] addr = new byte[16];
            addr[0] = (byte) 0xFC;
            addr[15] = 1;
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(addr)};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("IPv6 ULA fd00::1 rejected")
        void ipv6ULA_fd() throws Exception {
            byte[] addr = new byte[16];
            addr[0] = (byte) 0xFD;
            addr[15] = 1;
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(addr)};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("IPv4-mapped IPv6 with private IPv4 rejected")
        void ipv4MappedPrivate() throws Exception {
            // ::ffff:192.168.1.1
            byte[] addr = new byte[16];
            addr[10] = (byte) 0xFF;
            addr[11] = (byte) 0xFF;
            addr[12] = (byte) 192;
            addr[13] = (byte) 168;
            addr[14] = 1;
            addr[15] = 1;
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(addr)};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("IPv4-mapped IPv6 with public IPv4 passes")
        void ipv4MappedPublic() throws Exception {
            // ::ffff:8.8.8.8
            byte[] addr = new byte[16];
            addr[10] = (byte) 0xFF;
            addr[11] = (byte) 0xFF;
            addr[12] = 8;
            addr[13] = 8;
            addr[14] = 8;
            addr[15] = 8;
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(addr)};

            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com/api", resolver);
            assertNotNull(result);
        }

        @Test
        @DisplayName("IPv4-mapped IPv6 with loopback rejected")
        void ipv4MappedLoopback() throws Exception {
            // ::ffff:127.0.0.1
            byte[] addr = new byte[16];
            addr[10] = (byte) 0xFF;
            addr[11] = (byte) 0xFF;
            addr[12] = 127;
            addr[13] = 0;
            addr[14] = 0;
            addr[15] = 1;
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(addr)};

            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("public IPv6 passes")
        void publicIpv6() throws Exception {
            // 2001:4860:4860::8888 (Google DNS)
            byte[] addr = new byte[]{0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0x88, (byte) 0x88};
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(addr)};

            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com/api", resolver);
            assertNotNull(result);
        }

        @Test
        @DisplayName("Non-IPv4-mapped IPv6 with some zero bytes passes if not ULA/link-local")
        void nonMappedIpv6PublicPasses() throws Exception {
            // Some random non-private IPv6
            byte[] addr = new byte[]{0x26, 0x06, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0x10, 0x01};
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(addr)};

            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com/api", resolver);
            assertNotNull(result);
        }
    }

    // ─── isBlockedHostname ────────────────────────────────────────────────

    @Nested
    @DisplayName("isBlockedHostname")
    class IsBlockedHostname {

        @Test
        @DisplayName("::1 (no brackets) is blocked")
        void ipv6LoopbackNoBrackets() {
            assertTrue(UrlValidationUtils.isBlockedHostname("::1"));
        }

        @Test
        @DisplayName("[::1] is blocked")
        void ipv6LoopbackBrackets() {
            assertTrue(UrlValidationUtils.isBlockedHostname("[::1]"));
        }

        @Test
        @DisplayName("public hostname not blocked")
        void publicHostname() {
            assertFalse(UrlValidationUtils.isBlockedHostname("google.com"));
        }

        @Test
        @DisplayName("host ending in .local is blocked")
        void dotLocal() {
            assertTrue(UrlValidationUtils.isBlockedHostname("printer.local"));
        }

        @Test
        @DisplayName("host ending in .internal is blocked")
        void dotInternal() {
            assertTrue(UrlValidationUtils.isBlockedHostname("db.internal"));
        }
    }

    // ─── isValidHttpUrl ──────────────────────────────────────────────────

    @Nested
    @DisplayName("isValidHttpUrl")
    class IsValidHttpUrl {

        @Test
        @DisplayName("valid http URL returns true")
        void validHttp() {
            assertTrue(UrlValidationUtils.isValidHttpUrl("http://example.com/path"));
        }

        @Test
        @DisplayName("valid https URL returns true")
        void validHttps() {
            assertTrue(UrlValidationUtils.isValidHttpUrl("https://example.com/path"));
        }

        @Test
        @DisplayName("null returns false")
        void nullUrl() {
            assertFalse(UrlValidationUtils.isValidHttpUrl(null));
        }

        @Test
        @DisplayName("blank returns false")
        void blankUrl() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("  "));
        }

        @Test
        @DisplayName("empty returns false")
        void emptyUrl() {
            assertFalse(UrlValidationUtils.isValidHttpUrl(""));
        }

        @Test
        @DisplayName("ftp scheme returns false")
        void ftpScheme() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("ftp://example.com/file"));
        }

        @Test
        @DisplayName("malformed URI returns false")
        void malformedUri() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("http://bad uri with spaces"));
        }

        @Test
        @DisplayName("no host returns false")
        void noHost() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("http:///path"));
        }

        @Test
        @DisplayName("no scheme returns false")
        void noScheme() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("example.com/path"));
        }
    }

    // ─── isCloudMetadataAddress edge cases ────────────────────────────────

    @Nested
    @DisplayName("Cloud metadata address edge cases")
    class CloudMetadata {

        @Test
        @DisplayName("169.254.169.253 is NOT cloud metadata")
        void notCloudMetadata() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 253})};

            // 169.254.x.x is link-local though, so still rejected by JDK check
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }
    }
}
