/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import ai.labs.eddi.engine.httpclient.outbound.OutboundSettings.Protocol;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reading the outbound HTTP settings at startup, and refusing values that could
 * not be honoured.
 */
class OutboundSettingsFromConfigTest {

    private static Config config(Map<String, Object> values) {
        Config config = mock(Config.class);
        when(config.getOptionalValue(anyString(), any())).thenAnswer(i -> Optional.ofNullable(values.get(i.<String>getArgument(0))));
        return config;
    }

    private static OutboundSettings read(Map<String, Object> values) {
        return OutboundSettings.fromConfig(config(values));
    }

    @Test
    void theDefaultsAreTenSecondsAutoAndNoProxy() {
        OutboundSettings settings = read(Map.of());
        assertEquals(OutboundSettings.JDK_ENGINE, settings.engine());
        var profile = settings.profile(Profile.DEFAULT);
        assertEquals(Duration.ofSeconds(10), profile.connectTimeout());
        assertNull(profile.totalTimeout());
        assertEquals(Duration.ofSeconds(30), profile.budgetFloor());
        assertEquals(Protocol.AUTO, profile.protocol());
        assertNull(profile.tlsConfigurationName());
        assertFalse(settings.proxy().enabled());
        assertEquals("ProxySettings[none]", settings.proxy().toString());
    }

    @Test
    void theLegacyConnectTimeoutIsHonouredAndTheNewKeyWins() {
        assertEquals(Duration.ofMillis(2500), read(Map.of("httpClient.connectTimeoutInMillis", 2500)).profile(Profile.DEFAULT).connectTimeout());
        Map<String, Object> both = Map.of("httpClient.connectTimeoutInMillis", 2500, "eddi.http.outbound.default.connect-timeout",
                Duration.ofSeconds(4));
        assertEquals(Duration.ofSeconds(4), read(both).profile(Profile.DEFAULT).connectTimeout());
    }

    @Test
    void everyProfileSharesTheDefaultSettings() {
        Map<String, Object> values = new HashMap<>();
        values.put("eddi.http.outbound.engine", " JDK ");
        values.put("eddi.http.outbound.default.total-timeout", Duration.ofSeconds(45));
        values.put("eddi.http.outbound.default.protocol", " HTTP2 ");
        values.put("eddi.http.outbound.default.tls.configuration-name", " corporate ");
        OutboundSettings settings = read(values);
        for (Profile profile : Profile.values()) {
            assertEquals(Duration.ofSeconds(45), settings.profile(profile).budgetFloor());
            assertEquals(Protocol.HTTP2, settings.profile(profile).protocol());
            assertEquals("corporate", settings.profile(profile).tlsConfigurationName());
        }
        values.put("eddi.http.outbound.default.protocol", "http1");
        values.put("eddi.http.outbound.default.tls.configuration-name", "  ");
        assertEquals(Protocol.HTTP1, read(values).profile(Profile.DEFAULT).protocol());
        assertNull(read(values).profile(Profile.DEFAULT).tlsConfigurationName(), "a blank name means the JVM's trust");
    }

    @Test
    void aProxyIsReadWithItsBypassListAndCredentialsNeverPrinted() {
        Map<String, Object> values = new HashMap<>();
        values.put("eddi.http.outbound.proxy.host", " proxy.corp ");
        values.put("eddi.http.outbound.proxy.port", 3128);
        values.put("eddi.http.outbound.proxy.non-proxy-hosts", "localhost| *.corp ||10.*");
        values.put("eddi.http.outbound.proxy.credentials", "user:secret");
        var proxy = read(values).proxy();
        assertTrue(proxy.enabled());
        assertEquals("proxy.corp", proxy.host());
        assertEquals(3128, proxy.port());
        assertEquals(List.of("localhost", "*.corp", "10.*"), proxy.nonProxyHosts());
        assertEquals("user:secret", proxy.credentials());
        assertEquals("ProxySettings[proxy.corp:3128]", proxy.toString());

        values.remove("eddi.http.outbound.proxy.non-proxy-hosts");
        values.put("eddi.http.outbound.proxy.credentials", " ");
        var bare = read(values).proxy();
        assertEquals(List.of(), bare.nonProxyHosts());
        assertNull(bare.credentials());
    }

    @Test
    void valuesThatCannotBeHonouredFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> read(Map.of("eddi.http.outbound.engine", "netty")));
        assertThrows(IllegalStateException.class, () -> read(Map.of("eddi.http.outbound.default.connect-timeout", Duration.ZERO)));
        assertThrows(IllegalStateException.class, () -> read(Map.of("eddi.http.outbound.default.total-timeout", Duration.ofSeconds(-1))));
        var protocol = assertThrows(IllegalStateException.class, () -> read(Map.of("eddi.http.outbound.default.protocol", "spdy")));
        assertTrue(protocol.getMessage().contains("'spdy'"), protocol.getMessage());
        var noPort = assertThrows(IllegalStateException.class, () -> read(Map.of("eddi.http.outbound.proxy.host", "proxy")));
        assertTrue(noPort.getMessage().contains("proxy.port is not"), noPort.getMessage());
        assertThrows(IllegalStateException.class, () -> read(Map.of("eddi.http.outbound.proxy.host", "proxy", "eddi.http.outbound.proxy.port", 0)));
        assertThrows(IllegalStateException.class,
                () -> read(Map.of("eddi.http.outbound.proxy.host", "proxy", "eddi.http.outbound.proxy.port", 65536)));
    }

    @Test
    void settingsMustCoverEveryProfile() {
        var e = assertThrows(IllegalArgumentException.class, () -> new OutboundSettings(OutboundSettings.JDK_ENGINE, Map.of(),
                OutboundSettings.ProxySettings.NONE));
        assertTrue(e.getMessage().startsWith("no settings for profile"), e.getMessage());
        assertEquals(Duration.ofSeconds(3), OutboundSettings.defaults(Duration.ofSeconds(1)).profile(Profile.DEFAULT).budgetFloor());
    }
}
