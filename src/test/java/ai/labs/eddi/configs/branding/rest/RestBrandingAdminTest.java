/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.rest;

import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.BrandingTestSupport;
import ai.labs.eddi.configs.branding.model.BrandUpdate;
import ai.labs.eddi.configs.branding.model.BrandingView;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;

import static ai.labs.eddi.configs.branding.BrandingTestSupport.PNG;
import static ai.labs.eddi.configs.branding.BrandingTestSupport.svg;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestBrandingAdminTest {

    private BrandingTestSupport.MemoryStore store;
    private BrandingTestSupport.Config config;
    private SecurityIdentity identity;

    @BeforeEach
    void setUp() {
        store = new BrandingTestSupport.MemoryStore();
        config = new BrandingTestSupport.Config();
        identity = mock(SecurityIdentity.class);
        Principal principal = () -> "admin";
        when(identity.getPrincipal()).thenReturn(principal);
    }

    private RestBrandingAdmin rest() {
        return new RestBrandingAdmin(new BrandingService(store, config), identity);
    }

    @Test
    void adminOnlyAndThePublicEndpointsArePublic() {
        assertArrayEquals(new String[]{"eddi-admin"}, IRestBrandingAdmin.class.getAnnotation(RolesAllowed.class).value());
        assertNotNull(IRestBranding.class.getAnnotation(PermitAll.class));
    }

    @Test
    void updateRecordsTheUser() {
        Response response = rest().updateBrand(new BrandUpdate(0L, "Acme", null, null, null, null, null));
        assertEquals(200, response.getStatus());
        assertEquals("Acme", ((BrandingView) response.getEntity()).effective().productName());
        assertEquals("admin", store.document.updatedBy());
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidValueIs400WithViolations() {
        Response response = rest().updateBrand(new BrandUpdate(0L, "<script>", null, null, null, null, null));
        assertEquals(400, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertTrue(body.get("error").toString().contains("productName"));
        assertNotNull(body.get("violations"));
        assertEquals(400, rest().updateBrand(null).getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleRevisionIs409WithTheCurrentRevision() {
        RestBrandingAdmin rest = rest();
        rest.updateBrand(new BrandUpdate(0L, "One", null, null, null, null, null));
        Response response = rest.updateBrand(new BrandUpdate(0L, "Two", null, null, null, null, null));
        assertEquals(409, response.getStatus());
        assertEquals(1L, ((Map<String, Object>) response.getEntity()).get("currentRevision"));
    }

    @Test
    void lockedIs409() {
        config.locked = true;
        assertEquals(409, rest().updateBrand(new BrandUpdate(0L, "X", null, null, null, null, null)).getStatus());
        assertEquals(409, rest().resetBrand().getStatus());
        assertEquals(409, rest().uploadAsset("logo", PNG).getStatus());
        assertEquals(409, rest().deleteAsset("logo").getStatus());
        assertTrue(rest().readBrand().locked());
    }

    @Test
    void assetUploadStatuses() {
        RestBrandingAdmin rest = rest();
        assertEquals(200, rest.uploadAsset("logoDark", PNG).getStatus());
        assertEquals(400, rest.uploadAsset("logo", svg("<script/>")).getStatus());
        assertEquals(415, rest.uploadAsset("logo", "GIF89a".getBytes(StandardCharsets.US_ASCII)).getStatus());
        assertEquals(413, rest.uploadAsset("logo", new byte[600 * 1024]).getStatus());
        assertEquals(200, rest.deleteAsset("logoDark").getStatus());
        assertThrows(NotFoundException.class, () -> rest.uploadAsset("../logo", PNG));
        assertThrows(NotFoundException.class, () -> rest.deleteAsset("logo-dark"));
    }

    @Test
    void anonymousWritesAreAttributedAsSuch() {
        identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        rest().updateBrand(new BrandUpdate(0L, "Acme", null, null, null, null, null));
        assertEquals("anonymous", store.document.updatedBy());
        assertEquals(200, rest().resetBrand().getStatus());
    }
}
