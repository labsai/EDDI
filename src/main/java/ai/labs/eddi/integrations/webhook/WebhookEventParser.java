/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.webhook;

import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a verified webhook request into an {@link InboundEvent}.
 * <p>
 * Three shapes are accepted, and all three come out as the same map, so an
 * author's template and correlation pointer do not depend on how the sender
 * chose to deliver:
 * <ul>
 * <li><b>CloudEvents structured mode</b> — {@code Content-Type:
 * application/cloudevents+json}; the body is the event envelope.</li>
 * <li><b>CloudEvents binary mode</b> — a {@code ce-specversion} header; the
 * attributes are {@code ce-*} headers and the body is {@code data}.</li>
 * <li><b>Plain JSON</b> — anything else with a JSON body; the type comes from
 * the configured header or JSON pointer (else {@code "webhook"}), the id from
 * the configured delivery-id header, and the body becomes {@code data}.</li>
 * </ul>
 * The resulting map has the CloudEvents attribute names ({@code id},
 * {@code type}, {@code source}, {@code subject}, {@code time},
 * {@code datacontenttype}, {@code data}, extensions). It is DATA: it reaches
 * the conversation as the {@code event} context value and is never compiled as
 * a template.
 *
 * @since 6.6.0
 */
public final class WebhookEventParser {

    /** Event type used when a plain-JSON request names none. */
    public static final String DEFAULT_EVENT_TYPE = "webhook";

    static final int MAX_ATTRIBUTE_LENGTH = 256;
    private static final String CE_PREFIX = "ce-";
    private static final Set<String> REQUIRED_CE_ATTRIBUTES = Set.of("id", "type", "source");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public WebhookEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * A parsed event.
     *
     * @param id
     *            sender's event / delivery id, or null when it sent none
     * @param type
     *            the event type (never null)
     * @param mode
     *            {@code structured}, {@code binary} or {@code plain}
     * @param event
     *            the CloudEvents-shaped map exposed as {@code {context.event}}
     * @param eventNode
     *            the same map as a tree, for JSON-pointer lookups
     */
    public record InboundEvent(String id, String type, String mode, Map<String, Object> event, JsonNode eventNode) {
    }

    /** A request the endpoint must refuse, with the status to refuse it with. */
    public static class WebhookRequestException extends RuntimeException {
        private final int status;

        public WebhookRequestException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /**
     * @param headers
     *            request headers keyed by LOWER-CASE name, first value each
     */
    public InboundEvent parse(WebhookChannelSettings settings, String integrationId, String contentType,
                              Map<String, String> headers, byte[] body) {
        String mediaType = mediaTypeOf(contentType);
        if (mediaType.startsWith("application/cloudevents-batch")) {
            throw new WebhookRequestException(415, "CloudEvents batch mode is not supported — send one event per request.");
        }
        if (mediaType.equals("application/cloudevents+json")) {
            return parseStructured(body);
        }
        if (headers.containsKey(CE_PREFIX + "specversion")) {
            return parseBinary(headers, mediaType, body);
        }
        return parsePlain(settings, integrationId, mediaType, headers, body);
    }

    private InboundEvent parseStructured(byte[] body) {
        Map<String, Object> envelope = readJsonObject(body);
        requireSpecVersion(envelope.get("specversion"));
        for (String attribute : REQUIRED_CE_ATTRIBUTES) {
            Object value = envelope.get(attribute);
            if (!(value instanceof String text) || text.isBlank()) {
                throw new WebhookRequestException(400, "CloudEvent is missing the required attribute '" + attribute + "'.");
            }
        }
        String id = checkedAttribute("id", (String) envelope.get("id"));
        String type = checkedAttribute("type", (String) envelope.get("type"));
        return build(id, type, "structured", envelope);
    }

