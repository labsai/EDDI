/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestHtmlChatResource implements IRestHtmlChatResource {

    /**
     * Whether the widget may take its bearer token from {@code ?token=}. A crafted
     * link then signs the widget in as whoever minted the token (login CSRF), and
     * the first request logs the token before the widget strips it from the address
     * bar — so it is an operator opt-in, off by default.
     */
    @ConfigProperty(name = "eddi.chat.url-token.enabled", defaultValue = "false")
    boolean urlTokenEnabled;

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

    @Override
    public Response fetchChatConfig() {
        // Only booleans are spliced in, so there is nothing to escape; add escaping
        // with the first string value (see RestManagerResource.escapeJs).
        return Response.ok("window.__EDDI_CHAT__={urlTokenEnabled:" + urlTokenEnabled + "};")
                .type("application/javascript")
                .header("Cache-Control", "no-cache, must-revalidate")
                .build();
    }
}
