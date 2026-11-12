/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.BrandRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestHtmlChatResource implements IRestHtmlChatResource {

    private static final Logger LOGGER = Logger.getLogger(RestHtmlChatResource.class);

    /**
     * Where the Chat UI build puts the launcher ({@code vite.embed.config.ts}); the
     * Maven copy of {@code scripts/js/chat-ui*.js} brings it into the jar.
     */
    static final String EMBED_SCRIPT_RESOURCE = "META-INF/resources/scripts/js/chat-ui-embed.js";
    static final String EMBED_SCRIPT_TYPE = "application/javascript; charset=UTF-8";
    /**
     * An hour: a fixed URL in other sites' pages, so not immutable, but not
     * refetched on every view.
     */
    static final String EMBED_SCRIPT_CACHE = "public, max-age=3600";

    private final BrandedShellRenderer shellRenderer;

    /**
     * Whether the widget may take its bearer token from {@code ?token=}. A crafted
     * link then signs the widget in as whoever minted the token (login CSRF), and
     * the first request logs the token before the widget strips it from the address
     * bar — so it is an operator opt-in, off by default.
     */
    @ConfigProperty(name = "eddi.chat.url-token.enabled", defaultValue = "false")
    boolean urlTokenEnabled;

    @Inject
    public RestHtmlChatResource(BrandedShellRenderer shellRenderer) {
        this.shellRenderer = shellRenderer;
    }

    @Override
    public Response viewDefault() {
        return viewHtml("/");
    }

    @Override
    public Response viewHtml(String path) {
        // A null check rather than an unchecked stream: a missing shell answered 200
        // with an empty body once (the leading-slash lookup), instead of surfacing
        // the error.
        return shellRenderer.render(BrandedShell.CHAT, BrandRequest.NONE)
                .map(page -> Response.ok(page, MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build())
                .orElseGet(() -> Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    @Override
    public Response fetchEmbedScript() {
        try (InputStream in = getResourceAsStream(EMBED_SCRIPT_RESOURCE)) {
            if (in == null) {
                // A jar built with -DskipUi=true carries no UI, the launcher included.
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(in.readAllBytes(), EMBED_SCRIPT_TYPE).header("Cache-Control", EMBED_SCRIPT_CACHE).build();
        } catch (IOException e) {
            LOGGER.warn("Could not read the chat embed launcher", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Override
    public Response fetchChatConfig() {
        // The shared builder escapes any string value added later (JsGlobalScript).
        return Response.ok(JsGlobalScript.assign("__EDDI_CHAT__").put("urlTokenEnabled", urlTokenEnabled).toJs())
                .type("application/javascript")
                .header("Cache-Control", "no-cache, must-revalidate")
                .build();
    }
}
