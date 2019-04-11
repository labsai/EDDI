package ai.labs.resources.rest.documentdescriptor;

import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.documentdescriptor.model.SimpleDocumentDescriptor;
import ai.labs.resources.rest.method.PATCH;
import ai.labs.resources.rest.patch.PatchInstruction;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "Configurations -> (1) General")
@Path("/descriptorstore/descriptors")
public interface IRestDocumentDescriptorStore {
    String resourceURI = "eddi://ai.labs.descriptor/descriptorstore/descriptors/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<DocumentDescriptor> readDescriptors(@QueryParam("type") @DefaultValue("") String type,
                                             @QueryParam("filter") @DefaultValue("") String filter,
                                             @QueryParam("index") @DefaultValue("0") Integer index,
                                             @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    DocumentDescriptor readDescriptor(@PathParam("id") String id,
                                      @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                      @QueryParam("version") Integer version);

    @GET
    @Path("/{id}/simple")
    @Produces(MediaType.APPLICATION_JSON)
    SimpleDocumentDescriptor readSimpleDescriptor(@PathParam("id") String id,
                                                  @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                                  @QueryParam("version") Integer version);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    void patchDescriptor(@PathParam("id") String id,
                         @ApiParam(name = "version", required = true, format = "integer", example = "1")
                         @QueryParam("version") Integer version, PatchInstruction<DocumentDescriptor> patchInstruction);
}
