/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.rest;

import java.util.List;
import java.util.Map;

/**
 * Response body for the template preview endpoint.
 *
 * @param resolved
 *            the fully resolved template text, or null if an error occurred
 * @param availableVariables
 *            flattened dot-path list of all variables available in the template
 *            data (e.g., "properties.userName", "memory.current.input")
 * @param variableValues
 *            map of variable dot-paths to their actual values (for display in
 *            the variable reference panel)
 * @param error
 *            error message if template resolution failed, null otherwise
 */
public record TemplatePreviewResponse(
        String resolved,
        List<String> availableVariables,
        Map<String, Object> variableValues,
        String error) {
}
