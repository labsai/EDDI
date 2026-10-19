/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * {@code json-object} — output only. Flags an answer that is not a single JSON
 * object (a surrounding Markdown code fence is tolerated). It checks the shape
 * "is a JSON object" and nothing else — for a schema, use the task's
 * {@code responseSchema} with {@code responseValidation.onSchemaMismatch}.
 * <p>
 * Usually paired with {@code reprompt}: the model is told to answer with a
 * single valid JSON object.
 */
@ApplicationScoped
public class JsonObjectGuardrail implements ContentGuardrail {

    public static final String TYPE = "json-object";
    public static final String REPROMPT_HINT = "Your previous reply was not a single valid JSON object. "
            + "Reply with only one JSON object: no text before or after it, no Markdown code fence.";

    /**
     * Trailing text after the object ("{...} Hope this helps!") is not a single
     * object.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<GuardrailPhase> phases() {
        return Set.of(GuardrailPhase.OUTPUT);
    }

    @Override
    public GuardrailVerdict evaluate(String text, GuardrailContext context) {
        String candidate = stripFence(text.strip());
        if (!candidate.startsWith("{")) {
            return GuardrailVerdict.violation("not_a_json_object", REPROMPT_HINT);
        }
        try {
            JsonNode node = MAPPER.readTree(candidate);
            if (node != null && node.isObject()) {
                return GuardrailVerdict.allow();
            }
        } catch (JsonProcessingException e) {
            // falls through: invalid JSON
        }
        return GuardrailVerdict.violation("not_a_json_object", REPROMPT_HINT);
    }

    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int closing = text.lastIndexOf("```");
        if (firstNewline < 0 || closing <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, closing).strip();
    }
}
