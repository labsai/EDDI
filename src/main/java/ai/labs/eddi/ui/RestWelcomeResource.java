/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.BrandRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Serves welcome.html, branded (see {@link BrandedShellRenderer}), for all
 * /welcome/** paths. React Router handles client-side routing from there.
 */
@ApplicationScoped
public class RestWelcomeResource implements IRestWelcomeResource {

    private final BrandedShellRenderer shellRenderer;

    @Inject
    public RestWelcomeResource(BrandedShellRenderer shellRenderer) {
        this.shellRenderer = shellRenderer;
    }

    @Override
    public Response viewDefault() {
        return viewHtml();
    }

    @Override
    public Response viewHtml() {
        return shellRenderer.render(BrandedShell.WELCOME, BrandRequest.NONE)
                .map(page -> Response.ok(page, MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build())
                .orElseGet(() -> Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }
}
