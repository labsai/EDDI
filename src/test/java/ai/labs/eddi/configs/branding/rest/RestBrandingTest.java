/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.rest;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.BrandingTestSupport;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandUpdate;
import ai.labs.eddi.configs.branding.model.BrandColors;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ai.labs.eddi.configs.branding.BrandingTestSupport.PNG;
import static ai.labs.eddi.configs.branding.BrandingTestSupport.svg;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestBrandingTest {

    private BrandingService branding;
    private RestBranding rest;

    @BeforeEach
    void setUp() {
        branding = new BrandingService(new BrandingTestSupport.MemoryStore(), new BrandingTestSupport.Config());
        rest = new RestBranding(branding);
    }

    @Test
    void scriptIsUncachedJavascript() {
        Response response = rest.brandScript();
        assertEquals(200, response.getStatus());
        assertEquals("application/javascript;charset=UTF-8", response.getMediaType().toString());
        assertEquals(RestBranding.NO_CACHE, response.getHeaderString("Cache-Control"));
        assertEquals("nosniff", response.getHeaderString("X-Content-Type-Options"));
        assertTrue(((String) response.getEntity()).startsWith("window.__EDDI_BRAND__={branded:false,"));
    }

    @Test
    void stylesheetIsEmptyButOkUnbrandedAndCarriesTheTokensBranded() {
        Response response = rest.brandStylesheet();
        assertEquals(200, response.getStatus());
        assertEquals("", response.getEntity());
        assertEquals("text/css;charset=UTF-8", response.getMediaType().toString());

        branding.update(BrandRequest.NONE, new BrandUpdate(0L, null, null, null, new BrandColors("#0f766e", null, null, null, null), null,
                null), "a");
        assertTrue(((String) rest.brandStylesheet().getEntity()).contains("--color-primary:#0f766e;"));
    }

    @Test
    void uploadedImageCarriesTheCacheHeaders() {
        branding.putAsset(BrandRequest.NONE, BrandAssetSlot.LOGO, PNG, "a");
        Response response = rest.asset("logo", null);
        assertEquals(200, response.getStatus());
        assertArrayEquals(PNG, (byte[]) response.getEntity());
        assertEquals("image/png", response.getMediaType().toString());
        assertEquals("public, max-age=300", response.getHeaderString("Cache-Control"));
        assertEquals("nosniff", response.getHeaderString("X-Content-Type-Options"));
        assertTrue(response.getHeaderString("ETag").matches("\"sha256:[0-9a-f]{64}\""));
        assertNull(response.getHeaderString("Content-Security-Policy"), "only SVG needs the sandbox");
    }

    @Test
    void svgIsServedSandboxed() {
        branding.putAsset(BrandRequest.NONE, BrandAssetSlot.ICON, svg("<rect/>"), "a");
        Response response = rest.asset("icon", null);
        assertEquals("image/svg+xml", response.getMediaType().toString());
        assertEquals("default-src 'none'; style-src 'unsafe-inline'; sandbox", response.getHeaderString("Content-Security-Policy"));
        assertEquals(svg("<rect/>").length, ((byte[]) response.getEntity()).length);
    }

    @Test
    void favicoFallsBackToTheIcon() {
        branding.putAsset(BrandRequest.NONE, BrandAssetSlot.ICON, PNG, "a");
        assertArrayEquals(PNG, (byte[]) rest.asset("favicon.ico", null).getEntity());
    }

    @Test
    void matchingEtagIsNotModified() {
        branding.putAsset(BrandRequest.NONE, BrandAssetSlot.LOGO, PNG, "a");
        String etag = rest.asset("logo", null).getHeaderString("ETag");
        Response response = rest.asset("logo", "W/\"other\", " + etag);
        assertEquals(304, response.getStatus());
        assertNull(response.getEntity());
        assertEquals(etag, response.getHeaderString("ETag"));
        assertEquals(304, rest.asset("logo", "*").getStatus());
        assertEquals(200, rest.asset("logo", "\"sha256:stale\"").getStatus());
        assertFalse(RestBranding.matches("  ", etag));
    }

    @Test
    void unknownNameIsNotFound() {
        assertEquals(404, rest.asset("../../etc/passwd", null).getStatus());
        assertEquals(404, rest.asset("logoDark", null).getStatus(), "the public name is logo-dark, not the slot id");
    }
}
