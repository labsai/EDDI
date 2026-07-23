/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * Serves workforce.html for all /workforce/** paths. React Router handles
 * client-side routing from there.
 */
@ApplicationScoped
public class RestWorkforceResource implements IRestWorkforceResource {

    @Override
    public Response viewDefault() {
        return viewHtml("/");
    }

    @Override
    public Response viewHtml(String path) {
        return Response.ok(getResourceAsStream("/META-INF/resources/workforce.html")).build();
    }
}
