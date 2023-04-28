package ai.labs.eddi.configs.propertysetter;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
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
// @Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (0) PropertySetter", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/propertysetterstore/propertysetters")
@Tag(name = "03. Properties", description = "lifecycle extension for package")
public interface IRestPropertySetterStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.property";
    String resourceURI = resourceBaseType + "/propertysetterstore/propertysetters/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(description = "Read JSON Schema for regular propertySetter definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of propertySetter descriptors.")
    List<DocumentDescriptor> readPropertySetterDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                           @QueryParam("index") @DefaultValue("0") Integer index,
                                                           @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read propertySetter.")
    PropertySetterConfiguration readPropertySetter(@PathParam("id") String id,
                                                   @Parameter(name = "version", required = true, example = "1")
                                                   @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update propertySetter.")
    Response updatePropertySetter(@PathParam("id") String id,
                                  @Parameter(name = "version", required = true, example = "1")
                                  @QueryParam("version") Integer version, PropertySetterConfiguration propertySetterConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create propertySetter.")
    Response createPropertySetter(PropertySetterConfiguration propertySetterConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this propertySetter.")
    Response duplicatePropertySetter(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete propertySetter.")
    Response deletePropertySetter(@PathParam("id") String id,
                                  @Parameter(name = "version", required = true, example = "1")
                                  @QueryParam("version") Integer version);
}
