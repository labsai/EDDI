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

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestHtmlChatResource implements IRestHtmlChatResource {

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
    public Response fetchChatConfig() {
        // The shared builder escapes any string value added later (JsGlobalScript).
        return Response.ok(JsGlobalScript.assign("__EDDI_CHAT__").put("urlTokenEnabled", urlTokenEnabled).toJs())
                .type("application/javascript")
                .header("Cache-Control", "no-cache, must-revalidate")
                .build();
    }
}
