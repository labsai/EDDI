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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binary-mode data handling, event-type resolution and refusals of
 * {@link WebhookEventParser} that {@link WebhookEventParserTest} leaves out.
 */
class WebhookEventParserBranchTest {

    private final WebhookEventParser parser = new WebhookEventParser(new ObjectMapper());

    private InboundEvent parse(Map<String, String> config, String contentType, Map<String, String> headers, String body) {
        return parser.parse(WebhookChannelSettings.from(config), "hook-1", contentType, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private int refusal(Map<String, String> config, String contentType, Map<String, String> headers, String body) {
        return assertThrows(WebhookRequestException.class, () -> parse(config, contentType, headers, body)).status();
    }

    private static Map<String, String> binaryHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("ce-specversion", " 1.0 ");
        headers.put("ce-id", "evt-1");
        headers.put("ce-type", "com.example.ping");
        headers.put("ce-source", "/monitor");
        headers.put("ce-", "nameless");
        return headers;
    }

    @Test
    void binaryModeWithoutContentTypeReadsJsonOrFallsBackToText() {
        var json = parse(Map.of(), null, binaryHeaders(), "{\"a\":1}");
        assertEquals(Map.of("a", 1), json.event().get("data"));
        assertFalse(json.event().containsKey("datacontenttype"), "no content type is recorded when none was sent");
        assertFalse(json.event().containsKey(""), "a bare 'ce-' header names no attribute");

        var text = parse(Map.of(), null, binaryHeaders(), "plain words");
        assertEquals("plain words", text.event().get("data"));
    }

    @Test
    void binaryModeReadsTextDataAndRefusesOtherTypes() {
        assertEquals("line one", parse(Map.of(), "text/plain; charset=utf-8", binaryHeaders(), "line one").event().get("data"));
        assertEquals(415, refusal(Map.of(), "application/xml", binaryHeaders(), "<a/>"));
        assertEquals(400, refusal(Map.of(), "application/json", binaryHeaders(), "{broken"));
    }

    @Test
    void binaryModeWithoutABodyHasNoData() {
        var event = parse(Map.of(), "application/json", binaryHeaders(), "");
        assertFalse(event.event().containsKey("data"));
        assertEquals("application/json", event.event().get("datacontenttype"));
    }

    @Test
    void binaryModeRefusesABlankRequiredHeaderAndAnOverlongAttribute() {
        var blank = binaryHeaders();
        blank.put("ce-source", " ");
        assertEquals(400, refusal(Map.of(), "application/json", blank, "{}"));

        var overlong = binaryHeaders();
        overlong.put("ce-type", "t".repeat(WebhookEventParser.MAX_ATTRIBUTE_LENGTH + 1));
        assertEquals(400, refusal(Map.of(), "application/json", overlong, "{}"));
    }

    @Test
    void batchModeIsRefused() {
        assertEquals(415, refusal(Map.of(), "application/cloudevents-batch+json", Map.of(), "[]"));
    }

    @Test
    void structuredModeRefusesNonObjectsAndBlankAttributes() {
        assertEquals(400, refusal(Map.of(), "application/cloudevents+json", Map.of(), "null"));
        assertEquals(400, refusal(Map.of(), "application/cloudevents+json", Map.of(), "[1]"));
        assertEquals(400, refusal(Map.of(), "application/cloudevents+json", Map.of(),
                "{\"specversion\":\"1.0\",\"id\":\" \",\"type\":\"t\",\"source\":\"s\"}"));
        assertEquals(400, refusal(Map.of(), "application/cloudevents+json", Map.of(),
                "{\"specversion\":1.0,\"id\":\"1\",\"type\":\"t\",\"source\":\"s\"}"));
    }

    @Test
    void plainModeTakesTheTypeFromTheHeaderThenThePathThenTheDefault() {
        Map<String, String> config = Map.of(WebhookChannelSettings.KEY_EVENT_TYPE_HEADER, "X-Kind", WebhookChannelSettings.KEY_EVENT_TYPE_PATH,
                "/event/name");
        assertEquals("from-header", parse(config, "application/json", Map.of("x-kind", "from-header"), "{\"event\":{\"name\":\"p\"}}").type());
        assertEquals("from-path", parse(config, "application/json", Map.of("x-kind", " "), "{\"event\":{\"name\":\"from-path\"}}").type());
        assertEquals("7", parse(config, "application/json", Map.of(), "{\"event\":{\"name\":7}}").type(), "a scalar is read as text");
        assertEquals(WebhookEventParser.DEFAULT_EVENT_TYPE, parse(config, "application/json", Map.of(), "{\"event\":{\"name\":null}}").type());
        assertEquals(WebhookEventParser.DEFAULT_EVENT_TYPE, parse(config, "application/json", Map.of(), "{\"event\":{\"name\":{}}}").type(),
                "an object is not a type");
        assertEquals(WebhookEventParser.DEFAULT_EVENT_TYPE, parse(Map.of(), "application/vnd.api+json", Map.of(), "{}").type());
    }

    @Test
    void plainModeKeepsTheSendersIdButNotABlankOne() {
        var withId = parse(Map.of(), "application/json", Map.of("x-event-id", "d-1"), "{}");
        assertEquals("d-1", withId.id());
        assertEquals("d-1", withId.event().get("id"));
        assertEquals("webhook:hook-1", withId.event().get("source"));

        var blankId = parse(Map.of(), "application/json", Map.of("x-event-id", " "), "{}");
        assertNull(blankId.id());

        var noId = parse(Map.of(), "application/json", Map.of(), "{}");
        assertNull(noId.id());
        assertFalse(noId.event().containsKey("id"));

        assertEquals(400, refusal(Map.of(), "application/json", Map.of("x-event-id", "i".repeat(300)), "{}"));
    }

    @Test
    void plainModeRefusesAnEmptyOrInvalidBodyAndNonJson() {
        assertEquals(400, refusal(Map.of(), "application/json", Map.of(), ""));
        assertEquals(400, refusal(Map.of(), "application/json", Map.of(), "{nope"));
        assertEquals(415, refusal(Map.of(), "text/plain", Map.of(), "hello"));
        assertEquals(415, refusal(Map.of(), null, Map.of(), "{}"));
    }

    @Test
    void theMediaTypeIsNormalised() {
        assertEquals("", WebhookEventParser.mediaTypeOf(null));
        assertEquals("application/json", WebhookEventParser.mediaTypeOf(" Application/JSON ; charset=UTF-8"));
        assertTrue(WebhookEventParser.mediaTypeOf("text/plain").startsWith("text/"));
    }
}
