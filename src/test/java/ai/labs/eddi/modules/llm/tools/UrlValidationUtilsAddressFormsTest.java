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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thorough per-address-form table for the SSRF address checks — the extra IPv4
 * ranges, the IPv6 embeddings of an IPv4 address, and the additional
 * cloud-metadata endpoints added to close the gaps in
 * {@link UrlValidationUtils}.
 */
@DisplayName("UrlValidationUtils — address forms")
class UrlValidationUtilsAddressFormsTest {

    private static InetAddress ipv4(int a, int b, int c, int d) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    /** Builds an IPv6 InetAddress from 16 integer byte values. */
    private static InetAddress ipv6(int... octets) throws UnknownHostException {
        byte[] bytes = new byte[16];
        for (int i = 0; i < 16; i++) {
            bytes[i] = (byte) octets[i];
        }
        return InetAddress.getByAddress(bytes);
    }

    @Nested
    @DisplayName("IPv4 ranges newly treated as unsafe")
    class Ipv4Ranges {

        @Test
        @DisplayName("198.18.0.0/15 benchmarking range is private")
        void benchmarking() throws Exception {
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv4(198, 18, 0, 1)));
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv4(198, 19, 255, 255)));
        }

        @Test
        @DisplayName("255.255.255.255 broadcast is private; the rest of 240.0.0.0/4 stays reachable")
        void broadcastBlockedReservedAllowed() throws Exception {
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv4(255, 255, 255, 255)), "limited broadcast is blocked");
            // The rest of 240.0.0.0/4 is reserved-for-future-use but deliberately left
            // reachable to match the shipped validator contract.
            assertFalse(UrlValidationUtils.isPrivateAddress(ipv4(240, 0, 0, 1)));
            assertFalse(UrlValidationUtils.isPrivateAddress(ipv4(250, 1, 2, 3)));
        }

        @Test
        @DisplayName("192.0.0.0/24 IETF protocol range (incl. OCI metadata) is private")
        void ietfProtocolRange() throws Exception {
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv4(192, 0, 0, 1)));
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv4(192, 0, 0, 192)));
        }

        @Test
        @DisplayName("ordinary public addresses stay allowed")
        void publicStaysAllowed() throws Exception {
            assertFalse(UrlValidationUtils.isPrivateAddress(ipv4(8, 8, 8, 8)));
            assertFalse(UrlValidationUtils.isPrivateAddress(ipv4(1, 1, 1, 1)));
            // 192.0.2.0/24 (TEST-NET-1, 192.0.2.x) is NOT in 192.0.0.0/24 and stays public
            // here.
            assertFalse(UrlValidationUtils.isPrivateAddress(ipv4(192, 0, 2, 5)));
        }
    }

    @Nested
    @DisplayName("IPv6 embeddings of an IPv4 address are unpacked and re-checked")
    class Ipv6Embeddings {

        @Test
        @DisplayName("IPv4-mapped ::ffff: unsafe when the embedded IPv4 is unsafe")
        void mapped() throws Exception {
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1)));
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 169, 254, 169, 254)));
            assertFalse(UrlValidationUtils.isPrivateAddress(ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 8, 8, 8, 8)));
        }

        @Test
        @DisplayName("IPv4-compatible ::/96 unsafe when the embedded IPv4 is unsafe")
        void compatible() throws Exception {
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1)));
        }

        @Test
        @DisplayName("NAT64 64:ff9b::/96 and 64:ff9b:1::/48 unpack the trailing IPv4")
        void nat64() throws Exception {
            // 64:ff9b::169.254.169.254
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv6(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 169, 254, 169, 254)));
            // 64:ff9b:1:: with embedded 10.0.0.1
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv6(0, 0x64, 0xff, 0x9b, 0, 1, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1)));
        }

        @Test
        @DisplayName("6to4 2002::/16 unpacks the IPv4 in bytes 2-5")
        void sixToFour() throws Exception {
            // 2002:a9fe:a9fe:: → 169.254.169.254
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv6(0x20, 0x02, 169, 254, 169, 254, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
        }

        @Test
        @DisplayName("Teredo 2001::/32 unpacks the server IPv4 (bytes 4-7)")
        void teredoServer() throws Exception {
            // 2001:0000: with server IPv4 10.0.0.1 in bytes 4-7
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv6(0x20, 0x01, 0, 0, 10, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)));
        }

        @Test
        @DisplayName("Teredo 2001::/32 unpacks the bit-inverted client IPv4 (bytes 12-15)")
        void teredoClient() throws Exception {
            // client IPv4 127.0.0.1 stored bit-inverted → ~127.0.0.1 = 128.255.255.254
            assertTrue(UrlValidationUtils.isPrivateAddress(ipv6(0x20, 0x01, 0, 0, 8, 8, 8, 8, 0, 0, 0, 0,
                    ~127 & 0xff, ~0 & 0xff, ~0 & 0xff, ~1 & 0xff)));
        }

        @Test
        @DisplayName("a genuine public IPv6 address stays allowed")
        void publicIpv6() throws Exception {
            // 2001:4860:4860::8888 (Google public DNS) — 2001: prefix but not Teredo (bytes
            // 2-3 non-zero)
            assertFalse(UrlValidationUtils.isPrivateAddress(ipv6(0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0x88, 0x88)));
        }
    }

    @Nested
    @DisplayName("additional cloud-metadata endpoints")
    class MetadataEndpoints {

        private final UrlValidationUtils.HostResolver unresolvable = host -> {
            throw new UnknownHostException(host);
        };

        @ParameterizedTest
        @ValueSource(strings = {"http://168.63.129.16/", "http://192.0.0.192/opc/v1/instance/", "http://100.100.100.200/latest/meta-data/"})
        @DisplayName("Azure WireServer, OCI and Alibaba metadata literals are refused")
        void literalsRefused(String url) {
            var e = assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.rejectCloudMetadataTarget(url, unresolvable));
            assertTrue(e.getMessage().contains("instance-metadata"), e.getMessage());
        }

        @Test
        @DisplayName("the new metadata addresses are recognised as metadata addresses")
        void isMetadataAddress() throws Exception {
            assertTrue(UrlValidationUtils.isMetadataAddress(ipv4(168, 63, 129, 16)));
            assertTrue(UrlValidationUtils.isMetadataAddress(ipv4(192, 0, 0, 192)));
            assertTrue(UrlValidationUtils.isMetadataAddress(ipv4(100, 100, 100, 200)));
            assertFalse(UrlValidationUtils.isMetadataAddress(ipv4(8, 8, 8, 8)));
        }

        @Test
        @DisplayName("an IPv6-embedded metadata address is recognised too")
        void embeddedMetadata() throws Exception {
            // NAT64 wrapper of 169.254.169.254
            assertTrue(UrlValidationUtils.isMetadataAddress(ipv6(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 169, 254, 169, 254)));
            // ::ffff: mapped OCI 192.0.0.192
            assertTrue(UrlValidationUtils.isMetadataAddress(ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 192, 0, 0, 192)));
        }
    }
}
