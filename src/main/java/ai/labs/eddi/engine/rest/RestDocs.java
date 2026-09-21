/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.rest;

import ai.labs.eddi.engine.api.IRestDocs;
import ai.labs.eddi.engine.docs.DocsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * REST implementation of {@link IRestDocs}. Thin adapter over
 * {@link DocsService}, which owns the filesystem access and the path-traversal
 * guard.
 */
@ApplicationScoped
public class RestDocs implements IRestDocs {

    private final DocsService docsService;

    @Inject
    public RestDocs(DocsService docsService) {
        this.docsService = docsService;
    }

    @Override
    public List<String> listDocs() {
        return docsService.listDocs();
    }

    @Override
    public Response readDoc(String name) {
        String content = docsService.readDoc(name);
        if (content == null) {
            // A rejected name and an absent page are both 404 here, deliberately: the
            // caller can do nothing different about either, and distinguishing them
            // would echo an attacker-supplied traversal string back in the response.
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(content, MediaType.TEXT_PLAIN_TYPE).build();
    }
}
