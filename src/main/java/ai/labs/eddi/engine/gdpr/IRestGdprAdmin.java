package ai.labs.eddi.engine.gdpr;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST endpoints for GDPR compliance operations.
 * <p>
 * Provides cascading user data deletion (Art. 17 — Right to Erasure) and full
 * user data export (Art. 15/20 — Right of Access / Data Portability).
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/admin/gdpr")
@Tag(name = "GDPR / Privacy")
@Produces(MediaType.APPLICATION_JSON)
public interface IRestGdprAdmin {

    @DELETE
    @Path("/{userId}")
    @Operation(
               operationId = "deleteUserDataGdpr",
               summary = "Cascade-delete all user data (GDPR Art. 17)",
               description = "Deletes user memories and conversations. "
                       + "Pseudonymizes audit ledger and database log entries. "
                       + "Returns a summary of affected records.")
    GdprDeletionResult deleteUserData(
                                      @PathParam("userId")
                                      @Parameter(description = "User ID to erase", required = true) String userId);

    @GET
    @Path("/{userId}/export")
    @Operation(
               operationId = "exportUserDataGdpr",
               summary = "Export all user data (GDPR Art. 15/20)",
               description = "Returns all data associated with a user, including "
                       + "memories, conversations, and managed conversation mappings.")
    UserDataExport exportUserData(
                                  @PathParam("userId")
                                  @Parameter(description = "User ID to export", required = true) String userId);
}
