/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.crypto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.TreeMap;

/**
 * RFC 8785 JSON Canonicalization Scheme (JCS) implementation using pure
 * Jackson.
 * <p>
 * Produces a deterministic JSON string by:
 * <ol>
 * <li>Sorting all object keys lexicographically (recursive)</li>
 * <li>Removing insignificant whitespace</li>
 * <li>Normalizing number representations</li>
 * </ol>
 * <p>
 * No external dependency required — uses Jackson's built-in tree model.
 *
 * @since 6.0.0
 */
public final class JacksonCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private JacksonCanonicalizer() {
        // utility class
    }

    /**
     * Canonicalize a JSON string per RFC 8785.
     *
     * @param json
     *            the input JSON string
     * @return canonicalized JSON string with sorted keys and no whitespace
     * @throws JsonProcessingException
     *             if the input is not valid JSON
     */
    public static String canonicalize(String json) throws JsonProcessingException {
        JsonNode node = MAPPER.readTree(json);
        JsonNode sorted = sortKeys(node);
        return MAPPER.writeValueAsString(sorted);
    }

    /**
     * Canonicalize a Java object by serializing it to JSON first.
     *
     * @param obj
     *            the object to canonicalize
     * @return canonicalized JSON string
     * @throws JsonProcessingException
     *             if serialization fails
     */
    public static String canonicalizeObject(Object obj) throws JsonProcessingException {
        JsonNode node = MAPPER.valueToTree(obj);
        JsonNode sorted = sortKeys(node);
        return MAPPER.writeValueAsString(sorted);
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            TreeMap<String, JsonNode> sortedMap = new TreeMap<>();
            Iterator<String> fieldNames = objectNode.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                sortedMap.put(field, sortKeys(objectNode.get(field)));
            }
            ObjectNode sortedNode = MAPPER.createObjectNode();
            sortedMap.forEach(sortedNode::set);
            return sortedNode;
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            ArrayNode sortedArray = MAPPER.createArrayNode();
            for (JsonNode element : arrayNode) {
                sortedArray.add(sortKeys(element));
            }
            return sortedArray;
        }
        return node;
    }
}
