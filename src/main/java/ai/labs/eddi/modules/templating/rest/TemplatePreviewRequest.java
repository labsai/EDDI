/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.rest;

/**
 * Request body for the template preview endpoint.
 *
 * @param template
 *            the Qute template string to resolve
 * @param conversationId
 *            optional conversation ID — if provided, the template is resolved
 *            against that conversation's real memory data; otherwise, built-in
 *            sample data is used
 */
public record TemplatePreviewRequest(String template, String conversationId) {
}
