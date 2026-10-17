/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.engine.exception.ErrorResponses;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Refuses to store a configuration that carries {@link ConfigSecretRedactor}'s
 * marker.
 * <p>
 * A non-administrator reads LLM, MCP-calls, channel, RAG and httpcalls
 * documents with their plaintext credentials replaced by the marker. An editor
 * who opens such a document in the Manager and saves it unchanged would
 * otherwise replace the administrator's key with the marker — silently, until
 * the next deploy fails at the provider. A loud 400 at save time is the right
 * outcome: the credential has to be re-entered, or replaced by a
 * {@code ${vault:…}} reference.
 * <p>
 * Applied to administrators too. An administrator reads the document as stored,
 * so the only way one can send the marker is by copying an editor's view — and
 * storing it would be the same corruption.
 * <p>
 * Throws rather than returning a response, like
 * {@code CallerTokenAuthorPolicy}: the import path treats a non-200 update as
 * "create it directly instead", and the direct create runs this check too.
 */
public final class RedactionMarkerGuard {

    private RedactionMarkerGuard() {
    }

    /**
     * @throws BadRequestException
     *             with a JSON body naming every field that carries the marker
     */
    public static void requireNone(Object document) {
        List<String> fields = ConfigSecretRedactor.markerPaths(document);
        if (fields.isEmpty()) {
            return;
        }
        String message = "The document contains the redaction marker ('" + ConfigSecretRedactor.MARKER_PREFIX + "…') at "
                + String.join(", ", fields) + ". It was read through a redacted view, which never shows a plaintext credential; "
                + "saving it would replace the stored credential with the marker. Re-enter the credential, or replace it with a "
                + "${vault:…} reference, before saving.";
        Map<String, Object> body = new LinkedHashMap<>(ErrorResponses.body(ErrorResponses.BAD_REQUEST, message));
        body.put("fields", fields);
        throw new BadRequestException(message,
                Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build());
    }
}
