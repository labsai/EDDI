/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UrlValidationUtils Tests")
class UrlValidationUtilsTest {

    // A resolver that returns a public IP for any hostname
    private static final UrlValidationUtils.HostResolver PUBLIC_RESOLVER = host -> new InetAddress[]{
            InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};

    @Nested
    @DisplayName("rejectCloudMetadataTarget — on whatever ssrf-protection says")
    class CloudMetadataAlwaysBlocked {

        private final UrlValidationUtils.HostResolver unresolvable = host -> {
            throw new UnknownHostException(host);
        };

        @ParameterizedTest
        @ValueSource(strings = {"http://169.254.169.254/latest/meta-data/", "http://metadata.google.internal/computeMetadata/v1/",
                "http://[fd00:ec2::254]/latest/meta-data/", "http://100.100.100.200/latest/meta-data/", "https://METADATA.GOOGLE.INTERNAL/",
                "http://169.254.170.2/v2/credentials"})
        @DisplayName("metadata hosts and link-local literals are refused without any DNS lookup")
        void metadataTargetsAreRefused(String url) {
            var e = assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.rejectCloudMetadataTarget(url, unresolvable));
            assertTrue(e.getMessage().contains("instance-metadata"), e.getMessage());
        }

        @Test
        @DisplayName("a hostname that resolves to the metadata address is refused")
        void hostnameResolvingToMetadataIsRefused() {
            UrlValidationUtils.HostResolver rebinding = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254})};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.rejectCloudMetadataTarget("http://rebind.example.com/", rebinding));
        }

        @ParameterizedTest
        @ValueSource(strings = {"http://10.0.0.5/api", "http://127.0.0.1:7070/agents", "http://localhost:8080/", "https://api.example.com/v1"})
        @DisplayName("private, loopback and public targets stay reachable — they are what opting out of protection is for")
        void ordinaryTargetsPass(String url) {
            UrlValidationUtils.HostResolver privateNetwork = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 9})};
            assertDoesNotThrow(() -> UrlValidationUtils.rejectCloudMetadataTarget(url, privateNetwork));
        }

        @Test
        @DisplayName("an unparseable URL or unresolvable host is left to the caller's own checks")
        void unusableUrlsAreLeftToTheCaller() {
            assertDoesNotThrow(() -> UrlValidationUtils.rejectCloudMetadataTarget("http://exa mple.com/", unresolvable));
            assertDoesNotThrow(() -> UrlValidationUtils.rejectCloudMetadataTarget("http://nope.invalid/", unresolvable));
            assertDoesNotThrow(() -> UrlValidationUtils.rejectCloudMetadataTarget(null));
            assertDoesNotThrow(() -> UrlValidationUtils.rejectCloudMetadataTarget("  "));
        }

        @Test
        @DisplayName("IPv4-mapped and IPv6 forms of the metadata addresses are recognised")
        void addressFormsAreRecognised() throws Exception {
            assertTrue(UrlValidationUtils.isMetadataAddress(InetAddress.getByName("fd00:ec2::254")));
            assertTrue(UrlValidationUtils.isMetadataAddress(InetAddress.getByName("fe80::1")));
            assertTrue(UrlValidationUtils.isMetadataAddress(InetAddress.getByAddress(
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 169, (byte) 254, (byte) 169, (byte) 254})));
            assertFalse(UrlValidationUtils.isMetadataAddress(InetAddress.getByName("10.0.0.5")));
            assertFalse(UrlValidationUtils.isMetadataAddress(InetAddress.getByName("100.100.100.199")));
        }
    }

    @Nested
    @DisplayName("validateUrl — null/blank/invalid")
    class NullBlankInvalidTests {

        @Test
        @DisplayName("null URL throws IllegalArgumentException")
        void nullUrl() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(null, PUBLIC_RESOLVER));
        }

        @Test
        @DisplayName("empty URL throws IllegalArgumentException")
        void emptyUrl() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("", PUBLIC_RESOLVER));
        }

        @Test
        @DisplayName("blank URL throws IllegalArgumentException")
        void blankUrl() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("   ", PUBLIC_RESOLVER));
        }

        @Test
        @DisplayName("malformed URL throws IllegalArgumentException")
        void malformedUrl() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("://invalid", PUBLIC_RESOLVER));
        }
    }

    @Nested
    @DisplayName("validateUrl — scheme checks")
    class SchemeTests {

        @Test
        @DisplayName("http scheme is allowed")
        void httpScheme() {
            assertDoesNotThrow(() -> UrlValidationUtils.validateUrl("http://example.com", PUBLIC_RESOLVER));
        }

        @Test
        @DisplayName("https scheme is allowed")
        void httpsScheme() {
            assertDoesNotThrow(() -> UrlValidationUtils.validateUrl("https://example.com", PUBLIC_RESOLVER));
        }

        @Test
        @DisplayName("ftp scheme is rejected")
        void ftpScheme() {
            var ex = assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("ftp://example.com", PUBLIC_RESOLVER));
            assertTrue(ex.getMessage().contains("ftp"));
        }

        @Test
        @DisplayName("no scheme is rejected")
        void noScheme() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("example.com", PUBLIC_RESOLVER));
        }

        @Test
        @DisplayName("file scheme is rejected")
        void fileScheme() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("file:///etc/passwd", PUBLIC_RESOLVER));
        }
    }

    @Nested
    @DisplayName("validateUrl — host checks")
    class HostTests {

        @Test
        @DisplayName("no host in URL throws IllegalArgumentException")
        void noHost() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("http://", PUBLIC_RESOLVER));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://localhost/test",
                "http://127.0.0.1/test",
                "http://[::1]/test",
                "http://something.local/test",
                "http://host.internal/test",
                "http://metadata.google.internal/test",
                "http://169.254.169.254/test"
        })
        @DisplayName("blocked hostnames are rejected")
        void blockedHostnames(String url) {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(url, PUBLIC_RESOLVER));
        }
    }

    @Nested
    @DisplayName("validateUrl — DNS resolution")
    class DnsResolutionTests {

        @Test
        @DisplayName("unresolvable host throws IllegalArgumentException")
        void unresolvableHost() {
            UrlValidationUtils.HostResolver failingResolver = host -> {
                throw new UnknownHostException("no such host");
            };
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://nonexistent.example.com", failingResolver));
            assertTrue(ex.getMessage().contains("Cannot resolve hostname"));
        }

        @Test
        @DisplayName("host resolving to private IP is rejected")
        void privateIpResolution() {
            UrlValidationUtils.HostResolver privateResolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 1})};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://evil.example.com", privateResolver));
        }

        @Test
        @DisplayName("host resolving to public IP is accepted")
        void publicIpResolution() {
            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com", PUBLIC_RESOLVER);
            assertNotNull(result);
            assertEquals(1, result.length);
        }
    }

    @Nested
    @DisplayName("isBlockedHostname")
    class IsBlockedHostnameTests {

        @Test
        void localhost_isBlocked() {
            assertTrue(UrlValidationUtils.isBlockedHostname("localhost"));
        }

        @Test
        void loopbackIpv4_isBlocked() {
            assertTrue(UrlValidationUtils.isBlockedHostname("127.0.0.1"));
        }

        @Test
        void loopbackIpv6_isBlocked() {
            assertTrue(UrlValidationUtils.isBlockedHostname("[::1]"));
            assertTrue(UrlValidationUtils.isBlockedHostname("::1"));
        }

        @Test
        void localSuffix_isBlocked() {
            assertTrue(UrlValidationUtils.isBlockedHostname("myservice.local"));
        }

        @Test
        void internalSuffix_isBlocked() {
            assertTrue(UrlValidationUtils.isBlockedHostname("myservice.internal"));
        }

        @Test
        void cloudMetadata_isBlocked() {
            assertTrue(UrlValidationUtils.isBlockedHostname("169.254.169.254"));
        }

        @Test
        void publicHost_isNotBlocked() {
            assertFalse(UrlValidationUtils.isBlockedHostname("example.com"));
        }
    }

    @Nested
    @DisplayName("isPrivateAddress")
    class IsPrivateAddressTests {

        @Test
        void loopback_isPrivate() throws Exception {
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1})));
        }

        @Test
        void rfc1918_10_isPrivate() throws Exception {
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(new byte[]{10, 1, 2, 3})));
        }

        @Test
        void rfc1918_172_isPrivate() throws Exception {
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(new byte[]{(byte) 172, 16, 0, 1})));
        }

        @Test
        void rfc1918_192_isPrivate() throws Exception {
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 1})));
        }

        @Test
        void cgnat_isPrivate() throws Exception {
            // 100.64.0.0/10 — CGNAT
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(new byte[]{100, 64, 0, 1})));
        }

        @Test
        void multicast_isPrivate() throws Exception {
            // 224.0.0.1
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(new byte[]{(byte) 224, 0, 0, 1})));
        }

        @Test
        void unspecified_isPrivate() throws Exception {
            // 0.0.0.0
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(new byte[]{0, 0, 0, 0})));
        }

        @Test
        void cloudMetadata_isPrivate() throws Exception {
            // 169.254.169.254
            assertTrue(UrlValidationUtils.isPrivateAddress(
                    InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254})));
        }

        @Test
        void publicIpv4_isNotPrivate() throws Exception {
            assertFalse(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(new byte[]{8, 8, 8, 8})));
        }

        @Test
        void ipv6_ula_isPrivate() throws Exception {
            // fc00:: — ULA
            byte[] addr = new byte[16];
            addr[0] = (byte) 0xFC;
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(addr)));
        }

        @Test
        void ipv6_fd_isPrivate() throws Exception {
            // fd00:: — ULA
            byte[] addr = new byte[16];
            addr[0] = (byte) 0xFD;
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(addr)));
        }

        @Test
        void ipv4Mapped_private_isPrivate() throws Exception {
            // ::ffff:10.0.0.1
            byte[] addr = new byte[16];
            addr[10] = (byte) 0xFF;
            addr[11] = (byte) 0xFF;
            addr[12] = 10;
            addr[13] = 0;
            addr[14] = 0;
            addr[15] = 1;
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(addr)));
        }

        @Test
        void ipv4Mapped_public_isNotPrivate() throws Exception {
            // ::ffff:8.8.8.8
            byte[] addr = new byte[16];
            addr[10] = (byte) 0xFF;
            addr[11] = (byte) 0xFF;
            addr[12] = 8;
            addr[13] = 8;
            addr[14] = 8;
            addr[15] = 8;
            assertFalse(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(addr)));
        }

        @Test
        void publicIpv6_isNotPrivate() throws Exception {
            // 2001:0db8::1 (documentation prefix, but not blocked by our code)
            byte[] addr = new byte[16];
            addr[0] = 0x20;
            addr[1] = 0x01;
            addr[2] = 0x0d;
            addr[3] = (byte) 0xb8;
            addr[15] = 1;
            assertFalse(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(addr)));
        }

        @Test
        void ipv6_loopback_isPrivate() throws Exception {
            // ::1
            byte[] addr = new byte[16];
            addr[15] = 1;
            assertTrue(UrlValidationUtils.isPrivateAddress(InetAddress.getByAddress(addr)));
        }
    }

    @Nested
    @DisplayName("isValidHttpUrl")
    class IsValidHttpUrlTests {

        @Test
        void validHttp() {
            assertTrue(UrlValidationUtils.isValidHttpUrl("http://example.com"));
        }

        @Test
        void validHttps() {
            assertTrue(UrlValidationUtils.isValidHttpUrl("https://example.com/path?q=1"));
        }

        @Test
        void nullUrl() {
            assertFalse(UrlValidationUtils.isValidHttpUrl(null));
        }

        @Test
        void blankUrl() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("  "));
        }

        @Test
        void ftpScheme() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("ftp://example.com"));
        }

        @Test
        void noScheme() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("example.com"));
        }

        @Test
        void malformedUrl() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("://bad"));
        }

        @Test
        void noHost() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("http://"));
        }
    }

    @Nested
    @DisplayName("validateUrl — default resolver (single-arg)")
    class DefaultResolverTests {

        @Test
        @DisplayName("null URL uses default resolver path")
        void nullUrlDefaultResolver() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(null));
        }
    }
}
