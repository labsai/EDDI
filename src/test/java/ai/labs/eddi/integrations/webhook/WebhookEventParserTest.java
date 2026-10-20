/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.webhook;

import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.integrations.webhook.WebhookEventParser.InboundEvent;
import ai.labs.eddi.integrations.webhook.WebhookEventParser.WebhookRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebhookEventParserTest {

    private static final String ID = "0123456789abcdef01234567";
    private final WebhookEventParser parser = new WebhookEventParser(new ObjectMapper());

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private InboundEvent parse(Map<String, String> pc, String contentType, Map<String, String> headers, String body) {
        return parser.parse(WebhookChannelSettings.from(pc), ID, contentType, headers, bytes(body));
    }

    @Test
    void structuredMode() {
        String body = "{\"specversion\":\"1.0\",\"id\":\"evt-1\",\"type\":\"com.example.order.created\","
                + "\"source\":\"/shop\",\"data\":{\"orderId\":42}}";
        InboundEvent event = parse(Map.of(), "application/cloudevents+json; charset=utf-8", Map.of(), body);
        assertEquals("evt-1", event.id());
        assertEquals("com.example.order.created", event.type());
        assertEquals("structured", event.mode());
        assertEquals("/shop", event.event().get("source"));
        assertEquals(42, event.eventNode().at("/data/orderId").asInt());
    }

    @Test
    void structuredModeRequiresIdTypeSourceAndSpecVersion() {
        var e = assertThrows(WebhookRequestException.class, () -> parse(Map.of(), "application/cloudevents+json", Map.of(),
                "{\"specversion\":\"1.0\",\"type\":\"t\",\"source\":\"s\"}"));
        assertEquals(400, e.status());
        e = assertThrows(WebhookRequestException.class, () -> parse(Map.of(), "application/cloudevents+json", Map.of(),
                "{\"specversion\":\"0.3\",\"id\":\"1\",\"type\":\"t\",\"source\":\"s\"}"));
        assertEquals(400, e.status());
    }

    @Test
    void binaryMode() {
        Map<String, String> headers = new HashMap<>();
        headers.put("ce-specversion", "1.0");
        headers.put("ce-id", "evt-2");
        headers.put("ce-type", "com.example.alert");
        headers.put("ce-source", "/monitor");
        headers.put("ce-severity", "high");
        InboundEvent event = parse(Map.of(), "application/json", headers, "{\"host\":\"db1\"}");
        assertEquals("binary", event.mode());
        assertEquals("evt-2", event.id());
        assertEquals("com.example.alert", event.type());
        assertEquals("high", event.event().get("severity"));
        assertEquals("db1", event.eventNode().at("/data/host").asText());
        assertEquals("application/json", event.event().get("datacontenttype"));
    }

    @Test
    void binaryModeRequiresCoreHeaders() {
        var e = assertThrows(WebhookRequestException.class,
                () -> parse(Map.of(), "application/json", Map.of("ce-specversion", "1.0", "ce-id", "1"), "{}"));
        assertEquals(400, e.status());
    }

    @Test
    void plainJsonTypeFromHeaderAndIdFromDeliveryHeader() {
        InboundEvent event = parse(Map.of("signatureScheme", "github"), "application/json",
                Map.of("x-github-event", "push", "x-github-delivery", "d-123"), "{\"ref\":\"refs/heads/main\"}");
        assertEquals("plain", event.mode());
        assertEquals("push", event.type());
        assertEquals("d-123", event.id());
        assertEquals("webhook:" + ID, event.event().get("source"));
        assertEquals("refs/heads/main", event.eventNode().at("/data/ref").asText());
    }

    @Test
    void plainJsonTypeFromPointerElseDefault() {
        InboundEvent typed = parse(Map.of("eventTypePath", "/kind"), "application/json", Map.of(), "{\"kind\":\"alert.fired\"}");
        assertEquals("alert.fired", typed.type());
        assertNull(typed.id());
        InboundEvent untyped = parse(Map.of(), "application/json", Map.of(), "[1,2,3]");
        assertEquals(WebhookEventParser.DEFAULT_EVENT_TYPE, untyped.type());
        assertFalse(untyped.event().containsKey("id"));
    }

    @Test
    void rejectsNonJsonBatchAndMalformed() {
        assertEquals(415, assertThrows(WebhookRequestException.class,
                () -> parse(Map.of(), "application/x-www-form-urlencoded", Map.of(), "a=b")).status());
        assertEquals(415, assertThrows(WebhookRequestException.class,
                () -> parse(Map.of(), "application/cloudevents-batch+json", Map.of(), "[]")).status());
        assertEquals(400, assertThrows(WebhookRequestException.class,
                () -> parse(Map.of(), "application/json", Map.of(), "{not json")).status());
    }

    @Test
    void overlongAttributeRejected() {
        String longType = "t".repeat(WebhookEventParser.MAX_ATTRIBUTE_LENGTH + 1);
        assertEquals(400, assertThrows(WebhookRequestException.class,
                () -> parse(Map.of(), "application/cloudevents+json", Map.of(),
                        "{\"specversion\":\"1.0\",\"id\":\"1\",\"type\":\"" + longType + "\",\"source\":\"s\"}"))
                .status());
    }
}
