package ai.labs.resources.rest.documentdescriptor;

import ai.labs.models.DocumentDescriptor;
import ai.labs.models.SimpleDocumentDescriptor;
import ai.labs.resources.rest.method.PATCH;
import ai.labs.resources.rest.patch.PatchInstruction;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "Configurations -> (1) General", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/descriptorstore/descriptors")
public interface IRestDocumentDescriptorStore {
    String resourceURI = "eddi://ai.labs.descriptor/descriptorstore/descriptors/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of descriptors.")
    List<DocumentDescriptor> readDescriptors(@QueryParam("type") @DefaultValue("") String type,
                                             @QueryParam("filter") @DefaultValue("") String filter,
                                             @QueryParam("index") @DefaultValue("0") Integer index,
                                             @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read descriptor.")
    DocumentDescriptor readDescriptor(@PathParam("id") String id,
                                      @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                      @QueryParam("version") Integer version);

    @GET
    @Path("/{id}/simple")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read simple descriptor.")
    SimpleDocumentDescriptor readSimpleDescriptor(@PathParam("id") String id,
                                                  @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                                  @QueryParam("version") Integer version);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Partial update descriptor.")
    void patchDescriptor(@PathParam("id") String id,
                         @ApiParam(name = "version", required = true, format = "integer", example = "1")
                         @QueryParam("version") Integer version, PatchInstruction<DocumentDescriptor> patchInstruction);
}
