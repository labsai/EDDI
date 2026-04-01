package ai.labs.eddi.modules.nlp;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;

/**
 * Standalone semantic parser endpoint for NLP evaluation.
 */
@Path("/parser")
@Tag(name = "Standalone NLP")
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