    private InboundEvent parseBinary(Map<String, String> headers, String mediaType, byte[] body) {
        requireSpecVersion(headers.get(CE_PREFIX + "specversion"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("specversion", "1.0");
        for (var header : headers.entrySet()) {
            String name = header.getKey();
            if (name.startsWith(CE_PREFIX) && name.length() > CE_PREFIX.length() && !name.equals(CE_PREFIX + "specversion")) {
                event.put(name.substring(CE_PREFIX.length()), header.getValue());
            }
        }
        for (String attribute : REQUIRED_CE_ATTRIBUTES) {
            Object value = event.get(attribute);
            if (!(value instanceof String text) || text.isBlank()) {
                throw new WebhookRequestException(400, "Binary-mode CloudEvent is missing the header 'ce-" + attribute + "'.");
            }
        }
        if (!mediaType.isEmpty()) {
            event.put("datacontenttype", mediaType);
        }
        if (body.length > 0) {
            event.put("data", readData(mediaType, body));
        }
        return build(checkedAttribute("id", (String) event.get("id")), checkedAttribute("type", (String) event.get("type")), "binary", event);
    }

    private InboundEvent parsePlain(WebhookChannelSettings settings, String integrationId, String mediaType,
                                    Map<String, String> headers, byte[] body) {
        if (!isJson(mediaType)) {
            throw new WebhookRequestException(415,
                    "Unsupported content type — send JSON (application/json) or a CloudEvent (application/cloudevents+json).");
        }
        JsonNode data = readJsonTree(body);
        String type = null;
        if (settings.eventTypeHeader() != null) {
            type = headers.get(settings.eventTypeHeader().toLowerCase(Locale.ROOT));
        }
        if ((type == null || type.isBlank()) && settings.eventTypePath() != null) {
            JsonNode node = data.at(settings.eventTypePath());
            if (node.isValueNode() && !node.isNull()) {
                type = node.asText();
            }
        }
        if (type == null || type.isBlank()) {
            type = DEFAULT_EVENT_TYPE;
        }
        String id = headers.get(settings.eventIdHeader().toLowerCase(Locale.ROOT));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("type", checkedAttribute("type", type));
        event.put("source", "webhook:" + integrationId);
        event.put("datacontenttype", mediaType);
        event.put("data", objectMapper.convertValue(data, Object.class));
        if (id != null) {
            checkedAttribute("id", id);
        } else {
            event.remove("id");
        }
        return build(id != null && !id.isBlank() ? id : null, type, "plain", event);
    }

    private InboundEvent build(String id, String type, String mode, Map<String, Object> event) {
        return new InboundEvent(id, type, mode, event, objectMapper.valueToTree(event));
    }

    private Object readData(String mediaType, byte[] body) {
        if (isJson(mediaType) || mediaType.isEmpty()) {
            try {
                return objectMapper.readValue(body, Object.class);
            } catch (IOException e) {
                if (isJson(mediaType)) {
                    throw new WebhookRequestException(400, "The request body is not valid JSON.");
                }
                // No content type and not JSON: fall through to text.
            }
        }
        if (mediaType.isEmpty() || mediaType.startsWith("text/")) {
            return new String(body, StandardCharsets.UTF_8);
        }
        throw new WebhookRequestException(415, "Unsupported CloudEvent data content type '" + mediaType + "' — use JSON or text.");
    }

    private Map<String, Object> readJsonObject(byte[] body) {
        try {
            Map<String, Object> map = objectMapper.readValue(body, MAP_TYPE);
            if (map == null) {
                throw new WebhookRequestException(400, "The request body must be a JSON object.");
            }
            return new LinkedHashMap<>(map);
        } catch (JsonProcessingException e) {
            throw new WebhookRequestException(400, "The request body is not a valid JSON object.");
        } catch (IOException e) {
            throw new WebhookRequestException(400, "The request body could not be read.");
        }
    }

    private JsonNode readJsonTree(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw new WebhookRequestException(400, "The request body is empty.");
            }
            return node;
        } catch (IOException e) {
            throw new WebhookRequestException(400, "The request body is not valid JSON.");
        }
    }

    private static void requireSpecVersion(Object value) {
        if (!"1.0".equals(value instanceof String s ? s.trim() : null)) {
            throw new WebhookRequestException(400, "Unsupported CloudEvents specversion — only 1.0 is accepted.");
        }
    }

    private static String checkedAttribute(String name, String value) {
        if (value != null && value.length() > MAX_ATTRIBUTE_LENGTH) {
            throw new WebhookRequestException(400, "Event attribute '" + name + "' is longer than " + MAX_ATTRIBUTE_LENGTH + " characters.");
        }
        return value;
    }

    private static boolean isJson(String mediaType) {
        return mediaType.equals("application/json") || mediaType.endsWith("+json");
    }

    static String mediaTypeOf(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String type = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
