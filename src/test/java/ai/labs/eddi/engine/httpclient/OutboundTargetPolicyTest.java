/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import ai.labs.eddi.engine.security.SelfUrlResolver;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one place {@code eddi.security.ssrf-protection.enabled} is applied to
 * configured endpoints — LLM base URLs, embedding endpoints, vector-store
 * hosts, spec locations. No DNS: hostnames resolve through a fixed table.
 */
@DisplayName("OutboundTargetPolicy")
class OutboundTargetPolicyTest {

    private static final String PRIVATE_HOST = "llm.corp.example";
    private static final String PUBLIC_HOST = "api.example.com";
    private static final String METADATA_HOST = "meta.corp.example";

    private static final Map<String, byte[]> TABLE = Map.of(
            PRIVATE_HOST, new byte[]{10, 0, 0, 5},
            PUBLIC_HOST, new byte[]{93, (byte) 184, (byte) 216, 34},
            METADATA_HOST, new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254});

    static final UrlValidationUtils.HostResolver RESOLVER = host -> {
        byte[] address = TABLE.get(host);
        if (address == null) {
            throw new UnknownHostException(host);
        }
        return new InetAddress[]{InetAddress.getByAddress(host, address)};
    };

    static OutboundTargetPolicy off() {
        return new OutboundTargetPolicy(false, null, RESOLVER);
    }

    static OutboundTargetPolicy on() {
        return new OutboundTargetPolicy(true, null, RESOLVER);
    }

    static OutboundTargetPolicy onWithSelf(String selfBaseUrl) {
        return new OutboundTargetPolicy(true, new SelfUrlResolver(Optional.of(selfBaseUrl), 7070), RESOLVER);
    }

    @Nested
    @DisplayName("protection off")
    class ProtectionOff {

        @Test
        @DisplayName("a private host is allowed — a local Ollama is the common case")
        void privateAllowed() {
            assertDoesNotThrow(() -> off().validateUrl("http://" + PRIVATE_HOST + ":11434"));
            assertDoesNotThrow(() -> off().validateUrl("http://localhost:11434"));
            assertDoesNotThrow(() -> off().validateHost("10.0.0.5"));
        }

        @Test
        @DisplayName("the cloud metadata service is refused anyway, by name and by resolution")
        void metadataRefused() {
            var e = assertThrows(IllegalArgumentException.class, () -> off().validateUrl("http://169.254.169.254/latest/meta-data/"));
            assertTrue(e.getMessage().contains("instance-metadata"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> off().validateUrl("http://" + METADATA_HOST + "/v1"));
            assertThrows(IllegalArgumentException.class, () -> off().validateHost("169.254.169.254"));
            assertThrows(IllegalArgumentException.class, () -> off().validateHost(METADATA_HOST));
        }

        @Test
        @DisplayName("permissive() is protection off and is what a null field falls back to")
        void permissive() {
            assertFalse(OutboundTargetPolicy.permissive().isEnabled());
            assertSame(OutboundTargetPolicy.permissive(), OutboundTargetPolicy.orPermissive(null));
            OutboundTargetPolicy own = on();
            assertSame(own, OutboundTargetPolicy.orPermissive(own));
        }
    }

    @Nested
    @DisplayName("protection on")
    class ProtectionOn {

        @Test
        @DisplayName("a public host passes")
        void publicAllowed() {
            assertDoesNotThrow(() -> on().validateUrl("https://" + PUBLIC_HOST + "/v1"));
            assertDoesNotThrow(() -> on().validateHost(PUBLIC_HOST));
            assertFalse(on().isPrivateHost(PUBLIC_HOST));
        }

        @Test
        @DisplayName("a host resolving to a private address is refused, as are loopback literals and names")
        void privateRefused() {
            var e = assertThrows(IllegalArgumentException.class, () -> on().validateUrl("http://" + PRIVATE_HOST + ":11434/v1"));
            assertTrue(e.getMessage().contains("private/internal"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> on().validateUrl("http://localhost:11434"));
            assertThrows(IllegalArgumentException.class, () -> on().validateUrl("http://127.0.0.1:11434"));
            assertThrows(IllegalArgumentException.class, () -> on().validateUrl("http://[::1]:11434"));
        }

        @Test
        @DisplayName("a non-http scheme is refused")
        void schemeRefused() {
            assertThrows(IllegalArgumentException.class, () -> on().validateUrl("ftp://" + PUBLIC_HOST + "/spec"));
        }

        @Test
        @DisplayName("a bare host is held to the same address rules: literal, resolved, loopback, unresolvable")
        void hostRefused() {
            var e = assertThrows(IllegalArgumentException.class, () -> on().validateHost("10.0.0.5"));
            assertTrue(e.getMessage().contains("ssrf-protection.enabled=true"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> on().validateHost(PRIVATE_HOST));
            assertThrows(IllegalArgumentException.class, () -> on().validateHost("localhost"));
            assertThrows(IllegalArgumentException.class, () -> on().validateHost("::1"));
            assertThrows(IllegalArgumentException.class, () -> on().validateHost("[::1]"));
            assertThrows(IllegalArgumentException.class, () -> on().validateHost("172.16.0.9:5432"));
            assertTrue(on().isPrivateHost("nowhere.example"), "an unresolvable host is 'cannot tell', which is a refusal");
        }

        @Test
        @DisplayName("a blank host is nothing to check")
        void blankHost() {
            assertDoesNotThrow(() -> on().validateHost(null));
            assertDoesNotThrow(() -> on().validateHost("  "));
        }

        @Test
        @DisplayName("this deployment's own origin (eddi.self.base-url) is the one escape hatch — path and query ignored, other origins not")
        void selfOriginAllowed() {
            OutboundTargetPolicy policy = onWithSelf("http://127.0.0.1:7070");
            assertDoesNotThrow(() -> policy.validateUrl("http://127.0.0.1:7070/v1"));
            assertDoesNotThrow(() -> policy.validateUrl("http://127.0.0.1:7070/v1/chat/completions?x=1"));
            assertThrows(IllegalArgumentException.class, () -> policy.validateUrl("http://127.0.0.1:7071/v1"), "another port is another origin");
            assertThrows(IllegalArgumentException.class, () -> policy.validateUrl("http://localhost:7070/v1"), "another host name is another origin");
            assertThrows(IllegalArgumentException.class, () -> policy.validateUrl("http://" + PRIVATE_HOST + "/v1"));
        }

        @Test
        @DisplayName("the self escape hatch never covers the metadata service, and never applies to a bare host")
        void selfOriginLimits() {
            assertThrows(IllegalArgumentException.class, () -> onWithSelf("http://169.254.169.254:80").validateUrl("http://169.254.169.254/latest"));
            assertThrows(IllegalArgumentException.class, () -> onWithSelf("http://127.0.0.1:7070").validateHost("127.0.0.1"));
        }
    }

    @Test
    @DisplayName("bareHost strips a port and IPv6 brackets but keeps a bare IPv6 literal whole")
    void bareHost() {
        assertEquals("db.example", OutboundTargetPolicy.bareHost(" db.example:5432 "));
        assertEquals("db.example", OutboundTargetPolicy.bareHost("db.example"));
        assertEquals("::1", OutboundTargetPolicy.bareHost("[::1]:5432"));
        assertEquals("::1", OutboundTargetPolicy.bareHost("::1"));
        assertEquals("fd00::1", OutboundTargetPolicy.bareHost("fd00::1"));
        assertNull(OutboundTargetPolicy.bareHost(""));
        assertNull(OutboundTargetPolicy.bareHost(null));
    }
}
