package ai.labs.eddi.configs.parser;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */

// @Api(value = "Configurations -> Endpoint Parser Only", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/parserstore/parsers")
@Tag(name = "12. Standalone NLP", description = "lifecycle extension for package")
public interface IRestParserStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.parser/parserstore/parsers/";

    @GET
    @Path("/descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "Array of DocumentDescriptors")
    @Operation(description = "Read list of parser descriptors.")
    List<DocumentDescriptor> readParserDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                   @QueryParam("index") @DefaultValue("0") Integer index,
                                                   @QueryParam("limit") @DefaultValue("20") Integer limit);


    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "configuration of parser")
    @Operation(description = "Read parser.")
    ParserConfiguration readParser(@PathParam("id") String id,
                                   @Parameter(name = "version", required = true, example = "1")
                                   @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update parser.")
    Response updateParser(@PathParam("id") String id,
                          @Parameter(name = "version", required = true, example = "1")
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
    @Operation(description = "Create parser.")
    Response createParser(ParserConfiguration parserConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this parser.")
    Response duplicateParser(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete parser.")
    Response deleteParser(@PathParam("id") String id,
                          @Parameter(name = "version", required = true, example = "1")
                          @QueryParam("version") Integer version);
}
