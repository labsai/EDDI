/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounds the work an artifact JSON schema can make the validator do, before the
 * library sees it.
 * <p>
 * The library evaluates every {@code $ref} separately, so references multiply:
 * twenty {@code $defs} that each {@code allOf} the previous one twice expand to
 * a million subschemas (two seconds to save, four per artifact write, doubling
 * with each further definition), and nothing in the evaluation reads a clock.
 * Two shapes are refused:
 * <ul>
 * <li>a schema whose subschemas, with every reference expanded once, number
 * more than {@link #MAX_EXPANDED_SUBSCHEMAS};</li>
 * <li>a recursive reference that can apply twice to the same part of the
 * content: back at the same instance location (endless recursion — a
 * {@code StackOverflowError}), or through two routes that do not start by
 * descending into different named properties, which doubles the work at every
 * level of nesting. A tree ({@code children.items -> #}) or a binary tree
 * ({@code left -> #}, {@code right -> #}) passes.</li>
 * </ul>
 * References into the document are followed — {@code "#"}, a JSON pointer
 * ({@code "#/$defs/x"}), an anchor, or the root {@code $id} with one of those;
 * one naming another document is left to the schema loader, which loads only
 * the bundled meta-schemas ({@link ArtifactValidators}). A nested {@code $id},
 * which would re-base the references below it, is refused rather than guessed
 * at.
 */
final class ArtifactSchemaComplexity {

    /** Subschemas one evaluation of the schema may expand to. */
    static final int MAX_EXPANDED_SUBSCHEMAS = 2000;

    /** Steps the recursion analysis may take in total. */
    private static final int MAX_ANALYSIS_STEPS = 100_000;

    /**
     * Keywords whose value is one subschema applied at the same instance location.
     */
    private static final Set<String> SAME_LOCATION_SCHEMA = Set.of("not", "if", "then", "else");
    /**
     * Keywords whose value is a list of subschemas applied at the same location.
     */
    private static final Set<String> SAME_LOCATION_SCHEMA_LIST = Set.of("allOf", "anyOf", "oneOf");
    /**
     * Keywords whose value maps names to subschemas applied at the same location.
     */
    private static final Set<String> SAME_LOCATION_SCHEMA_MAP = Set.of("dependentSchemas", "dependencies");
    /**
     * Keywords whose value is one subschema applied to some child of the instance.
     */
    private static final Set<String> DESCENDING_SCHEMA = Set.of("items", "additionalItems", "additionalProperties", "unevaluatedItems",
            "unevaluatedProperties", "contains", "propertyNames");
    private static final Set<String> REFERENCES = Set.of("$ref", "$dynamicRef", "$recursiveRef");

    /** The token for a step into an unnamed child (an item, any property). */
    private static final String ANY_CHILD = "*";

    private final JsonNode root;
    private final Map<String, List<JsonNode>> anchors = new HashMap<>();
    private final Map<JsonNode, Long> weights = new IdentityHashMap<>();
    private final Set<JsonNode> onPath = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<JsonNode> recursionTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private int analysisSteps;

    private ArtifactSchemaComplexity(JsonNode root) {
        this.root = root;
    }

    /**
     * Why {@code schema} is too expensive to evaluate, or {@code null} when it is
     * not.
     */
    static String problem(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return null;
        }
        try {
            var analysis = new ArtifactSchemaComplexity(schema);
            analysis.indexAnchors(schema, true);
            if (analysis.weight(schema) > MAX_EXPANDED_SUBSCHEMAS) {
                return "the schema expands to more than " + MAX_EXPANDED_SUBSCHEMAS + " subschemas once its $refs are followed — every"
                        + " reference is evaluated on its own, so repeated references multiply. Reference each definition fewer times";
            }
            for (JsonNode target : analysis.recursionTargets) {
                String refused = analysis.recursionProblem(target);
                if (refused != null) {
                    return refused;
                }
            }
            return null;
        } catch (Refused e) {
            return e.getMessage();
        }
    }

    /**
     * Collects {@code $anchor}/{@code $dynamicAnchor} names and refuses a nested
     * {@code $id}.
     */
    private void indexAnchors(JsonNode node, boolean isRoot) {
        if (node.isObject()) {
            if (!isRoot && node.get("$id") != null && node.get("$id").isTextual()) {
                throw new Refused("a nested $id is not supported in an artifact schema: keep every subschema in the one document"
                        + " and reference it as \"#/$defs/...\"");
            }
            for (String keyword : List.of("$anchor", "$dynamicAnchor", "$recursiveAnchor")) {
                JsonNode anchor = node.get(keyword);
                if (anchor != null && anchor.isTextual()) {
                    anchors.computeIfAbsent(anchor.asText(), k -> new ArrayList<>()).add(node);
                }
            }
        }
        for (JsonNode child : node) {
            if (child.isContainerNode()) {
                indexAnchors(child, false);
            }
        }
    }

    /**
     * The subschemas {@code node} expands to, references followed once, memoised so
     * the count itself stays linear. A reference back into the current path counts
     * one and is judged separately ({@link #recursionProblem}).
     */
    private long weight(JsonNode node) {
        Long known = weights.get(node);
        if (known != null) {
            return known;
        }
        if (!onPath.add(node)) {
            recursionTargets.add(node);
            return 1;
        }
        if (onPath.size() > MAX_EXPANDED_SUBSCHEMAS) {
            // A chain of references this long expands past the budget on its own; stop
            // before it can exhaust the stack.
            onPath.remove(node);
            return MAX_EXPANDED_SUBSCHEMAS + 1L;
        }
        long total = 1;
        for (Edge edge : edges(node)) {
            total = Math.min(Long.MAX_VALUE / 4, total + weight(edge.target()));
            if (total > MAX_EXPANDED_SUBSCHEMAS) {
                break;
            }
        }
        onPath.remove(node);
        weights.put(node, total);
        return total;
    }

    /**
     * Walks every route from {@code target} back to itself and refuses one that
     * returns at the same instance location, or two that do not start by descending
     * into different named properties.
     */
    private String recursionProblem(JsonNode target) {
        List<List<String>> routes = new ArrayList<>();
        Set<JsonNode> path = Collections.newSetFromMap(new IdentityHashMap<>());
        path.add(target);
        collectRoutes(target, target, new ArrayList<>(), path, routes);
        Set<String> firstSteps = new HashSet<>();
        for (List<String> route : routes) {
            if (route.isEmpty()) {
                return "a $ref leads back to a schema it is part of without descending into the content, which recurses endlessly";
            }
            String first = route.getFirst();
            if (routes.size() > 1 && (ANY_CHILD.equals(first) || !firstSteps.add(first))) {
                return "a recursive $ref can reach the same part of the content by two routes, which doubles the validation work at"
                        + " every level of nesting. Recurse through one property, or through properties with different names";
            }
        }
        return null;
    }

    private void collectRoutes(JsonNode node, JsonNode target, List<String> descent, Set<JsonNode> path, List<List<String>> routes) {
        for (Edge edge : edges(node)) {
            if (++analysisSteps > MAX_ANALYSIS_STEPS) {
                throw new Refused("the schema's references are too intricate to check; simplify its $refs");
            }
            if (edge.step() != null) {
                descent.add(edge.step());
            }
            if (edge.target() == target) {
                routes.add(List.copyOf(descent));
            } else if (path.add(edge.target())) {
                collectRoutes(edge.target(), target, descent, path, routes);
                path.remove(edge.target());
            }
            if (edge.step() != null) {
                descent.removeLast();
            }
        }
    }

    /**
     * The subschemas {@code node} evaluates, each with the instance step it takes
     * ({@code null} for none). {@code $defs} are reached only through a reference.
     */
    private List<Edge> edges(JsonNode node) {
        List<Edge> edges = new ArrayList<>();
        if (!node.isObject()) {
            return edges;
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String keyword = field.getKey();
            JsonNode value = field.getValue();
            if (REFERENCES.contains(keyword)) {
                if (value.isTextual()) {
                    for (JsonNode target : resolve(keyword, value.asText())) {
                        edges.add(new Edge(null, target));
                    }
                }
            } else if (SAME_LOCATION_SCHEMA.contains(keyword)) {
                addSchema(edges, null, value);
            } else if (SAME_LOCATION_SCHEMA_LIST.contains(keyword) && value.isArray()) {
                value.forEach(item -> addSchema(edges, null, item));
            } else if (SAME_LOCATION_SCHEMA_MAP.contains(keyword) && value.isObject()) {
                value.forEach(item -> addSchema(edges, null, item));
            } else if ("properties".equals(keyword) && value.isObject()) {
                value.properties().forEach(property -> addSchema(edges, "p:" + property.getKey(), property.getValue()));
            } else if ("patternProperties".equals(keyword) && value.isObject()) {
                value.forEach(item -> addSchema(edges, ANY_CHILD, item));
            } else if ("prefixItems".equals(keyword) && value.isArray()) {
                value.forEach(item -> addSchema(edges, ANY_CHILD, item));
            } else if (DESCENDING_SCHEMA.contains(keyword)) {
                if (value.isArray()) {
                    value.forEach(item -> addSchema(edges, ANY_CHILD, item));
                } else {
                    addSchema(edges, ANY_CHILD, value);
                }
            }
        }
        return edges;
    }

    private static void addSchema(List<Edge> edges, String step, JsonNode schema) {
        if (schema != null && schema.isObject()) {
            edges.add(new Edge(step, schema));
        }
    }

    private List<JsonNode> resolve(String keyword, String reference) {
        if ("$recursiveRef".equals(keyword)) {
            return List.of(root);
        }
        String ref = sameDocumentFragment(reference);
        if (ref == null) {
            // Another document: a bundled meta-schema (a fixed cost), or one the schema
            // loader refuses to load — either way nothing here to expand.
            return List.of();
        }
        if (ref.isEmpty() || "#".equals(ref)) {
            return List.of(root);
        }
        if (ref.startsWith("#/")) {
            JsonNode target = root.at(decodePointer(ref.substring(1)));
            if (target.isMissingNode()) {
                throw new Refused("$ref \"" + reference + "\" does not point at anything in the schema");
            }
            return List.of(target);
        }
        if (ref.startsWith("#")) {
            List<JsonNode> named = anchors.get(ref.substring(1));
            if (named == null) {
                throw new Refused("$ref \"" + reference + "\" names an anchor the schema does not define");
            }
            // A $dynamicRef may land on any anchor of that name, so it counts as all of
            // them.
            return "$dynamicRef".equals(keyword) ? named : List.of(named.getFirst());
        }
        throw new Refused("$ref \"" + reference + "\" is not a JSON pointer or an anchor of this schema");
    }

    /**
     * The fragment ({@code "#..."}, or {@code ""} for the whole document) when
     * {@code reference} points into this document, or {@code null} when it names
     * another one. A relative reference resolves against the root {@code $id};
     * without one there is nothing to resolve it against, so it is refused rather
     * than guessed at.
     */
    private String sameDocumentFragment(String reference) {
        if (reference.isEmpty() || reference.startsWith("#")) {
            return reference;
        }
        URI resolved;
        URI base;
        try {
            URI target = new URI(reference);
            String rootId = root.path("$id").asText("");
            base = rootId.isEmpty() ? null : new URI(rootId);
            if (base == null || !base.isAbsolute()) {
                if (target.isAbsolute()) {
                    return null;
                }
                throw new Refused("$ref \"" + reference + "\" is relative and the schema has no absolute $id to resolve it against;"
                        + " use \"#/$defs/...\"");
            }
            resolved = base.resolve(target);
        } catch (URISyntaxException e) {
            throw new Refused("$ref \"" + reference + "\" is not a valid URI reference");
        }
        if (!withoutFragment(resolved).equals(withoutFragment(base))) {
            return null;
        }
        return resolved.getRawFragment() == null ? "" : "#" + resolved.getRawFragment();
    }

    private static String withoutFragment(URI uri) {
        String text = uri.toString();
        int hash = text.indexOf('#');
        return hash < 0 ? text : text.substring(0, hash);
    }

    /**
     * A JSON pointer from a URI fragment: percent-escapes decoded, as RFC 6901 §6
     * says.
     */
    private static String decodePointer(String fragment) {
        try {
            return URLDecoder.decode(fragment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new Refused("$ref \"#" + fragment + "\" is not a valid JSON pointer");
        }
    }

    private record Edge(String step, JsonNode target) {
    }

    private static final class Refused extends RuntimeException {
        Refused(String message) {
            super(message, null, false, false);
        }
    }
}
