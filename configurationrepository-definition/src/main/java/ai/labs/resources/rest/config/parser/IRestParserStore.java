package ai.labs.resources.rest.config.parser;

import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.config.parser.model.ParserConfiguration;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.Authorization;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */

@Api(value = "Configurations -> Endpoint Parser Only", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/parserstore/parsers")
public interface IRestParserStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.parser/parserstore/parsers/";

    @GET
    @Path("/descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiImplicitParams({
            @ApiImplicitParam(name = "filter", paramType = "query", dataType = "string", format = "string", example = "<name_of_parser>"),
            @ApiImplicitParam(name = "index", paramType = "query", dataType = "integer", format = "integer", example = "0"),
            @ApiImplicitParam(name = "limit", paramType = "query", dataType = "integer", format = "integer", example = "20")})
    @ApiResponse(code = 200, response = DocumentDescriptor.class, responseContainer = "List",
            message = "Array of DocumentDescriptors")
    @ApiOperation(value = "Read list of parser descriptors.")
    List<DocumentDescriptor> readParserDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                   @QueryParam("index") @DefaultValue("0") Integer index,
                                                   @QueryParam("limit") @DefaultValue("20") Integer limit);


    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = ParserConfiguration.class, message = "configuration of parser")
    @ApiOperation(value = "Read parser.")
    ParserConfiguration readParser(@PathParam("id") String id,
                                   @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                   @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update parser.")
    Response updateParser(@PathParam("id") String id,
                          @ApiParam(name = "version", required = true, format = "integer", example = "1")
                          @QueryParam("version") Integer version,
                          ParserConfiguration parserConfiguration);

    /**
     * example parser json config:
     * <p>
     * {
     * "extensions": {
     * "dictionaries": [
     * {
     * "type": "eddi://ai.labs.parser.dictionaries.integer"
     * },
     * {
     * "type": "eddi://ai.labs.parser.dictionaries.decimal"
     * },
     * {
     * "type": "eddi://ai.labs.parser.dictionaries.punctuation"
     * },
     * {
     * "type": "eddi://ai.labs.parser.dictionaries.email"
     * },
     * {
     * "type": "eddi://ai.labs.parser.dictionaries.time"
     * },
     * {
     * "type": "eddi://ai.labs.parser.dictionaries.ordinalNumber"
     * },
     * {
     * "type": "eddi://ai.labs.parser.dictionaries.regular",
     * "config": {
     * "uri": "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/<INSERT_ID_OF_DICTIONARY>?version=<VERSION_NUMBER>"
     * }
     * }
     * ],
     * "corrections": [
     * {
     * "type": "eddi://ai.labs.parser.corrections.stemming",
     * "config": {
     * "language": "english",
     * "lookupIfKnown": "false"
     * }
     * },
     * {
     * "type": "eddi://ai.labs.parser.corrections.levenshtein",
     * "config": {
     * "distance": "2"
     * }
     * },
     * {
     * "type": "eddi://ai.labs.parser.corrections.mergedTerms"
     * }
     * ]}
     * }
     *
     * @param parserConfiguration configuration of parser (which dictionaries and which corrections algorithms in which order)
     * @return an array of expressions representing the found solutions
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create parser.")
    Response createParser(ParserConfiguration parserConfiguration);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate this parser.")
    Response duplicateParser(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @ApiOperation(value = "Delete parser.")
    Response deleteParser(@PathParam("id") String id,
                          @ApiParam(name = "version", required = true, format = "integer", example = "1")
                          @QueryParam("version") Integer version);
}
