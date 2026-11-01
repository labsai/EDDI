/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.rest;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingException;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandUpdate;
import ai.labs.eddi.configs.branding.model.BrandingView;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The admin branding endpoints: a thin layer over {@link BrandingService},
 * which owns validation, locking, concurrency and the audit line. A refused
 * write answers its status with {@code {"error": "...", "violations": [...]}}
 * and, for a revision conflict, the {@code currentRevision}.
 */
@ApplicationScoped
public class RestBrandingAdmin implements IRestBrandingAdmin {

    private final BrandingService branding;
    private final SecurityIdentity identity;

    @Inject
    public RestBrandingAdmin(BrandingService branding, SecurityIdentity identity) {
        this.branding = branding;
        this.identity = identity;
    }

    @Override
    public BrandingView readBrand() {
        return branding.view(BrandRequest.NONE);
    }

    @Override
    public Response updateBrand(BrandUpdate update) {
        if (update == null) {
            return error(BrandingException.INVALID, Map.of("error", "A brand document is required."));
        }
        return answer(() -> branding.update(BrandRequest.NONE, update, user()));
    }

    @Override
    public Response resetBrand() {
        return answer(() -> branding.reset(BrandRequest.NONE, user()));
    }

    @Override
    public Response uploadAsset(String slot, byte[] data) {
        BrandAssetSlot target = slot(slot);
        return answer(() -> branding.putAsset(BrandRequest.NONE, target, data, user()));
    }

    @Override
    public Response deleteAsset(String slot) {
        BrandAssetSlot target = slot(slot);
        return answer(() -> branding.deleteAsset(BrandRequest.NONE, target, user()));
    }

    private static BrandAssetSlot slot(String slot) {
        return BrandAssetSlot.fromId(slot)
                .orElseThrow(() -> new NotFoundException("No brand image slot '" + slot + "'; use logo, logoDark, icon, favicon or ogImage."));
    }

    private static Response answer(Supplier<BrandingView> write) {
        try {
            return Response.ok(write.get(), MediaType.APPLICATION_JSON_TYPE).build();
        } catch (BrandingException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            if (!e.violations().isEmpty()) {
                body.put("violations", e.violations());
            }
            if (e.currentRevision() != null) {
                body.put("currentRevision", e.currentRevision());
            }
            return error(e.status(), body);
        }
    }

    private static Response error(int status, Map<String, ?> body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }

    private String user() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return "anonymous";
        }
        return identity.getPrincipal().getName();
    }
}
