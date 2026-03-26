package ai.labs.eddi.configs.descriptors;

import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.SimpleDocumentDescriptor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/descriptorstore/descriptors")
@Tag(name = "Descriptors")
public interface IRestDocumentDescriptorStore {
    String DESCRIPTOR_STORE_PATH = "/descriptorstore/descriptors/";
    String resourceURI = "eddi://ai.labs.descriptor" + DESCRIPTOR_STORE_PATH;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of descriptors.")
    List<DocumentDescriptor> readDescriptors(@QueryParam("type") @DefaultValue("") String type, @QueryParam("filter") @DefaultValue("") String filter,
            @QueryParam("index") @DefaultValue("0") Integer index, @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read descriptor.")
    DocumentDescriptor readDescriptor(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version);

    @GET
    @Path("/{id}/simple")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read simple descriptor.")
    SimpleDocumentDescriptor readSimpleDescriptor(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Partial update descriptor.")
    void patchDescriptor(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
            PatchInstruction<DocumentDescriptor> patchInstruction);
}
