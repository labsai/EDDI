/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * REST endpoint for uploading and listing binary attachments on a conversation.
 * <p>
 * Accepts multipart file uploads and stores them via the configured
 * {@link IAttachmentStore} (GridFS or PostgreSQL BYTEA). Returns a storage
 * reference that can be used in subsequent conversation turns to forward
 * attachments to multimodal LLM models.
 *
 * @since 6.0.0
 */
@Path("/conversations")
@Tag(name = "Attachments")
public class RestAttachmentUpload {

    private static final Logger LOGGER = Logger.getLogger(RestAttachmentUpload.class);

    /**
     * Allowed pattern for the optional tenantId query parameter. Alphanumeric plus
     * dash/underscore, max 64 characters.
     */
    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final IAttachmentStore attachmentStore;
    private final ManagedExecutor managedExecutor;
    private final long maxUploadBytes;

    @Inject
    public RestAttachmentUpload(IAttachmentStore attachmentStore,
            ManagedExecutor managedExecutor,
            @ConfigProperty(name = "eddi.attachments.max-size-bytes",
                            defaultValue = "20971520") long maxUploadBytes) {
        this.attachmentStore = attachmentStore;
        this.managedExecutor = managedExecutor;
        this.maxUploadBytes = maxUploadBytes;
    }

    @POST
    @Path("/{conversationId}/attachments")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
               operationId = "uploadAttachment",
               summary = "Upload a file attachment",
               description = "Upload a binary file to attach to a conversation. "
                       + "Returns a storage reference usable in the "
                       + "'attachment_url' context field or via STORED content source.")
    @APIResponse(responseCode = "201", description = "Attachment stored. Response contains storageRef.")
    @APIResponse(responseCode = "400", description = "No file provided, file too large, or MIME mismatch.")
    @APIResponse(responseCode = "500", description = "Storage error.")
    public void uploadAttachment(
                                 @Parameter(description = "Conversation to attach the file to.")
                                 @PathParam("conversationId") String conversationId,
                                 @RestForm("file") FileUpload file,
                                 // Security note: tenantId is client-supplied and NOT
                                 // authenticated. It is sanitized (alphanumeric + dash/underscore,
                                 // max 64 chars) to prevent injection, but must not be used for
                                 // security-critical decisions (quota enforcement, data segregation)
                                 // until multi-tenancy auth wiring derives it from the principal.
                                 @Parameter(description = "Optional tenant identifier for multi-tenant isolation.")
                                 @QueryParam("tenantId") String tenantId,
                                 @Suspended AsyncResponse asyncResponse) {

        CompletableFuture.runAsync(() -> {
            try {
                if (file == null || file.fileName() == null) {
                    asyncResponse.resume(Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "No file provided"))
                            .build());
                    return;
                }

                // Sanitize optional tenantId
                String safeTenantId = sanitizeTenantId(tenantId);

                String fileName = file.fileName();
                String mimeType = file.contentType() != null ? file.contentType() : "application/octet-stream";

                // Early size guard — reject before reading into memory
                long fileSize = Files.size(file.uploadedFile());
                if (fileSize > maxUploadBytes) {
                    LOGGER.warnf("Attachment rejected for conversation '%s': %s (%d bytes exceeds %d byte limit)",
                            sanitize(conversationId), sanitize(fileName), fileSize, maxUploadBytes);
                    asyncResponse.resume(Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of(
                                    "error", String.format("File too large: %d bytes (max %d)", fileSize, maxUploadBytes),
                                    "code", "ATTACHMENT_TOO_LARGE"))
                            .build());
                    return;
                }

                byte[] bytes = Files.readAllBytes(file.uploadedFile());

                Attachment attachment = attachmentStore.store(
                        bytes, mimeType, fileName, conversationId, safeTenantId);

                LOGGER.infof("Attachment uploaded for conversation '%s': %s (%s, %d bytes) → %s",
                        sanitize(conversationId), sanitize(fileName), attachment.mimeType(),
                        attachment.sizeBytes(), attachment.storageRef());

                asyncResponse.resume(Response.status(Response.Status.CREATED)
                        .entity(Map.of(
                                "storageRef", attachment.storageRef(),
                                "fileName", attachment.filename() != null ? attachment.filename() : "",
                                "mimeType", attachment.mimeType(),
                                "sizeBytes", attachment.sizeBytes(),
                                "conversationId", attachment.conversationId()))
                        .build());

            } catch (IAttachmentStore.AttachmentStoreException e) {
                LOGGER.warnf("Attachment upload rejected for conversation '%s': %s",
                        sanitize(conversationId), e.getMessage());
                asyncResponse.resume(Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of(
                                "error", e.getMessage(),
                                "code", "ATTACHMENT_REJECTED"))
                        .build());
            } catch (IOException e) {
                LOGGER.errorf(e, "Failed to read uploaded file for conversation '%s'",
                        sanitize(conversationId));
                asyncResponse.resume(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of(
                                "error", "Failed to process upload",
                                "code", "ATTACHMENT_UPLOAD_FAILED"))
                        .build());
            } catch (Exception e) {
                LOGGER.errorf(e, "Unexpected error during attachment upload for conversation '%s'",
                        sanitize(conversationId));
                asyncResponse.resume(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of(
                                "error", "Internal error",
                                "code", "ATTACHMENT_UPLOAD_FAILED"))
                        .build());
            }
        }, managedExecutor);
    }

    @GET
    @Path("/{conversationId}/attachments")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
               operationId = "listAttachments",
               summary = "List attachments for a conversation",
               description = "Returns metadata for all attachments stored against this conversation.")
    @APIResponse(responseCode = "200", description = "List of attachment metadata.")
    public void listAttachments(
                                @Parameter(description = "Conversation ID to list attachments for.")
                                @PathParam("conversationId") String conversationId,
                                @Suspended AsyncResponse asyncResponse) {
        CompletableFuture.runAsync(() -> {
            try {
                List<Attachment> attachments = attachmentStore.listByConversation(conversationId);
                asyncResponse.resume(Response.ok(attachments).build());
            } catch (Exception e) {
                LOGGER.errorf(e, "Failed to list attachments for conversation '%s'", sanitize(conversationId));
                asyncResponse.resume(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of("error", "Failed to list attachments")).build());
            }
        }, managedExecutor);
    }

    @DELETE
    @Path("/{conversationId}/attachments")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
               operationId = "deleteAttachments",
               summary = "Delete all attachments for a conversation",
               description = "Removes all stored attachments for a conversation (GDPR erasure).")
    @APIResponse(responseCode = "200", description = "Attachments deleted.")
    public void deleteAttachments(
                                  @Parameter(description = "Conversation ID to delete attachments for.")
                                  @PathParam("conversationId") String conversationId,
                                  @Suspended AsyncResponse asyncResponse) {
        CompletableFuture.runAsync(() -> {
            try {
                long deleted = attachmentStore.deleteByConversation(conversationId);
                asyncResponse.resume(Response.ok(Map.of(
                        "conversationId", conversationId,
                        "deletedCount", deleted)).build());
            } catch (Exception e) {
                LOGGER.errorf(e, "Failed to delete attachments for conversation '%s'", sanitize(conversationId));
                asyncResponse.resume(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of("error", "Failed to delete attachments")).build());
            }
        }, managedExecutor);
    }

    /**
     * Sanitize the optional tenant ID parameter: if it doesn't match the allowed
     * pattern, treat it as absent (null).
     */
    private static String sanitizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        if (!TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            LOGGER.warnf("Rejected invalid tenantId: '%s'", sanitize(tenantId));
            return null;
        }
        return tenantId;
    }
}
