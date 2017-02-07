package ai.labs.resources.rest.documentdescriptor;

import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.documentdescriptor.model.SimpleDocumentDescriptor;
import ai.labs.resources.rest.method.PATCH;
import ai.labs.resources.rest.patch.PatchInstruction;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/descriptorstore/descriptors")
public interface IRestDocumentDescriptorStore {
    String resourceURI = "eddi://ai.labs.descriptor/descriptorstore/descriptors/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Deprecated
    List<DocumentDescriptor> readDescriptors(@QueryParam("type") @DefaultValue("") String type,
                                             @QueryParam("filter") @DefaultValue("") String filter,
                                             @QueryParam("index") @DefaultValue("0") Integer index,
                                             @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    DocumentDescriptor readDescriptor(@PathParam("id") String id, @QueryParam("version") Integer version) throws Exception;

    @GET
    @Path("/{id}/simple")
    @Produces(MediaType.APPLICATION_JSON)
    SimpleDocumentDescriptor readSimpleDescriptor(@PathParam("id") String id, @QueryParam("version") Integer version);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    void patchDescriptor(@PathParam("id") String id, @QueryParam("version") Integer version, PatchInstruction<DocumentDescriptor> patchInstruction);
}
