package ai.labs.eddi.engine.gdpr;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST endpoints for GDPR compliance operations.
 * <p>
 * Provides cascading user data deletion (Art. 17 — Right to Erasure), full
 * user data export (Art. 15/20 — Right of Access / Data Portability), and
 * processing restriction (Art. 18 — Right to Restriction).
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
                       + "memories, conversations, managed conversation mappings, "
                       + "and audit processing records.")
    UserDataExport exportUserData(
                                  @PathParam("userId")
                                  @Parameter(description = "User ID to export", required = true) String userId);

    @POST
    @Path("/{userId}/restrict")
    @Operation(
               operationId = "restrictProcessingGdpr",
               summary = "Restrict processing for a user (GDPR Art. 18)",
               description = "Stops new conversation processing while preserving "
                       + "all user data. Use when a user disputes data accuracy "
                       + "or objects to processing.")
    void restrictProcessing(
                            @PathParam("userId")
                            @Parameter(description = "User ID to restrict", required = true) String userId);

    @DELETE
    @Path("/{userId}/restrict")
    @Operation(
               operationId = "unrestrictProcessingGdpr",
               summary = "Remove processing restriction (GDPR Art. 18)",
               description = "Restores normal conversation processing for a user.")
    void unrestrictProcessing(
                              @PathParam("userId")
                              @Parameter(description = "User ID to unrestrict", required = true) String userId);

    @GET
    @Path("/{userId}/restrict")
    @Operation(
               operationId = "checkProcessingRestrictionGdpr",
               summary = "Check processing restriction status",
               description = "Returns true if processing is currently restricted for this user.")
    boolean isProcessingRestricted(
                                   @PathParam("userId")
                                   @Parameter(description = "User ID to check", required = true) String userId);
}
