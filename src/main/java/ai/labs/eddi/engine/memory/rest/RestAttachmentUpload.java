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

    private final IAttachmentStore attachmentStore;
    private final long maxUploadBytes;

    @Inject
    public RestAttachmentUpload(IAttachmentStore attachmentStore,
            @ConfigProperty(name = "eddi.attachments.max-size-bytes",
                            defaultValue = "20971520") long maxUploadBytes) {
        this.attachmentStore = attachmentStore;
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

                String fileName = file.fileName();
                String mimeType = file.contentType() != null ? file.contentType() : "application/octet-stream";

                // Early size guard — reject before reading into memory
                long fileSize = Files.size(file.uploadedFile());
                if (fileSize > maxUploadBytes) {
                    LOGGER.warnf("Attachment rejected: %s (%d bytes exceeds %d byte limit)",
                            sanitize(fileName), fileSize, maxUploadBytes);
                    asyncResponse.resume(Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of(
                                    "error", String.format("File too large: %d bytes (max %d)", fileSize, maxUploadBytes),
                                    "code", "ATTACHMENT_TOO_LARGE"))
                            .build());
                    return;
                }

                byte[] bytes = Files.readAllBytes(file.uploadedFile());

                Attachment attachment = attachmentStore.store(
                        bytes, mimeType, fileName, conversationId, tenantId);

                LOGGER.infof("Attachment uploaded: %s (%s, %d bytes) → %s",
                        sanitize(fileName), attachment.mimeType(), attachment.sizeBytes(),
                        attachment.storageRef());

                asyncResponse.resume(Response.status(Response.Status.CREATED)
                        .entity(Map.of(
                                "storageRef", attachment.storageRef(),
                                "fileName", attachment.filename() != null ? attachment.filename() : "",
                                "mimeType", attachment.mimeType(),
                                "sizeBytes", attachment.sizeBytes(),
                                "conversationId", attachment.conversationId()))
                        .build());

            } catch (IAttachmentStore.AttachmentStoreException e) {
                LOGGER.warnf("Attachment upload rejected: %s", e.getMessage());
                asyncResponse.resume(Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of(
                                "error", e.getMessage(),
                                "code", "ATTACHMENT_REJECTED"))
                        .build());
            } catch (IOException e) {
                LOGGER.errorf(e, "Failed to read uploaded file");
                asyncResponse.resume(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of(
                                "error", "Failed to process upload",
                                "code", "ATTACHMENT_UPLOAD_FAILED"))
                        .build());
            } catch (Exception e) {
                LOGGER.errorf(e, "Unexpected error during attachment upload");
                asyncResponse.resume(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of(
                                "error", "Internal error",
                                "code", "ATTACHMENT_UPLOAD_FAILED"))
                        .build());
            }
        });
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
                LOGGER.errorf(e, "Failed to list attachments for conversation '%s'", conversationId);
                asyncResponse.resume(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of("error", "Failed to list attachments")).build());
            }
        });
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
                LOGGER.errorf(e, "Failed to delete attachments for conversation '%s'", conversationId);
                asyncResponse.resume(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of("error", "Failed to delete attachments")).build());
            }
        });
    }
}
