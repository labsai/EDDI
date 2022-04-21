package ai.labs.eddi.modules.nlp;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */

@Path("/parser")
public interface IRestSemanticParser {

    @Parameter(name = "parserId", example = "507f1f77bcf86cd799439011", required = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solution result of the parsing job as json array"),
            @ApiResponse(responseCode = "400", description = "missing parserId of parser configuration document")})
    @POST
    @Path("{parserId}")
    @Produces(MediaType.APPLICATION_JSON)
    void parse(@PathParam("parserId") String parserId,
               @Parameter(name = "version", required = true, example = "1")
               @QueryParam("version") Integer version,
               String sentence, @Suspended AsyncResponse asyncResponse);
}
