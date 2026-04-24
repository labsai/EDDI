/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UrlValidationUtils - SSRF prevention.
 */
class UrlValidationUtilsTest {

    // === Valid URLs ===

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "https://example.com/path/to/resource", "http://example.com", "https://example.com:8080/path",
            "https://www.google.com/search?q=test"})
    void testValidateUrl_AcceptsValidPublicUrls(String url) {
        assertDoesNotThrow(() -> UrlValidationUtils.validateUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://example.com", "https://www.google.com"})
    void testIsValidHttpUrl_AcceptsValidUrls(String url) {
        assertTrue(UrlValidationUtils.isValidHttpUrl(url));
    }

    // === Null/Empty ===

    @Test
    void testValidateUrl_RejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(null));
    }

    @Test
    void testValidateUrl_RejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(""));
    }

    @Test
    void testValidateUrl_RejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("   "));
    }

    @Test
    void testIsValidHttpUrl_RejectsNull() {
        assertFalse(UrlValidationUtils.isValidHttpUrl(null));
    }

    @Test
    void testIsValidHttpUrl_RejectsEmpty() {
        assertFalse(UrlValidationUtils.isValidHttpUrl(""));
    }

    // === Scheme Validation ===

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/file.pdf", "file:///etc/passwd", "file:///C:/Windows/System32/config/SAM",
            "jar:file:///app.jar!/config.yml", "gopher://evil.com:25/", "dict://evil.com:11111/", "ldap://evil.com/", "tftp://evil.com/"})
    void testValidateUrl_RejectsNonHttpSchemes(String url) {
        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(url), "Should reject non-HTTP scheme: " + url);
    }

    @Test
    void testIsValidHttpUrl_RejectsFileScheme() {
        assertFalse(UrlValidationUtils.isValidHttpUrl("file:///etc/passwd"));
    }

    @Test
    void testValidateUrl_RejectsNoScheme() {
        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl("example.com/path"));
    }

    // === Hostname Validation ===

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/", "http://localhost:8080/", "http://127.0.0.1/", "http://127.0.0.1:3000/admin", "http://[::1]/",
            "http://169.254.169.254/latest/meta-data/", "http://metadata.google.internal/computeMetadata/v1/"})
    void testValidateUrl_RejectsInternalHostnames(String url) {
        assertThrows(IllegalArgumentException.class, () -> UrlValidationUtils.validateUrl(url), "Should reject internal hostname: " + url);
    }

    // === isBlockedHostname ===

    @Test
    void testIsBlockedHostname_Localhost() {
        assertTrue(UrlValidationUtils.isBlockedHostname("localhost"));
    }

    @Test
    void testIsBlockedHostname_Loopback() {
        assertTrue(UrlValidationUtils.isBlockedHostname("127.0.0.1"));
    }

    @Test
    void testIsBlockedHostname_IPv6Loopback() {
        assertTrue(UrlValidationUtils.isBlockedHostname("::1"));
    }

    @Test
    void testIsBlockedHostname_DotLocal() {
        assertTrue(UrlValidationUtils.isBlockedHostname("myhost.local"));
    }

    @Test
    void testIsBlockedHostname_DotInternal() {
        assertTrue(UrlValidationUtils.isBlockedHostname("myhost.internal"));
    }

    @Test
    void testIsBlockedHostname_MetadataEndpoint() {
        assertTrue(UrlValidationUtils.isBlockedHostname("169.254.169.254"));
    }

    @Test
    void testIsBlockedHostname_GoogleMetadata() {
        assertTrue(UrlValidationUtils.isBlockedHostname("metadata.google.internal"));
    }

    @Test
    void testIsBlockedHostname_PublicDomain() {
        assertFalse(UrlValidationUtils.isBlockedHostname("example.com"));
    }

    @Test
    void testIsBlockedHostname_PublicDomainWithLocal() {
        // "notlocal" does not end with ".local"
        assertFalse(UrlValidationUtils.isBlockedHostname("notlocal"));
    }

    // === isPrivateAddress ===

    @Test
    void testIsPrivateAddress_Loopback() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertTrue(UrlValidationUtils.isPrivateAddress(loopback));
    }

    @Test
    void testIsPrivateAddress_SiteLocal_10() throws Exception {
        InetAddress addr = InetAddress.getByName("10.0.0.1");
        assertTrue(UrlValidationUtils.isPrivateAddress(addr));
    }

    @Test
    void testIsPrivateAddress_SiteLocal_192() throws Exception {
        InetAddress addr = InetAddress.getByName("192.168.1.1");
        assertTrue(UrlValidationUtils.isPrivateAddress(addr));
    }

    @Test
    void testIsPrivateAddress_SiteLocal_172() throws Exception {
        InetAddress addr = InetAddress.getByName("172.16.0.1");
        assertTrue(UrlValidationUtils.isPrivateAddress(addr));
    }

    @Test
    void testIsPrivateAddress_LinkLocal() throws Exception {
        InetAddress addr = InetAddress.getByName("169.254.1.1");
        assertTrue(UrlValidationUtils.isPrivateAddress(addr));
    }

    // === isValidHttpUrl edge cases ===

    @Test
    void testIsValidHttpUrl_RejectsNoHost() {
        assertFalse(UrlValidationUtils.isValidHttpUrl("http://"));
    }

    @Test
    void testIsValidHttpUrl_RejectsJustPath() {
        assertFalse(UrlValidationUtils.isValidHttpUrl("/path/to/file"));
    }

    @Test
    void testIsValidHttpUrl_RejectsMalformed() {
        assertFalse(UrlValidationUtils.isValidHttpUrl("ht tp://example.com"));
    }
}
