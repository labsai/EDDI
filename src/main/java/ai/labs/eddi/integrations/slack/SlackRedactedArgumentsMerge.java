/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Turns the arguments an approver edited in Slack — which only ever showed the
 * REDACTED arguments — into the amended raw arguments, server-side.
 * <ul>
 * <li>A leaf the approver left as shown keeps its RAW value (a redacted secret
 * survives without ever reaching Slack).</li>
 * <li>A leaf that was redacted cannot be changed from Slack: changing it is an
 * error ("edit in the Manager").</li>
 * <li>A new or changed value carrying the redaction marker is an error — the
 * marker would otherwise end up as the literal argument.</li>
 * </ul>
 *
 * @since 6.7.0
 */
final class SlackRedactedArgumentsMerge {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SlackRedactedArgumentsMerge() {
    }

    /** A refused merge; the message names the JSON path, never a value. */
    static final class MergeException extends Exception {
        MergeException(String message) {
            super(message);
        }
    }

    /**
     * @param raw
     *            the call's raw arguments (JSON object)
     * @param redacted
     *            the redacted arguments shown in Slack
     * @param submitted
     *            what the approver submitted
     * @return the amended raw arguments (JSON)
     */
    static String merge(String raw, String redacted, String submitted) throws MergeException {
        JsonNode rawNode;
        JsonNode redactedNode;
        JsonNode submittedNode;
        try {
            rawNode = JSON.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            redactedNode = JSON.readTree(redacted == null || redacted.isBlank() ? "{}" : redacted);
        } catch (Exception e) {
            throw new MergeException("these arguments cannot be edited in Slack — decide in the Manager");
        }
        try {
            submittedNode = JSON.readTree(submitted);
        } catch (Exception e) {
            throw new MergeException("not valid JSON");
        }
        if (submittedNode == null || !submittedNode.isObject()) {
            throw new MergeException("the arguments must be a JSON object");
        }
        JsonNode merged = mergeNode("$", rawNode, redactedNode, submittedNode);
        try {
            return JSON.writeValueAsString(merged);
        } catch (Exception e) {
            throw new MergeException("the arguments cannot be serialized");
        }
    }

    private static JsonNode mergeNode(String path, JsonNode raw, JsonNode redacted, JsonNode submitted) throws MergeException {
        if (submitted.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = submitted.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode rawChild = raw != null && raw.isObject() ? raw.get(field.getKey()) : null;
                JsonNode redactedChild = redacted != null && redacted.isObject() ? redacted.get(field.getKey()) : null;
                out.set(field.getKey(), mergeNode(path + "." + field.getKey(), rawChild, redactedChild, field.getValue()));
            }
            return out;
        }
        if (submitted.isArray()) {
            // Items are matched to the raw ones by index, so adding or removing one in an
            // array that holds redacted values could hand a different secret to the call.
            if (redacted != null && redacted.isArray() && raw != null && raw.isArray() && !redacted.equals(raw)
                    && submitted.size() != raw.size()) {
                throw new MergeException(path + " holds redacted values, so items cannot be added or removed in Slack — edit it in the Manager");
            }
            ArrayNode out = JSON.createArrayNode();
            for (int i = 0; i < submitted.size(); i++) {
                JsonNode rawChild = raw != null && raw.isArray() ? raw.get(i) : null;
                JsonNode redactedChild = redacted != null && redacted.isArray() ? redacted.get(i) : null;
                // An item that holds a redacted value is paired with its raw twin by index
                // alone, so it may only be left exactly as shown: changing a visible field
                // (a name, an id) of it would send the original item's secret along with
                // the changed identity.
                if (redactedChild != null && rawChild != null && !redactedChild.equals(rawChild) && !submitted.get(i).equals(redactedChild)) {
                    throw new MergeException(
                            path + "[" + i + "] holds a redacted secret, so the item cannot be changed in Slack — edit it in the Manager");
                }
                out.add(mergeNode(path + "[" + i + "]", rawChild, redactedChild, submitted.get(i)));
            }
            return out;
        }
        boolean wasRedacted = redacted != null && raw != null && !redacted.equals(raw);
        if (redacted != null && submitted.equals(redacted)) {
            // Left as shown: the raw value, which Slack never saw.
            return raw != null ? raw : submitted;
        }
        if (wasRedacted) {
            throw new MergeException(path + " holds a redacted secret and cannot be edited in Slack — edit it in the Manager");
        }
        if (submitted.isTextual() && submitted.asText().contains(SecretRedactionFilter.REDACTED)) {
            throw new MergeException(path + " contains the redaction marker " + SecretRedactionFilter.REDACTED + " — type the real value");
        }
        return submitted;
    }
}
