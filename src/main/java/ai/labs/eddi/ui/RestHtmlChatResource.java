/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestHtmlChatResource implements IRestHtmlChatResource {

    @Override
    public Response viewDefault() {
        return viewHtml("/");
    }

    @Override
    public Response viewHtml(String path) {
        // No leading slash, and a null check — matching RestWelcomeResource and
        // RestWorkforceResource. getResourceAsStream() delegates to the thread context
        // classloader, and only Quarkus' own loader strips a leading '/'; on a plain
        // JDK loader "/META-INF/..." resolves to null, and the unchecked null then
        // answered 200 with an empty body instead of surfacing the error.
        var stream = getResourceAsStream("META-INF/resources/chat.html");
        if (stream == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        return Response.ok(stream).build();
    }
}
