package ai.labs.eddi.configs.admin;

import ai.labs.eddi.configs.admin.model.OrphanReport;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

/**
 * REST API for detecting and cleaning up orphaned resources.
 *
 * <p>An orphan is a resource (package, behavior set, HTTP calls config, etc.)
 * that exists in the database but is not referenced by any bot or package.</p>
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/administration/orphans")
@Tag(name = "11. Orphan Admin", description = "Detect and clean up orphaned resources")
public interface IRestOrphanAdmin {

    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Scan for orphaned resources",
            description = "Scans all stores (packages, behavior sets, HTTP calls, output sets, langchains, "
                    + "property setters, dictionaries) and returns resources not referenced by any bot or package. "
                    + "Use includeDeleted=true to also include soft-deleted resources in the report.")
    @APIResponse(responseCode = "200", description = "Orphan report with list of unreferenced resources.")
    OrphanReport scanOrphans(
            @Parameter(description = "Include soft-deleted resources in the scan. Default: false.")
            @QueryParam("includeDeleted") @DefaultValue("false") Boolean includeDeleted);

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Purge orphaned resources",
            description = "Scans all stores for orphans and permanently deletes them. "
                    + "Returns a report of what was deleted. This operation is irreversible.")
    @APIResponse(responseCode = "200", description = "Purge report with count and list of deleted resources.")
    OrphanReport purgeOrphans(
            @Parameter(description = "Include soft-deleted resources in the purge. Default: true.")
            @QueryParam("includeDeleted") @DefaultValue("true") Boolean includeDeleted);
}
