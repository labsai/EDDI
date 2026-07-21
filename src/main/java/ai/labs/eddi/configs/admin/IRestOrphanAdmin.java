/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.admin;

import ai.labs.eddi.configs.admin.model.OrphanReport;
import jakarta.annotation.security.RolesAllowed;
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
 * <p>
 * An orphan is a resource (package, behavior set, HTTP calls config, etc.) that
 * exists in the database but is not referenced by any Agent or package.
 * </p>
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/administration/orphans")
@Tag(name = "Operations / Orphans", description = "Detect and clean up orphaned resources")
@RolesAllowed("eddi-admin")
public interface IRestOrphanAdmin {

    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Scan for orphaned resources", description = "Scans all stores "
            + "(workflows, behavior sets, HTTP calls, output sets, LLMs, "
            + "property setters, dictionaries, parsers) and returns resources not referenced by any Agent or workflow. "
            + "includeDeleted=true additionally includes soft-deleted resources; false (the default) reports live resources only.")
    @APIResponse(responseCode = "200", description = "Orphan report with list of unreferenced resources.")
    // @formatter:off
    OrphanReport scanOrphans(
            @Parameter(description = "Also include soft-deleted resources. Default: false (live resources only).")
            @QueryParam("includeDeleted") @DefaultValue("false") Boolean includeDeleted);
    // @formatter:on

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Purge orphaned resources", description = "Scans all stores for orphans and permanently deletes them. "
            + "Returns a report of what was deleted. This operation is irreversible. "
            + "Pass the same includeDeleted value used for the scan so the purge acts on the set that was reviewed. "
            + "Refuses with 409 when the reference scan is incomplete, since a partial reference set would "
            + "misclassify live resources as orphans.")
    @APIResponse(responseCode = "200", description = "Purge report with count and list of deleted resources.")
    @APIResponse(responseCode = "409", description = "The reference scan was incomplete; nothing was deleted.")
    // @formatter:off
    OrphanReport purgeOrphans(
            @Parameter(description = "Also purge soft-deleted resources. Default: false (live resources only).")
            @QueryParam("includeDeleted") @DefaultValue("false") Boolean includeDeleted);
    // @formatter:on
}
