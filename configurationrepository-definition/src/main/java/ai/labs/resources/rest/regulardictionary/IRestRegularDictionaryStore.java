package ai.labs.resources.rest.regulardictionary;

import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.method.PATCH;
import ai.labs.resources.rest.patch.PatchInstruction;
import ai.labs.resources.rest.regulardictionary.model.RegularDictionaryConfiguration;
import io.swagger.annotations.*;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (1) Regular Dictionary", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/regulardictionarystore/regulardictionaries")
public interface IRestRegularDictionaryStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = Map.class, message = "JSON Schema (for validation).")
    @ApiOperation(value = "Read JSON Schema for regular dictionary definition.")
    Response readJsonSchema();

    @GET
    @Path("/descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiImplicitParams({
            @ApiImplicitParam(name = "filter", paramType = "query", dataType = "string", format = "string", example = "<name_of_regular_dictionary>"),
            @ApiImplicitParam(name = "index", paramType = "query", dataType = "integer", format = "integer", example = "0"),
            @ApiImplicitParam(name = "limit", paramType = "query", dataType = "integer", format = "integer", example = "20")})
    @ApiResponse(code = 200, response = DocumentDescriptor.class, responseContainer = "List",
            message = "Array of DocumentDescriptors")
    @ApiOperation(value = "Read list of regular dictionary descriptors.")
    List<DocumentDescriptor> readRegularDictionaryDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                              @QueryParam("index") @DefaultValue("0") Integer index,
                                                              @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = RegularDictionaryConfiguration.class, message = "configuration of regular dictionary")
    @ApiOperation(value = "Read regular dictionary.")
    RegularDictionaryConfiguration readRegularDictionary(@PathParam("id") String id,
                                                         @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                                         @QueryParam("version") Integer version,
                                                         @QueryParam("filter") @DefaultValue("") String filter,
                                                         @QueryParam("order") @DefaultValue("") String order,
                                                         @QueryParam("index") @DefaultValue("0") Integer index,
                                                         @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}/expressions")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read expressions of regular dictionary.")
    List<String> readExpressions(@PathParam("id") String id,
                                 @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                 @QueryParam("version") Integer version,
                                 @QueryParam("filter") @DefaultValue("") String filter,
                                 @QueryParam("order") @DefaultValue("") String order,
                                 @QueryParam("index") @DefaultValue("0") Integer index,
                                 @QueryParam("limit") @DefaultValue("20") Integer limit);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update regular dictionary.")
    Response updateRegularDictionary(@PathParam("id") String id,
                                     @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                     @QueryParam("version") Integer version, RegularDictionaryConfiguration regularDictionaryConfiguration);


    /**
     * example dictionary json config:
     * <p>
     * {
     * "language" : "en",
     * "words" : [
     * {
     * "word" : "hello",
     * "exp" : "greeting(hello)",
     * "frequency" : 0
     * }
     * ],
     * "phrases" : [
     * {
     * "phrase" : "good afternoon",
     * "exp" : "greeting(good_afternoon)"
     * }
     * ]
     * }
     *
     * @param regularDictionaryConfiguration dictionary resource to be created
     * @return no content, http code 201, see Location header for URI of the created resource
     */

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create regular dictionary.")
    Response createRegularDictionary(RegularDictionaryConfiguration regularDictionaryConfiguration);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate regular dictionary.")
    Response duplicateRegularDictionary(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @ApiOperation(value = "Delete regular dictionary.")
    Response deleteRegularDictionary(@PathParam("id") String id,
                                     @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                     @QueryParam("version") Integer version);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Partial update regular dictionary.")
    Response patchRegularDictionary(@PathParam("id") String id,
                                    @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                    @QueryParam("version") Integer version, PatchInstruction<RegularDictionaryConfiguration>[] patchInstructions);
}
