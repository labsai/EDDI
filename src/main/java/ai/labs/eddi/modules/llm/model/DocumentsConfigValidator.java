/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import ai.labs.eddi.engine.documents.DocumentBounds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Save-time validation of an LLM task's {@link DocumentsConfig}. Three rules,
 * applied to every key the designer set (an absent key is never an error):
 * <ol>
 * <li>a key that is not in {@link #LIVE_KEYS} is refused — "not available in
 * this version" — so that nothing is stored inert to wake up later;</li>
 * <li>an enum-like value that is not a known name is refused;</li>
 * <li>a number above its operator bound ({@link DocumentBounds}) is refused,
 * naming the field, the value and the bound; a negative number is refused.</li>
 * </ol>
 * Called from {@code LlmStore}, so every write path (create, update, JSON
 * patch, import, setup, upgrade) is covered. At deploy time the same values are
 * never refused: {@code EffectiveDocumentsConfig.resolve} ignores a key that is
 * not available and clamps a value above its bound, with a warning, so a bound
 * tightened later never un-deploys an agent.
 */
public final class DocumentsConfigValidator {

    /**
     * The designer keys whose behaviour has shipped, as dotted paths below
     * {@code documents} (e.g. {@code sharing.delegation}). An entry
     * {@code path=VALUE} makes only that value of an enum-like key available (e.g.
     * {@code structure=ON_DEMAND}). <b>Append-only</b>: each package that makes a
     * key live adds it here, in a delimited block, in the same PR as the behaviour.
     */
    public static final Set<String> LIVE_KEYS = Set.of(
    // --- WP-0.1: the block exists; no key is available yet ---
    );

    /**
     * Keys that are objects in the JSON but have no fields of their own yet: the
     * object itself is the key.
     */
    static final Set<String> OBJECT_KEYS = Set.of("bridge");

    /** Known names of every enum-like key; for a list key, of each element. */
    static final Map<String, Set<String>> ENUM_VALUES = Map.ofEntries(
            Map.entry("structure", Set.of("OFF", "ON_DEMAND", "AUTO_EXPAND")),
            Map.entry("split.strategy", Set.of("AUTO", "OUTLINE", "HEADINGS", "PAGES")),
            Map.entry("unitForm", Set.of("AUTO", "BLOCKS")),
            Map.entry("images.mode", Set.of("EMBEDDED")),
            Map.entry("images.pageRender", Set.of("NEVER", "SCANNED", "VECTOR", "ALWAYS")),
            Map.entry("tables.image", Set.of("NEVER", "LOW_CONFIDENCE", "ALWAYS")),
            Map.entry("tables.format", Set.of("MARKDOWN", "CSV", "JSON")),
            Map.entry("embeddedFiles.extract", Set.of("NONE")),
            Map.entry("library.promote", Set.of("TOOL")),
            Map.entry("library.scopes", Set.of("self", "global", "team")),
            Map.entry("large.onReady", Set.of("NONE", "NOTE")),
            Map.entry("large.summary.images", Set.of("CAPTIONS", "KEY", "ALL")));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The known names of an enum-like key (each element, for a list); empty if the
     * key is not enum-like.
     */
    public static Set<String> knownValues(String path) {
        return ENUM_VALUES.getOrDefault(path, Set.of());
    }

    private DocumentsConfigValidator() {
    }

    /**
     * @throws IllegalArgumentException
     *             naming {@code fieldPath} and the offending key
     */
    public static void validate(DocumentsConfig config, String fieldPath) {
        if (config == null) {
            return; // no block: the operator bounds are not even read
        }
        validate(config, fieldPath, LIVE_KEYS, DocumentBounds.current());
    }

    /** The whole contract, with the live set and the bounds supplied. */
    static void validate(DocumentsConfig config, String fieldPath, Set<String> liveKeys, DocumentBounds bounds) {
        if (config == null) {
            return;
        }
        for (Map.Entry<String, JsonNode> leaf : setKeys(config).entrySet()) {
            String path = leaf.getKey();
            JsonNode value = leaf.getValue();
            String field = fieldPath + "." + path;
            if (!isLive(path, value, liveKeys)) {
                throw new IllegalArgumentException(field + " is not available in this version");
            }
            checkEnum(path, value, field);
            checkNumber(path, value, field, bounds);
        }
    }

    /**
     * Whether {@code path} with {@code value} is available: the key itself is live,
     * or — for a text or list of texts — every value is live as {@code path=VALUE}.
     */
    public static boolean isLive(String path, JsonNode value, Set<String> liveKeys) {
        if (liveKeys.contains(path)) {
            return true;
        }
        if (value != null && value.isTextual()) {
            return liveKeys.contains(path + "=" + value.asText());
        }
        if (value != null && value.isArray() && !value.isEmpty()) {
            for (JsonNode element : value) {
                if (!element.isTextual() || !liveKeys.contains(path + "=" + element.asText())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Every key the designer set, as dotted path → JSON value. Objects are walked
     * (an empty object sets nothing), except {@link #OBJECT_KEYS}; lists and
     * scalars are leaves.
     */
    public static Map<String, JsonNode> setKeys(DocumentsConfig config) {
        Map<String, JsonNode> leaves = new LinkedHashMap<>();
        if (config != null) {
            collect("", MAPPER.valueToTree(config), leaves);
        }
        return leaves;
    }

    private static void collect(String prefix, JsonNode node, Map<String, JsonNode> leaves) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isObject() && !OBJECT_KEYS.contains(path)) {
                collect(path, value, leaves);
            } else {
                leaves.put(path, value);
            }
        }
    }

    private static void checkEnum(String path, JsonNode value, String field) {
        Set<String> known = ENUM_VALUES.get(path);
        if (known == null) {
            return;
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                if (!element.isTextual() || !known.contains(element.asText())) {
                    throw new IllegalArgumentException(field + " contains unknown value '" + element.asText() + "' (known: "
                            + String.join(", ", known.stream().sorted().toList()) + ")");
                }
            }
        } else if (!value.isTextual() || !known.contains(value.asText())) {
            throw new IllegalArgumentException(field + " has unknown value '" + value.asText() + "' (known: "
                    + String.join(", ", known.stream().sorted().toList()) + ")");
        }
    }

    private static void checkNumber(String path, JsonNode value, String field, DocumentBounds bounds) {
        if (!value.isNumber()) {
            return;
        }
        // Exact decimal comparison: a byte count above 2^53 must not round past its
        // bound.
        if ((value.isFloatingPointNumber() && !Double.isFinite(value.asDouble())) || value.decimalValue().signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative (was " + value.asText() + ")");
        }
        BigDecimal number = value.decimalValue();
        bounds.boundFor(path).ifPresent(bound -> {
            if (number.compareTo(BigDecimal.valueOf(bound)) > 0) {
                throw new IllegalArgumentException(field + " is " + value.asText() + ", above the operator bound of "
                        + formatBound(bound) + " (" + DocumentBounds.propertyFor(path) + ")");
            }
        });
    }

    static String formatBound(double bound) {
        return bound == Math.rint(bound) && Math.abs(bound) < 1e15 ? Long.toString((long) bound) : Double.toString(bound);
    }
}
