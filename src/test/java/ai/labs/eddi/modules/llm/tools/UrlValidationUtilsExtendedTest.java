/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for UrlValidationUtils covering IPv6 ULA, CGNAT, IPv4-mapped
 * IPv6, DNS rebinding, and other SSRF bypass vectors.
 *
 * @since 6.0.2
 */
class UrlValidationUtilsExtendedTest {

    // ─── IPv6 ULA (fc00::/7) ───

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[fc00::1]/secret",
            "http://[fd12:3456:789a::1]/admin",
            "http://[fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff]/x"
    })
    @DisplayName("P0-3: Block IPv6 ULA addresses (fc00::/7)")
    void shouldBlockIpv6Ula(String url) {
        UrlValidationUtils.HostResolver resolver = host -> {
            String clean = host.replace("[", "").replace("]", "");
            return InetAddress.getAllByName(clean);
        };
        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(url, resolver));
    }

    // ─── IPv4-mapped IPv6 (::ffff:x.x.x.x) ───

    @ParameterizedTest
    @ValueSource(strings = {
            "http://mapped-to-loopback.test/",
            "http://mapped-to-private.test/"
    })
    @DisplayName("P0-3: Block IPv4-mapped IPv6 wrapping private addresses")
    void shouldBlockIpv4MappedIpv6(String url) {
        UrlValidationUtils.HostResolver resolver = host -> {
            if (host.contains("loopback")) {
                // ::ffff:127.0.0.1
                byte[] bytes = new byte[16];
                bytes[10] = (byte) 0xFF;
                bytes[11] = (byte) 0xFF;
                bytes[12] = 127;
                bytes[13] = 0;
                bytes[14] = 0;
                bytes[15] = 1;
                return new InetAddress[]{InetAddress.getByAddress(host, bytes)};
            } else {
                // ::ffff:10.0.0.1
                byte[] bytes = new byte[16];
                bytes[10] = (byte) 0xFF;
                bytes[11] = (byte) 0xFF;
                bytes[12] = 10;
                bytes[13] = 0;
                bytes[14] = 0;
                bytes[15] = 1;
                return new InetAddress[]{InetAddress.getByAddress(host, bytes)};
            }
        };
        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(url, resolver));
    }

    // ─── CGNAT (100.64.0.0/10) ───

    @Test
    @DisplayName("P0-3: Block CGNAT addresses (100.64.0.0/10)")
    void shouldBlockCgnat() {
        UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{100, 64, 0, 5})};

        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("http://cgnat.test/", resolver));
    }

    @Test
    @DisplayName("P0-3: Allow 100.128.0.1 (outside CGNAT range)")
    void shouldAllowOutsideCgnat() {
        UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{100, (byte) 128, 0, 1})};

        InetAddress[] resolved = UrlValidationUtils.validateUrl("http://outside-cgnat.test/", resolver);
        assertNotNull(resolved);
        assertEquals(1, resolved.length);
    }

    // ─── Unspecified address (0.0.0.0/8) ───

    @Test
    @DisplayName("P0-3: Block unspecified address (0.0.0.0)")
    void shouldBlockUnspecified() {
        UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{0, 0, 0, 0})};

        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("http://zero.test/", resolver));
    }

    // ─── Multicast (224.0.0.0/4) ───

    @Test
    @DisplayName("P0-3: Block multicast addresses (224.0.0.0/4)")
    void shouldBlockMulticast() {
        UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{(byte) 224, 0, 0, 1})};

        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("http://multicast.test/", resolver));
    }

    // ─── Cloud metadata (169.254.169.254) ───

    @Test
    @DisplayName("P0-3: Block cloud metadata endpoint")
    void shouldBlockCloudMetadata() {
        UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254})};

        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("http://metadata.test/latest/meta-data/", resolver));
    }

    // ─── DNS rebinding ───

    @Nested
    @DisplayName("DNS rebinding defense")
    class DnsRebinding {

        @Test
        @DisplayName("validateUrl returns resolved addresses for TOCTOU defense")
        void shouldReturnResolvedAddresses() {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})};

            InetAddress[] resolved = UrlValidationUtils.validateUrl("http://legit.example.com/", resolver);
            assertNotNull(resolved);
            assertEquals(1, resolved.length);
            assertEquals("/8.8.8.8", resolved[0].toString().substring(resolved[0].toString().indexOf('/')));
        }

        @Test
        @DisplayName("DNS rebinding blocked if resolver returns private IP")
        void shouldBlockDnsRebinding() {
            // Simulates DNS rebinding: first resolve returns public, but we only
            // resolve once — if the mock DNS returns private, it's caught
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 1})};

            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("http://rebind.attacker.com/", resolver));
        }
    }

    // ─── Existing coverage (regression) ───

    @Nested
    @DisplayName("Existing validation rules (regression)")
    class ExistingRules {

        @Test
        void shouldRejectNullUrl() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(null));
        }

        @Test
        void shouldRejectEmptyUrl() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(""));
        }

        @Test
        void shouldRejectFtpScheme() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("ftp://example.com/file"));
        }

        @Test
        void shouldRejectFileScheme() {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("file:///etc/passwd"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://localhost/admin",
                "http://127.0.0.1:8080/",
                "http://169.254.169.254/latest/meta-data/"
        })
        void shouldBlockKnownInternalHostnames(String url) {
            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(url));
        }

        @Test
        void shouldBlockPrivateIpv4() {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 1})};

            assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("http://internal.corp/", resolver));
        }

        @Test
        void shouldAllowPublicUrl() {
            UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})};

            InetAddress[] resolved = UrlValidationUtils.validateUrl("https://example.com/", resolver);
            assertNotNull(resolved);
        }
    }
}
