/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.rest;

import ai.labs.eddi.configs.branding.model.BrandUpdate;
import ai.labs.eddi.configs.branding.model.BrandingView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

/**
 * Admin API for the deployment's brand (white-labeling): product name, colours,
 * toggles and images. {@code eddi-admin} only — a brand is deployment identity,
 * and REST has no role hierarchy. Deliberately not on the Platform Operator's
 * allow-list (plan R9).
 */
@Path("/administration/branding")
@Tag(name = "Operations / Branding", description = "White-label the Manager, Chat UI and shells")
@RolesAllowed("eddi-admin")
public interface IRestBrandingAdmin {

    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read the brand", description = "The stored, editable document (null fields are unset), the brand in effect "
            + "after the eddi.branding.* seed and the EDDI defaults are applied, whether the brand is locked by "
            + "eddi.branding.locked=true, and its source: database, properties or default.")
    @APIResponse(responseCode = "200", description = "The brand.", content = @Content(schema = @Schema(implementation = BrandingView.class)))
    BrandingView readBrand();

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Replace the brand's text, colours and toggles", description = "Replaces productName (max 40), shortName "
            + "(max 12), tagline (max 120), colors and the poweredBy/showUpdateCheck toggles; images are set per slot. A null "
            + "or empty field is unset, so the seed property or the EDDI default applies. Names may not contain '<', '>' "
            + "or control characters. Colours must be #rgb, #rrggbb, or rgb()/hsl()/oklch() with plain numbers. "
            + "Send the revision you read: a different current revision answers 409. A low-contrast foreground is "
            + "accepted and reported in warnings.")
    @APIResponse(responseCode = "200", description = "The new brand, with warnings.",
                 content = @Content(schema = @Schema(implementation = BrandingView.class)))
    @APIResponse(responseCode = "400", description = "A value was rejected; violations lists which and why.")
    @APIResponse(responseCode = "409", description = "The brand is locked, or it changed since the given revision.")
    Response updateBrand(BrandUpdate update);

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Reset the brand", description = "Clears every stored value and image, so the eddi.branding.* seed "
            + "or the EDDI brand applies again. The revision still advances.")
    @APIResponse(responseCode = "200", description = "The brand after the reset.",
                 content = @Content(schema = @Schema(implementation = BrandingView.class)))
    @APIResponse(responseCode = "409", description = "The brand is locked.")
    Response resetBrand();

    @PUT
    @Path("/assets/{slot}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Upload a brand image", description = "The raw image as the request body. PNG, WebP or SVG (ICO for the "
            + "favicon slot only), decided by the file's content, not its name or Content-Type; at most 512 KB. An SVG "
            + "with script, foreignObject, event handlers, javascript: URLs, entities, character references or external "
            + "references is refused.")
    @APIResponse(responseCode = "200", description = "The brand with the new image's hash.",
                 content = @Content(schema = @Schema(implementation = BrandingView.class)))
    @APIResponse(responseCode = "400", description = "An SVG that could run script or load something.")
    @APIResponse(responseCode = "404", description = "Not a slot.")
    @APIResponse(responseCode = "409", description = "The brand is locked.")
    @APIResponse(responseCode = "413", description = "Larger than 512 KB.")
    @APIResponse(responseCode = "415", description = "Not a type the slot accepts.")
    // @formatter:off
    Response uploadAsset(
            @Parameter(description = "logo, logoDark, icon, favicon or ogImage") @PathParam("slot") String slot,
            byte[] data);
    // @formatter:on

    @DELETE
    @Path("/assets/{slot}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Remove a brand image", description = "Reverts the slot to the seed image or the bundled EDDI one.")
    @APIResponse(responseCode = "200", description = "The brand without the image.",
                 content = @Content(schema = @Schema(implementation = BrandingView.class)))
    @APIResponse(responseCode = "404", description = "Not a slot.")
    @APIResponse(responseCode = "409", description = "The brand is locked.")
    Response deleteAsset(@Parameter(description = "logo, logoDark, icon, favicon or ogImage")
    @PathParam("slot") String slot);
}
