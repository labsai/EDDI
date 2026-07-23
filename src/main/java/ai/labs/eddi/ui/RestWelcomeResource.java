/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * Serves welcome.html for all /welcome/** paths. React Router handles
 * client-side routing from there.
 */
@ApplicationScoped
public class RestWelcomeResource implements IRestWelcomeResource {

    @Override
    public Response viewDefault() {
        return viewHtml("/");
    }

    @Override
    public Response viewHtml(String path) {
        return Response.ok(getResourceAsStream("/META-INF/resources/welcome.html")).build();
    }
}
