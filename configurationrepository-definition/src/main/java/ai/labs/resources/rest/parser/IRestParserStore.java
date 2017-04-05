package ai.labs.resources.rest.parser;

import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.parser.model.ParserConfiguration;
import io.swagger.annotations.*;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */

@Api(value = "configurations")
@Path("/parserstore/parsers")
public interface IRestParserStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.parser/parserstore/parsers/";

    @GET
    @Path("/descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiImplicitParams({
            @ApiImplicitParam(name = "filter", format = "string", example = "<name_of_parser>"),
            @ApiImplicitParam(name = "index", format = "integer", example = "<at what position should the paging start>"),
            @ApiImplicitParam(name = "limit", format = "integer", example = "<how many results should be returned>")})
    @ApiResponse(code = 200, response = DocumentDescriptor.class, responseContainer = "List",
            message = "Array of DocumentDescriptors")
    List<DocumentDescriptor> readParserDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                   @QueryParam("index") @DefaultValue("0") Integer index,
                                                   @QueryParam("limit") @DefaultValue("20") Integer limit);


    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = ParserConfiguration.class, message = "configuration of parser")
    ParserConfiguration readParser(@PathParam("id") String id,
                                   @ApiParam(name = "version", required = true, format = "integer", example = "1")
                        @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
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
     * @param parserConfiguration configuration of parser (which dictionaries and which correction algorithms in which order)
     * @return an array of expressions representing the found solutions
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createParser(ParserConfiguration parserConfiguration);

    @DELETE
    @Path("/{id}")
    void deleteParser(@PathParam("id") String id,
                      @ApiParam(name = "version", required = true, format = "integer", example = "1")
                      @QueryParam("version") Integer version);
}
