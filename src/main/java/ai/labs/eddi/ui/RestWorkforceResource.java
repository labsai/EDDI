/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Serves workforce.html, branded (see {@link BrandedShellRenderer}), for all
 * /workforce/** paths. React Router handles client-side routing from there.
 */
@ApplicationScoped
public class RestWorkforceResource implements IRestWorkforceResource {

    private final BrandedShellRenderer shellRenderer;
    private final BrandingService branding;

    @Inject
    public RestWorkforceResource(BrandedShellRenderer shellRenderer, BrandingService branding) {
        this.shellRenderer = shellRenderer;
        this.branding = branding;
    }

    @Override
    public Response manifest() {
        String manifest = WorkforceManifest.render(branding.current(BrandRequest.NONE), branding.asset(BrandRequest.NONE, BrandAssetSlot.ICON));
        return Response.ok(manifest, "application/manifest+json").build();
    }

    @Override
    public Response viewDefault() {
        return viewHtml();
    }

    @Override
    public Response viewHtml() {
        return shellRenderer.render(BrandedShell.WORKFORCE, BrandRequest.NONE)
                .map(page -> Response.ok(page, MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build())
                .orElseGet(() -> Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }
}
