package ai.labs.parser.rest;

import ai.labs.parser.rest.model.Solution;
import io.swagger.annotations.*;

import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */

@Api(value = "Configurations -> Endpoint Parser Only", authorizations = {@Authorization(value = "eddi_auth")})

@Path("/parser")
public interface IRestSemanticParser {

    @ApiParam(name = "parserId", example = "507f1f77bcf86cd799439011", required = true)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Solution result of the parsing job as json array"),
            @ApiResponse(code = 400, message = "missing parserId of parser configuration document")})
    @POST
    @Path("{parserId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "returns an array of found solutions", response = Solution.class, responseContainer = "List")
    void parse(@PathParam("parserId") String parserId,
               @ApiParam(name = "version", required = true, format = "integer", example = "1")
               @QueryParam("version") Integer version,
               String sentence, @Suspended AsyncResponse asyncResponse);
}
