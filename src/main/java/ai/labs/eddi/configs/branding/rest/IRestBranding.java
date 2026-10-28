/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.rest;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The brand as the browser loads it: fixed, same-origin URLs, so the CSP never
 * widens. Public because the shells and their loaders need these before a token
 * exists; they expose only what the shells already show.
 * <p>
 * Hidden from the OpenAPI document like the shells themselves: they are page
 * assets, not API operations.
 */
@Path("/branding")
// deny-unannotated-endpoints denies anything unannotated, and /branding/* is
// also on the static-assets permit list.
@PermitAll
public interface IRestBranding {

    /**
     * {@code window.__EDDI_BRAND__}. Uncached, like the other runtime config
     * scripts, so a rebrand shows on the next page load.
     */
    @GET
    @Path("/__brand__.js")
    @Produces("application/javascript")
    @Operation(hidden = true)
    Response brandScript();

    /**
     * The brand's colour tokens, loaded after each bundle's stylesheet; empty when
     * no colour is set.
     */
    @GET
    @Path("/brand.css")
    @Produces("text/css")
    @Operation(hidden = true)
    Response brandStylesheet();

    /**
     * The current image for a slot, or the bundled EDDI one: {@code logo},
     * {@code logo-dark}, {@code icon}, {@code favicon.ico}, {@code og-image}.
     */
    @GET
    @Path("/{asset: logo|logo-dark|icon|favicon\\.ico|og-image}")
    @Operation(hidden = true)
    Response asset(@PathParam("asset") String asset, @HeaderParam("If-None-Match") String ifNoneMatch);
}
