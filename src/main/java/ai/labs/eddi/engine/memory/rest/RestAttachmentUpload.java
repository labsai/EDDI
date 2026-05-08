/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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

    @Inject
    public RestAttachmentUpload(IAttachmentStore attachmentStore) {
        this.attachmentStore = attachmentStore;
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
    public Response uploadAttachment(
                                     @Parameter(description = "Conversation to attach the file to.")
                                     @PathParam("conversationId") String conversationId,
                                     @RestForm("file") FileUpload file,
                                     @Parameter(description = "Optional tenant identifier for multi-tenant isolation.")
                                     @QueryParam("tenantId") String tenantId) {

        if (file == null || file.fileName() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No file provided"))
                    .build();
        }

        String fileName = file.fileName();
        String mimeType = file.contentType() != null ? file.contentType() : "application/octet-stream";

        try {
            byte[] bytes = Files.readAllBytes(file.uploadedFile());

            Attachment attachment = attachmentStore.store(
                    bytes, mimeType, fileName, conversationId, tenantId);

            LOGGER.infof("Attachment uploaded: %s (%s, %d bytes) → %s",
                    fileName, attachment.mimeType(), attachment.sizeBytes(),
                    attachment.storageRef());

            return Response.status(Response.Status.CREATED)
                    .entity(Map.of(
                            "storageRef", attachment.storageRef(),
                            "fileName", attachment.filename() != null ? attachment.filename() : "",
                            "mimeType", attachment.mimeType(),
                            "sizeBytes", attachment.sizeBytes(),
                            "conversationId", attachment.conversationId()))
                    .build();

        } catch (IAttachmentStore.AttachmentStoreException e) {
            LOGGER.warnf("Attachment upload rejected: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", e.getMessage(),
                            "code", "ATTACHMENT_REJECTED"))
                    .build();
        } catch (IOException e) {
            LOGGER.errorf(e, "Failed to read uploaded file: %s", fileName);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                            "error", "Failed to process upload",
                            "code", "ATTACHMENT_UPLOAD_FAILED"))
                    .build();
        }
    }

    @GET
    @Path("/{conversationId}/attachments")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
               operationId = "listAttachments",
               summary = "List attachments for a conversation",
               description = "Returns metadata for all attachments stored against this conversation.")
    @APIResponse(responseCode = "200", description = "List of attachment metadata.")
    public Response listAttachments(
                                    @Parameter(description = "Conversation ID to list attachments for.")
                                    @PathParam("conversationId") String conversationId) {
        List<Attachment> attachments = attachmentStore.listByConversation(conversationId);
        return Response.ok(attachments).build();
    }

    @DELETE
    @Path("/{conversationId}/attachments")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
               operationId = "deleteAttachments",
               summary = "Delete all attachments for a conversation",
               description = "Removes all stored attachments for a conversation (GDPR erasure).")
    @APIResponse(responseCode = "200", description = "Attachments deleted.")
    public Response deleteAttachments(
                                      @Parameter(description = "Conversation ID to delete attachments for.")
                                      @PathParam("conversationId") String conversationId) {
        long deleted = attachmentStore.deleteByConversation(conversationId);
        return Response.ok(Map.of(
                "conversationId", conversationId,
                "deletedCount", deleted)).build();
    }
}
