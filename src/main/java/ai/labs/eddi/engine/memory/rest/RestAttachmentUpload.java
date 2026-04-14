package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.engine.memory.IAttachmentStorage;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;

/**
 * REST endpoint for uploading binary attachments to a conversation.
 * <p>
 * Accepts multipart file uploads and stores them via the configured
 * {@link IAttachmentStorage} (GridFS or PostgreSQL). Returns a storage
 * reference that can be used in subsequent conversation turns.
 *
 * @since 6.0.0
 */
@Path("/conversations")
@Tag(name = "Attachments")
public class RestAttachmentUpload {

    private static final Logger LOGGER = Logger.getLogger(RestAttachmentUpload.class);

    private final Instance<IAttachmentStorage> attachmentStorageInstance;

    @Inject
    public RestAttachmentUpload(Instance<IAttachmentStorage> attachmentStorageInstance) {
        this.attachmentStorageInstance = attachmentStorageInstance;
    }

    @POST
    @Path("/{conversationId}/attachments")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
               operationId = "uploadAttachment",
               summary = "Upload a file attachment",
               description = "Upload a binary file to attach to a conversation. " +
                       "Returns a storage reference that can be used in the 'attachment_url' context field.")
    @APIResponse(responseCode = "201", description = "Attachment stored. Response contains storageRef.")
    @APIResponse(responseCode = "400", description = "No file provided or invalid request.")
    @APIResponse(responseCode = "503", description = "No attachment storage configured.")
    public Response uploadAttachment(
                                     @Parameter(description = "Conversation to attach the file to.")
                                     @PathParam("conversationId") String conversationId,
                                     @RestForm("file") FileUpload file) {

        if (attachmentStorageInstance.isUnsatisfied()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "No attachment storage configured"))
                    .build();
        }
        if (attachmentStorageInstance.isAmbiguous()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                            "error", "Multiple attachment storage implementations configured",
                            "code", "ATTACHMENT_STORAGE_AMBIGUOUS"))
                    .build();
        }

        if (file == null || file.fileName() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No file provided"))
                    .build();
        }

        var storage = attachmentStorageInstance.get();
        String fileName = file.fileName();
        String mimeType = file.contentType() != null ? file.contentType() : "application/octet-stream";
        long sizeBytes = file.size();

        try (InputStream is = Files.newInputStream(file.uploadedFile())) {
            String storageRef = storage.store(conversationId, fileName, mimeType, is, sizeBytes);

            LOGGER.infof("Attachment uploaded: %s (%s, %d bytes) → %s",
                    fileName, mimeType, sizeBytes, storageRef);

            return Response.status(Response.Status.CREATED)
                    .entity(Map.of(
                            "storageRef", storageRef,
                            "fileName", fileName,
                            "mimeType", mimeType,
                            "sizeBytes", sizeBytes))
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
}
