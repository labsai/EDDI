package ai.labs.parser.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */

@Api
@Path("/api/v1/parser")
public interface IRestSemanticParser {

    @ApiParam(name = "parserId", example = "507f1f77bcf86cd799439011", required = true)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Solution result of the parsing job as json array"),
            @ApiResponse(code = 400, message = "missing parserId of parser configuration document")})
    @POST
    @Path("{parserId}")
    @Produces(MediaType.APPLICATION_JSON)
    void parse(@PathParam("parserId") String parserId, @QueryParam("version") Integer version,
               String sentence, @Suspended AsyncResponse asyncResponse);
}
