/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * Serves workforce.html for all /workforce/** paths.
 * React Router handles client-side routing from there.
 */
@ApplicationScoped
public class RestWorkforceResource implements IRestWorkforceResource {

    @Override
    public Response viewDefault() {
        return viewHtml();
    }

    @Override
    public Response viewHtml() {
        var stream = getResourceAsStream("/META-INF/resources/workforce.html");
        if (stream == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        return Response.ok(stream).build();
    }
}
