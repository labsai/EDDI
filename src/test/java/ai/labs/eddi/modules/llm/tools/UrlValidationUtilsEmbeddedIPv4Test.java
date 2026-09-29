/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IPv6 spellings of an IPv4 address (NAT64, 6to4, IPv4-compatible), checked
 * through the public entry points ({@code validateUrl},
 * {@code rejectCloudMetadataTarget}) and from literal text rather than byte
 * arrays. The byte-level cases, and the reserved IPv4 ranges, live in
 * {@code UrlValidationUtilsAddressFormsTest}.
 */
@DisplayName("UrlValidationUtils — embedded IPv4")
class UrlValidationUtilsEmbeddedIPv4Test {

    private static InetAddress address(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @ParameterizedTest(name = "{0} is blocked")
    @ValueSource(strings = {
            // NAT64 well-known prefix (RFC 6052) around private IPv4
            "64:ff9b::7f00:1", // 127.0.0.1
            "64:ff9b::a00:1", // 10.0.0.1
            "64:ff9b::c0a8:101", // 192.168.1.1
            "64:ff9b::a9fe:a9fe", // 169.254.169.254
            // 6to4 (RFC 3056) around private IPv4
            "2002:7f00:1::1", // 127.0.0.1
            "2002:a00:1::", // 10.0.0.1
            "2002:a9fe:a9fe::", // 169.254.169.254
            // IPv4-compatible (deprecated, RFC 4291)
            "::7f00:1", "::a00:1"})
    void blocked(String literal) throws Exception {
        assertTrue(UrlValidationUtils.isPrivateAddress(address(literal)), literal);
    }

    @ParameterizedTest(name = "{0} is allowed")
    @ValueSource(strings = {
            "64:ff9b::808:808", // NAT64 of 8.8.8.8
            "2002:808:808::1", // 6to4 of 8.8.8.8
            "2606:4700:4700::1111", // ordinary public IPv6
            "192.0.1.1", "8.8.4.4"})
    void allowed(String literal) throws Exception {
        assertFalse(UrlValidationUtils.isPrivateAddress(address(literal)), literal);
    }

    @Test
    @DisplayName("a hostname resolving to a NAT64 loopback address is refused")
    void validateUrlRefusesNat64Loopback() {
        UrlValidationUtils.HostResolver resolver = host -> new InetAddress[]{address("64:ff9b::7f00:1")};

        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("https://rebind.example/", resolver));
    }

    @Test
    @DisplayName("the always-on metadata refusal sees through NAT64 and 6to4")
    void metadataRefusalUnwrapsEmbeddedAddresses() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlValidationUtils.rejectCloudMetadataTarget("http://[64:ff9b::a9fe:a9fe]/latest/meta-data/"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlValidationUtils.rejectCloudMetadataTarget("http://[2002:a9fe:a9fe::1]/latest/meta-data/"));
        assertDoesNotThrow(() -> UrlValidationUtils.rejectCloudMetadataTarget("http://[64:ff9b::808:808]/"));
    }

    @Test
    @DisplayName("embeddedIPv4Addresses extracts from each form and from nothing else")
    void embeddedIPv4() throws Exception {
        byte[] loopback = {127, 0, 0, 1};
        assertArrayEquals(loopback, UrlValidationUtils.embeddedIPv4Addresses(address("64:ff9b::7f00:1").getAddress()).get(0));
        assertArrayEquals(loopback, UrlValidationUtils.embeddedIPv4Addresses(address("2002:7f00:1::").getAddress()).get(0));
        assertArrayEquals(loopback, UrlValidationUtils.embeddedIPv4Addresses(address("::7f00:1").getAddress()).get(0));
        assertTrue(UrlValidationUtils.embeddedIPv4Addresses(address("2606:4700:4700::1111").getAddress()).isEmpty());
        assertTrue(UrlValidationUtils.embeddedIPv4Addresses(new byte[]{1, 2, 3, 4}).isEmpty());
    }
}
