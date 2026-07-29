/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST endpoint for previewing Qute template resolution.
 * <p>
 * Used by the Manager LLM editor to show a live preview of system prompts with
 * real conversation data or sample defaults.
 * <p>
 * Authoring-only. When a {@code conversationId} is supplied the caller chooses
 * the template <em>and</em> receives the flattened variable values, which makes
 * this a query language over that conversation's memory — so the role gate is
 * paired with a per-conversation ownership check in the implementation.
 *
 * @since 6.0.0
 */
@Path("/administration/preview")
@Tag(name = "Tools / Template Preview", description = "Preview resolved system prompts with sample data")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestTemplatePreview {

    @POST
    @Path("/template")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Preview a Qute template with conversation data",
               description = "Resolves a Qute template string against real conversation memory " +
                       "(if conversationId is provided) or built-in sample data. Returns the " +
                       "resolved text and the list of available template variables.")
    TemplatePreviewResponse previewTemplate(TemplatePreviewRequest request);
}
