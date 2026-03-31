package ai.labs.eddi.configs.dictionary;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/expressions")
@Tag(name = "Dictionary")
public interface IRestExpression {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read expressions.")
    List<String> readExpressions(@QueryParam("workflowId") String workflowId, @QueryParam("workflowVersion") Integer workflowVersion,
            @QueryParam("filter") @DefaultValue("") String filter, @QueryParam("limit") @DefaultValue("20") Integer limit);
}
