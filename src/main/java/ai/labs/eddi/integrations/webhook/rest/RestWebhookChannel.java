/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.webhook.rest;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.webhook.WebhookEventHandler;
import ai.labs.eddi.integrations.webhook.WebhookEventHandler.Dispatch;
import ai.labs.eddi.integrations.webhook.WebhookEventParser;
import ai.labs.eddi.integrations.webhook.WebhookEventParser.InboundEvent;
import ai.labs.eddi.integrations.webhook.WebhookEventParser.WebhookRequestException;
import ai.labs.eddi.integrations.webhook.WebhookSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Signed inbound webhook channel: {@code POST /integrations/webhook/{id}},
 * where {@code id} is the resource id of a {@code channelType: "webhook"}
 * channel integration.
 * <p>
 * Like the Slack endpoint it is reachable without an EDDI credential — the
 * request's HMAC signature, checked against that ONE integration's secret, is
 * its authentication, and the check fails closed. Order of operations:
 * <ol>
 * <li>Look up the integration (404 if none).</li>
 * <li>Read at most {@code eddi.webhook.max-body-bytes} raw bytes (413 beyond).
 * The body is taken as a stream of bytes, not a {@code String}: the MAC covers
 * the bytes the sender signed, and a charset decode/re-encode round trip could
 * change them (an invalid UTF-8 sequence would be replaced).</li>
 * <li>Verify the signature over those bytes (401). Nothing is parsed before
 * this — an unsigned body never reaches a JSON parser.</li>
 * <li>Parse CloudEvents structured / binary or plain JSON (400 / 415).</li>
 * <li>Deduplicate, route, start the turn (202 / 200 duplicate / 429 /
 * 503).</li>
 * </ol>
 * The body and signature are never logged above DEBUG — and not at DEBUG
 * either.
 *
 * @since 6.6.0
 */
@ApplicationScoped
@Path("/integrations/webhook")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Integrations / Webhook", description = "Signed inbound webhook (CloudEvents over HTTP) receiver")
// Explicitly public: senders call this with no EDDI credential, and every
// request is authenticated by its HMAC signature here (the HTTP policy permits
// the path). quarkus.security.jaxrs.deny-unannotated-endpoints denies anything
// unannotated.
@PermitAll
public class RestWebhookChannel {

    private static final Logger LOGGER = Logger.getLogger(RestWebhookChannel.class);
    private static final Pattern INTEGRATION_ID = Pattern.compile("^[0-9a-fA-F-]{18,64}$");

    private final ChannelTargetRouter router;
    private final WebhookSignatureVerifier verifier;
    private final WebhookEventHandler handler;
    private final WebhookEventParser parser;
    private final ObjectMapper objectMapper;
    private final int maxBodyBytes;

