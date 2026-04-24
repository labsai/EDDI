/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.rest;

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
 *
 * @since 6.0.0
 */
@Path("/administration/preview")
@Tag(name = "Template Preview")
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
