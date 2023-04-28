package ai.labs.eddi.configs.regulardictionary;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.regulardictionary.model.RegularDictionaryConfiguration;
import ai.labs.eddi.models.DocumentDescriptor;
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
// @Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (1) Regular Dictionary", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/regulardictionarystore/regulardictionaries")
@Tag(name = "01. Regular Dictionary", description = "lifecycle extension for package")
public interface IRestRegularDictionaryStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(description = "Read JSON Schema for regular dictionary definition.")
    Response readJsonSchema();

    @GET
    @Path("/descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "Array of DocumentDescriptors")
    @Operation(description = "Read list of regular dictionary descriptors.")
    List<DocumentDescriptor> readRegularDictionaryDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                              @QueryParam("index") @DefaultValue("0") Integer index,
                                                              @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "configuration of regular dictionary")
    @Operation(description = "Read regular dictionary.")
    RegularDictionaryConfiguration readRegularDictionary(@PathParam("id") String id,
                                                         @Parameter(name = "version", required = true, example = "1")
                                                         @QueryParam("version") Integer version,
                                                         @QueryParam("filter") @DefaultValue("") String filter,
                                                         @QueryParam("order") @DefaultValue("") String order,
                                                         @QueryParam("index") @DefaultValue("0") Integer index,
                                                         @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}/expressions")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read expressions of regular dictionary.")
    List<String> readExpressions(@PathParam("id") String id,
                                 @Parameter(name = "version", required = true, example = "1")
                                 @QueryParam("version") Integer version,
                                 @QueryParam("filter") @DefaultValue("") String filter,
                                 @QueryParam("order") @DefaultValue("") String order,
                                 @QueryParam("index") @DefaultValue("0") Integer index,
                                 @QueryParam("limit") @DefaultValue("20") Integer limit);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update regular dictionary.")
    Response updateRegularDictionary(@PathParam("id") String id,
                                     @Parameter(name = "version", required = true, example = "1")
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
    @Operation(description = "Create regular dictionary.")
    Response createRegularDictionary(RegularDictionaryConfiguration regularDictionaryConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate regular dictionary.")
    Response duplicateRegularDictionary(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete regular dictionary.")
    Response deleteRegularDictionary(@PathParam("id") String id,
                                     @Parameter(name = "version", required = true, example = "1")
                                     @QueryParam("version") Integer version);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Partial update regular dictionary.")
    Response patchRegularDictionary(@PathParam("id") String id,
                                    @Parameter(name = "version", required = true, example = "1")
                                    @QueryParam("version") Integer version,
                                    List<PatchInstruction<RegularDictionaryConfiguration>> patchInstructions);
}
