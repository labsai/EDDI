/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UrlValidationUtils — Deep Branch Coverage")
class UrlValidationUtilsDeepBranchTest {

    // ─── isPrivateIPv4 branches ──────────────────────────────────────────

    @Nested
    @DisplayName("IPv4 CGNAT boundary")
    class CgnatBoundary {

        @Test
        @DisplayName("100.64.0.1 — in CGNAT range")
        void inCgnat() {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{100, 64, 0, 1})};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("100.100.0.1 — in CGNAT range (middle)")
        void inCgnatMiddle() {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{100, 100, 0, 1})};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("100.63.255.255 — just below CGNAT range")
        void belowCgnat() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{100, 63, (byte) 255, (byte) 255})};
            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com/api", resolver);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("IPv4 multicast boundary")
    class MulticastBoundary {

        @Test
        @DisplayName("239.255.255.255 — edge of multicast")
        void multicastEdge() {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{(byte) 239, (byte) 255, (byte) 255, (byte) 255})};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("240.0.0.1 — NOT multicast (reserved)")
        void notMulticast() throws Exception {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{(byte) 240, 0, 0, 1})};
            // 240.x is reserved but NOT multicast and not caught by JDK checks
            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com/api", resolver);
            assertNotNull(result);
        }

        @Test
        @DisplayName("225.0.0.1 — multicast")
        void multicast225() {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{(byte) 225, 0, 0, 1})};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }
    }

    @Nested
    @DisplayName("IPv4 unspecified")
    class Unspecified {

        @Test
        @DisplayName("0.255.255.255 — in 0.x.x.x range")
        void thisNetwork() {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{0, (byte) 255, (byte) 255, (byte) 255})};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }
    }

    @Nested
    @DisplayName("Cloud metadata near-misses")
    class CloudMetadataNearMiss {

        @Test
        @DisplayName("169.254.168.254 — NOT cloud metadata")
        void notMetadata1() {
            // 169.254.x.x is link-local → still blocked by JDK
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, (byte) 168, (byte) 254})};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("169.254.169.253 — NOT cloud metadata (last byte differs)")
        void notMetadata2() {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 253})};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }
    }

    // ─── isPrivateIPv6 branches ──────────────────────────────────────────

    @Nested
    @DisplayName("IPv6 ULA boundary")
    class UlaBoundary {

        @Test
        @DisplayName("0xFE — NOT ULA (fb, fc, fd are ULA but 0xFE is not)")
        void notUla() throws Exception {
            byte[] addr = new byte[16];
            addr[0] = (byte) 0xFE;
            addr[1] = (byte) 0x00; // Not link-local (fe80::/10 requires second nibble 8)
            addr[15] = 1;
            // fe00::1 — This is NOT ULA and NOT link-local
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(addr)};
            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com/api", resolver);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("IPv4-mapped IPv6 with various embedded IPv4")
    class Ipv4MappedVariants {

        private byte[] makeIpv4Mapped(int b0, int b1, int b2, int b3) {
            byte[] addr = new byte[16];
            addr[10] = (byte) 0xFF;
            addr[11] = (byte) 0xFF;
            addr[12] = (byte) b0;
            addr[13] = (byte) b1;
            addr[14] = (byte) b2;
            addr[15] = (byte) b3;
            return addr;
        }

        @Test
        @DisplayName("::ffff:100.64.0.1 — embedded CGNAT")
        void embeddedCgnat() {
            byte[] addr = makeIpv4Mapped(100, 64, 0, 1);
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(addr)};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("::ffff:224.0.0.1 — embedded multicast")
        void embeddedMulticast() {
            byte[] addr = makeIpv4Mapped(224, 0, 0, 1);
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(addr)};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("::ffff:0.0.0.1 — embedded unspecified range")
        void embeddedUnspecified() {
            byte[] addr = makeIpv4Mapped(0, 0, 0, 1);
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(addr)};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("::ffff:169.254.169.254 — embedded cloud metadata")
        void embeddedCloudMetadata() {
            byte[] addr = makeIpv4Mapped(169, 254, 169, 254);
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(addr)};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }

        @Test
        @DisplayName("::ffff:10.0.0.1 — embedded 10.x private")
        void embeddedTenNet() {
            byte[] addr = makeIpv4Mapped(10, 0, 0, 1);
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(addr)};
            assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("http://example.com/api", resolver));
        }
    }

    @Nested
    @DisplayName("Non-IPv4-mapped IPv6")
    class NonIpv4Mapped {

        @Test
        @DisplayName("Regular public IPv6 — bytes[10] != 0xFF")
        void regularPublicIpv6() throws Exception {
            byte[] addr = new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(addr)};
            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com/api", resolver);
            assertNotNull(result);
        }

        @Test
        @DisplayName("IPv6 with non-zero early bytes — NOT IPv4-mapped")
        void nonZeroEarlyBytes() throws Exception {
            byte[] addr = new byte[]{0x26, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 8, 8, 8, 8};
            // bytes[0] is 0x26, so not IPv4-mapped
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(addr)};
            InetAddress[] result = UrlValidationUtils.validateUrl("http://example.com/api", resolver);
            assertNotNull(result);
        }
    }

    // ─── isBlockedHostname direct tests ──────────────────────────────────

    @Nested
    @DisplayName("isBlockedHostname — additional")
    class BlockedHostnameAdditional {

        @Test
        @DisplayName("169.254.169.254 is blocked")
        void cloudMetadataIp() {
            assertTrue(UrlValidationUtils.isBlockedHostname("169.254.169.254"));
        }

        @Test
        @DisplayName("metadata.google.internal is blocked")
        void metadataGoogle() {
            assertTrue(UrlValidationUtils.isBlockedHostname("metadata.google.internal"));
        }

        @Test
        @DisplayName("random.com is not blocked")
        void randomCom() {
            assertFalse(UrlValidationUtils.isBlockedHostname("random.com"));
        }

        @Test
        @DisplayName("internal.example.com is NOT blocked (not ending with .internal)")
        void notInternal() {
            assertFalse(UrlValidationUtils.isBlockedHostname("internal.example.com"));
        }
    }

    // ─── isValidHttpUrl — additional branch coverage ─────────────────────

    @Nested
    @DisplayName("isValidHttpUrl — additional")
    class IsValidHttpUrlAdditional {

        @Test
        @DisplayName("data: scheme returns false")
        void dataScheme() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("data:text/html,<h1>hi</h1>"));
        }

        @Test
        @DisplayName("HTTP with port is valid")
        void httpWithPort() {
            assertTrue(UrlValidationUtils.isValidHttpUrl("http://example.com:8080/path"));
        }

        @Test
        @DisplayName("HTTPS mixed case is valid")
        void httpsMixedCase() {
            assertTrue(UrlValidationUtils.isValidHttpUrl("HTTPS://Example.com/path"));
        }

        @Test
        @DisplayName("just scheme no host returns false")
        void schemeNoHost() {
            assertFalse(UrlValidationUtils.isValidHttpUrl("http://"));
        }
    }

    // ─── validateUrl — scheme edge cases ──────────────────────────────────

    @Nested
    @DisplayName("validateUrl — scheme message")
    class SchemeEdgeCases {

        @Test
        @DisplayName("null scheme shows '<no scheme>' in message")
        void nullSchemeMessage() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("example.com/path"));
            assertTrue(ex.getMessage().contains("<no scheme>"));
        }

        @Test
        @DisplayName("data scheme shows 'data' in message")
        void dataSchemeMessage() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> UrlValidationUtils.validateUrl("data:text/html,test"));
            assertTrue(ex.getMessage().contains("data"));
        }
    }
}
