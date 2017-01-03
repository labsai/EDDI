package ai.labs.parser.rest;

import io.swagger.annotations.*;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Api
@Path("parser")
public interface IRestSemanticParser {

    @ApiParam(name = "configId", example = "507f1f77bcf86cd799439011", required = true)
    @ApiResponses({
            @ApiResponse(code = 202, message = "parsing job has been received and will be executed",
                    responseHeaders = {
                            @ResponseHeader(name = "location", description = "uri of parsing job")
                    }),
            @ApiResponse(code = 400, message = "missing configId of parser configuration document")})
    @POST
    @Path("{configId}")
    Response parse(@PathParam("configId") String configId, String sentence);


    @ApiParam(name = "solutionId", example = "507f1f77bcf86cd799439011", required = true)
    @ApiResponses({
            @ApiResponse(code = 202, message = "parsing job is still in progress",
                    responseHeaders = {
                            @ResponseHeader(name = "location", description = "uri of parsing job")
                    }),
            @ApiResponse(code = 200, message = "parsing job has finished"),
            @ApiResponse(code = 400, message = "missing solutionId")})
    @GET
    @Path("{solutionId}")
    Response readSolution(@PathParam("solutionId") String solutionId);
}
