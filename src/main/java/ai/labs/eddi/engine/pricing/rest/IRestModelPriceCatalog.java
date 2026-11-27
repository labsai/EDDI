/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing.rest;

import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

/**
 * The deployment price catalog (docs/model-pricing.md). Reading is open to
 * admins and editors — an editor setting up a task wants to see which price
 * applies — but only {@code eddi-admin} writes: the catalog is a budget lever
 * (lowering a price to $0 is raising a budget). The MCP write tool
 * ({@code update_model_prices}) applies only what makes spending stricter and
 * files the rest for an administrator's approval
 * ({@code docs/spend-control-changes.md}); the Platform Operator has no write
 * grant.
 */
@Path("/administration/model-prices")
@Tag(name = "Operations / Model Prices", description = "The deployment's model price catalog and unpriced-call policy")
public interface IRestModelPriceCatalog {

    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Operation(summary = "Read the price catalog", description = "The stored catalog (revision, unpricedPolicy, entries), the "
            + "effective policy (the stricter of the document's and the eddi.llm.pricing.unpriced-policy-floor property), the floor, "
            + "the model types this deployment knows, and staleness information.")
    Response readCatalog();

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "Replace the price catalog", description = "Replaces the whole catalog. Send the revision you read, as "
            + "If-Match or as the document's revision: a different stored revision answers 409. At most 500 entries; model "
            + "patterns allow '*' only as a leading or trailing wildcard; prices are 0..10000 USD per 1M tokens.")
    @APIResponse(responseCode = "200", description = "The stored catalog.")
    @APIResponse(responseCode = "400", description = "Rejected; violations lists which field and why.")
    @APIResponse(responseCode = "409", description = "The catalog changed since the given revision; currentRevision is returned.")
    Response replaceCatalog(@Parameter(description = "The revision the edit was based on")
    @HeaderParam("If-Match") String ifMatch,
                            ModelPriceCatalog catalog);

    @GET
    @Path("/resolve")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Operation(summary = "Which catalog entry prices a model", description = "The entry the catalog matches for this model type "
            + "and model name (exact, then the longest wildcard, then provider '*'), or none.")
    Response resolve(@QueryParam("provider") String provider, @QueryParam("model") String model);

    @GET
    @Path("/reference")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "The bundled reference prices", description = "Read-only list prices shipped with this EDDI release, "
            + "each with its asOf date and source. Never active until an admin adopts them into the catalog.")
    ModelPriceCatalog reference();

    @GET
    @Path("/unpriced")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Operation(summary = "Unpriced models seen on this node", description = "Provider, model, site, count and last-seen time of "
            + "model calls that had no price, on this node since it started (at most 200 rows). The cluster-wide signal is the "
            + "eddi.llm.calls.unpriced metric.")
    Response unpriced();
}