    @Inject
    public RestWebhookChannel(ChannelTargetRouter router,
            WebhookSignatureVerifier verifier,
            WebhookEventHandler handler,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "eddi.webhook.max-body-bytes", defaultValue = "262144") int maxBodyBytes) {
        this.router = router;
        this.verifier = verifier;
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.parser = new WebhookEventParser(objectMapper);
        this.maxBodyBytes = maxBodyBytes;
    }

    @POST
    @Path("/{integrationId}")
    @Consumes(MediaType.WILDCARD)
    public Response receive(@PathParam("integrationId") String integrationId,
                            @Context HttpHeaders httpHeaders,
                            InputStream body) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (httpHeaders != null) {
            for (var entry : httpHeaders.getRequestHeaders().entrySet()) {
                List<String> values = entry.getValue();
                if (entry.getKey() != null && values != null && !values.isEmpty()) {
                    headers.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), values.get(0));
                }
            }
        }
        long declaredLength = httpHeaders != null ? httpHeaders.getLength() : -1;
        if (declaredLength > maxBodyBytes) {
            return error(413, "The request body exceeds " + maxBodyBytes + " bytes.");
        }
        byte[] raw;
        try {
            raw = body == null ? new byte[0] : body.readNBytes(maxBodyBytes + 1);
        } catch (IOException e) {
            return error(400, "The request body could not be read.");
        }
        return receive(integrationId, headers, raw);
    }

    /**
     * Everything after the body has been read, with headers keyed by lower-case
     * name. Package-private so the full path can be tested without an HTTP stack.
     */
    Response receive(String integrationId, Map<String, String> headers, byte[] raw) {
        if (raw.length > maxBodyBytes) {
            return error(413, "The request body exceeds " + maxBodyBytes + " bytes.");
        }
        Optional<ChannelIntegrationConfiguration> found = integrationId != null && INTEGRATION_ID.matcher(integrationId).matches()
                ? router.getWebhookIntegration(integrationId)
                : Optional.empty();
        if (found.isEmpty()) {
            return error(404, "No webhook integration with this id.");
        }
        ChannelIntegrationConfiguration integration = found.get();
        WebhookChannelSettings settings = WebhookChannelSettings.from(integration.getPlatformConfig());

        WebhookSignatureVerifier.Result verification = verifier.verify(settings, raw, name -> headers.get(name.toLowerCase(Locale.ROOT)));
        if (verification != WebhookSignatureVerifier.Result.VALID) {
            LOGGER.warnf("Webhook %s: request refused (%s)", sanitize(integrationId), verification);
            return error(401, "Invalid or missing signature.");
        }

        InboundEvent event;
        try {
            event = parser.parse(settings, integrationId, headers.get("content-type"), headers, raw);
        } catch (WebhookRequestException e) {
            return error(e.status(), e.getMessage());
        }

        // Used when the event carries no id. The signature value is unique to the
        // signed bytes (and, for eddi-v1, the timestamp), so a captured request
        // replayed verbatim is recognised. For github and generic-hmac — which sign
        // no timestamp — this dedup window is the only replay protection there is.
        String fallbackDedupKey = headers.get(signatureHeaderName(settings).toLowerCase(Locale.ROOT));
        Dispatch dispatch = handler.dispatch(integrationId, integration, settings, event, fallbackDedupKey);
        return toResponse(dispatch);
    }

    private static String signatureHeaderName(WebhookChannelSettings settings) {
        return switch (settings.scheme() == null ? WebhookChannelSettings.Scheme.EDDI_V1 : settings.scheme()) {
            case EDDI_V1 -> WebhookSignatureVerifier.EDDI_SIGNATURE_HEADER;
            case GITHUB -> WebhookSignatureVerifier.GITHUB_SIGNATURE_HEADER;
            case GENERIC_HMAC -> settings.signatureHeader();
        };
    }

    private Response toResponse(Dispatch dispatch) {
        Map<String, Object> entity = new LinkedHashMap<>();
        if (dispatch.conversationId() != null) {
            entity.put("conversationId", dispatch.conversationId());
        }
        if (dispatch.groupConversationId() != null) {
            entity.put("groupConversationId", dispatch.groupConversationId());
        }
        if (dispatch.duplicate()) {
            entity.put("duplicate", true);
        }
        if (dispatch.error() != null) {
            entity.put("error", dispatch.error());
        }
        var builder = Response.status(dispatch.status()).type(MediaType.APPLICATION_JSON).entity(json(entity));
        if (dispatch.status() == 409 && dispatch.duplicate()) {
            // A repeat of a delivery still being processed: retry soon, and it is
            // answered 200 (accepted) or processed afresh (first one refused).
            builder.header("Retry-After", "5");
        } else if (dispatch.status() == 429) {
            builder.header("Retry-After", "60");
        } else if (dispatch.status() == 503) {
            builder.header("Retry-After", "30");
        }
        return builder.build();
    }

    private Response error(int status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(json(Map.of("error", message))).build();
    }

    private String json(Map<String, Object> entity) {
        try {
            return objectMapper.writeValueAsString(entity);
        } catch (IOException e) {
            return "{}";
        }
    }
}
