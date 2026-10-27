/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.BrandAssets;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandSource;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import io.quarkiverse.mcp.server.Implementation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrandedMcpServerInfoTest {

    private BrandingService branding;

    private static ResolvedBrand branded(String name) {
        return new ResolvedBrand(3, BrandSource.DATABASE, false, true, name, name, null, BrandColors.EMPTY, true, false,
                BrandAssets.EMPTY);
    }

    @BeforeEach
    void setUp() {
        branding = mock(BrandingService.class);
    }

    @Test
    void unbrandedLeavesTheExtensionsOwnAnswer() {
        when(branding.current(BrandRequest.NONE)).thenReturn(ResolvedBrand.defaults(false));
        assertTrue(new BrandedMcpServerInfo(branding, Optional.empty(), "6.6.0").implementation("<default>").isEmpty());
    }

    @Test
    void aBrandNamesTheServerAndFollowsARebrand() {
        when(branding.current(BrandRequest.NONE)).thenReturn(branded("Acme Assist"), branded("Zebra Labs"));
        var info = new BrandedMcpServerInfo(branding, Optional.empty(), "6.6.0");

        Implementation first = info.implementation("<default>").orElseThrow();
        assertEquals("Acme Assist", first.name());
        assertEquals("Acme Assist", first.title());
        assertEquals("6.6.0", first.version());
        assertEquals("Zebra Labs", info.implementation("<default>").orElseThrow().name());
    }

    @Test
    void anExplicitServerInfoNameWins() {
        when(branding.current(BrandRequest.NONE)).thenReturn(branded("Acme Assist"));
        assertTrue(new BrandedMcpServerInfo(branding, Optional.of("acme-mcp"), "6.6.0").implementation("<default>").isEmpty());
        // A blank property is no choice at all.
        assertEquals("Acme Assist",
                new BrandedMcpServerInfo(branding, Optional.of(" "), "6.6.0").implementation("<default>").orElseThrow().name());
    }

    @Test
    void anUnreadableBrandNeverFailsInitialize() {
        when(branding.current(BrandRequest.NONE)).thenThrow(new IllegalStateException("store down"));
        assertTrue(new BrandedMcpServerInfo(branding, Optional.empty(), "6.6.0").implementation("<default>").isEmpty());
    }
}
