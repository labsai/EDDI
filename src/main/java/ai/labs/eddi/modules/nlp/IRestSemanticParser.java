/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;

/**
 * Standalone semantic parser endpoint for NLP evaluation.
 * <p>
 * An authoring tool for testing dictionaries and parser configurations by hand
 * (curl, scripts; the Manager does not call it), so it carries the same roles
 * as the parser configuration store it evaluates. It used to declare none,
 * which left it open to every authenticated principal, including one holding no
 * EDDI role at all.
 */
@Path("/parser")
@RolesAllowed({"eddi-admin", "eddi-editor"})
@Tag(name = "Tools / NLP", description = "Standalone semantic parser")
public interface IRestSemanticParser {

    @POST
    @Path("{parserId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Parse a sentence", description = "Parses the given sentence using the specified parser configuration.")
    @APIResponse(responseCode = "200", description = "Parsing result as JSON array.")
    @APIResponse(responseCode = "400", description = "Missing parserId or invalid configuration.")
    void parse(@Parameter(name = "parserId", example = "507f1f77bcf86cd799439011", required = true)
    @PathParam("parserId") String parserId,
               @Parameter(name = "version", required = true, example = "1")
               @QueryParam("version") Integer version, String sentence,
               @Suspended AsyncResponse asyncResponse);
}
