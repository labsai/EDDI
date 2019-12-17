package ai.labs.resources.rest.config.propertysetter;

import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.config.propertysetter.model.PropertySetterConfiguration;
import io.swagger.annotations.Api;
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
import java.util.Map;

/**
 * @author ginccc
 */
@Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (0) PropertySetter", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/propertysetterstore/propertysetters")
public interface IRestPropertySetterStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.property/propertysetterstore/propertysetters/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = Map.class, message = "JSON Schema (for validation).")
    @ApiOperation(value = "Read JSON Schema for regular propertySetter definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of propertySetter descriptors.")
    List<DocumentDescriptor> readPropertySetterDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                           @QueryParam("index") @DefaultValue("0") Integer index,
                                                           @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read propertySetter.")
    PropertySetterConfiguration readPropertySetter(@PathParam("id") String id,
                                                   @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                                   @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update propertySetter.")
    Response updatePropertySetter(@PathParam("id") String id,
                                  @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                  @QueryParam("version") Integer version, PropertySetterConfiguration propertySetterConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create propertySetter.")
    Response createPropertySetter(PropertySetterConfiguration propertySetterConfiguration);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate this propertySetter.")
    Response duplicatePropertySetter(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @ApiOperation(value = "Delete propertySetter.")
    Response deletePropertySetter(@PathParam("id") String id,
                                  @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                  @QueryParam("version") Integer version);
}
