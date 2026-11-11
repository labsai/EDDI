/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.parser.ObjectMapperFactory;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Inlines every external {@code $ref} of an OpenAPI document before
 * swagger-parser sees it, so the parser never touches the network.
 * <p>
 * swagger-parser, asked to resolve, fetched a remote {@code $ref} with its own
 * {@code HttpURLConnection} client: a deny-list of its own that misses
 * {@code 100.64.0.0/10} (Alibaba's metadata service at
 * {@code 100.100.100.200}), Azure's {@code 168.63.129.16} and Oracle's
 * {@code 192.0.0.192}, ignores {@code eddi.security.ssrf-protection.*},
 * resolves the host again after checking it, follows same-scheme redirects
 * unchecked and reads the body without a limit. Here each referenced document
 * is fetched by {@link OpenApiSpecFetcher} instead — the deployment's
 * {@code OutboundTargetPolicy} on every hop, the same size cap as the spec
 * itself — and the referenced node is copied in place of the reference. What
 * the parser gets afterwards holds internal ({@code #/…}) references only,
 * which {@link #bundle} verifies before returning.
 * <p>
 * Bounded: at most {@value #MAX_REMOTE_DOCUMENTS} distinct documents, their
 * combined size at most {@link OpenApiSpecFetcher#MAX_SPEC_BYTES}, a reference
 * chain at most {@value #MAX_REF_DEPTH} deep and at most
 * {@value #MAX_EXPANDED_NODES} nodes written — so a document that references
 * the same large schema many times over cannot expand without limit. A
 * reference that leads back into a chain being expanded (a recursive schema) is
 * replaced by an empty schema rather than expanded forever. A reference that
 * cannot be fetched or does not point at a node is refused with the reason.
 * <p>
 * A textual {@code $id} is removed from the output: in an OpenAPI 3.1 schema it
 * is the base the parser would resolve that schema's references against, and
 * every reference has been resolved here already.
 */
final class OpenApiRefBundler {

    private static final Logger LOGGER = Logger.getLogger(OpenApiRefBundler.class);

    /** Distinct remote documents a spec may reference, directly or not. */
    static final int MAX_REMOTE_DOCUMENTS = 32;

    /** Longest chain of references followed into remote documents. */
    static final int MAX_REF_DEPTH = 16;

    /** Most JSON nodes the bundled document may hold. */
    static final int MAX_EXPANDED_NODES = 500_000;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final OpenApiSpecFetcher fetcher;
    private final String rootUrl;
    private final Map<String, Doc> documents = new HashMap<>();
    private long fetchedChars;
    private int expandedNodes;

    private record Doc(String url, JsonNode tree, boolean root) {
    }

    private OpenApiRefBundler(OpenApiSpecFetcher fetcher, String rootUrl) {
        this.fetcher = fetcher;
        this.rootUrl = rootUrl;
    }

    /**
     * {@code content} with its external references inlined, or {@code content}
     * itself when it has none.
     *
     * @param content
     *            the spec, JSON or YAML
     * @param location
     *            the URL it was read from, against which its relative references
     *            resolve; {@code null} for an inline spec, whose external
     *            references must then be absolute http(s) URLs
     * @param fetcher
     *            fetches every referenced document
     * @throws IllegalArgumentException
     *             when a reference is refused by the outbound policy, cannot be
     *             fetched or read, does not point at a node, or a limit is exceeded
     */
    static String bundle(String content, String location, OpenApiSpecFetcher fetcher) {
        JsonNode root = McpApiToolBuilder.deserializeLikeParser(content);
        if (root == null) {
            return content;
        }
        if (!needsBundling(root)) {
            // Nothing to inline, but the other reference-bearing fields are checked all
            // the same: a discriminator mapping is not a $ref and still makes the parser
            // fetch.
            requireInternalRefsOnly(root);
            return content;
        }
        var bundler = new OpenApiRefBundler(fetcher, location == null ? null : withoutFragment(URI.create(location)));
        JsonNode bundled = bundler.expand(root, new Doc(bundler.rootUrl, root, true), new ArrayDeque<>());
        requireInternalRefsOnly(bundled);
        try {
            return ObjectMapperFactory.createJson().writeValueAsString(bundled);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("OpenAPI spec could not be re-serialized after inlining its $refs", e);
        }
    }

    /**
     * Whether {@code root} holds an external {@code $ref} or a textual {@code $id}.
     */
    private static boolean needsBundling(JsonNode root) {
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node.isObject()) {
                JsonNode ref = node.get("$ref");
                if (ref != null && ref.isTextual() && !ref.asText().trim().startsWith("#")) {
                    return true;
                }
                JsonNode id = node.get("$id");
                if (id != null && id.isTextual()) {
                    return true;
                }
            }
            if (node.isContainerNode()) {
                node.forEach(pending::push);
            }
        }
        return false;
    }

    /**
     * The last line of defence: the parser must be handed internal references only,
     * or it would fetch the rest itself.
     * <p>
     * swagger-parser 2.1.48 reads a document only through
     * {@code ResolverCache.loadRef} (OpenAPI 3.0) and {@code ReferenceVisitor}
     * (3.1). Every path into them starts at an object's {@code $ref} — schema,
     * parameter, response, request body, header, example, link, callback, security
     * scheme, path item — with one exception:
     * {@code SchemaProcessor.processDiscriminatorSchema} and
     * {@code ExternalRefProcessor.processDiscriminator} turn each discriminator
     * {@code mapping} value into a schema {@code $ref} and resolve it. An example's
     * {@code externalValue}, a link's {@code operationRef}, {@code $anchor},
     * {@code $schema} and {@code $dynamicRef} are never read from anywhere; a
     * schema {@code $id} only re-bases references and is removed by
     * {@link #expand}. So the check covers {@code $ref} and discriminator mapping
     * values; a mapping value that is not internal is refused rather than inlined,
     * since it names a schema and not a node that could take its place.
     */
    private static void requireInternalRefsOnly(JsonNode root) {
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node.isObject()) {
                JsonNode ref = node.get("$ref");
                if (ref != null && ref.isValueNode() && !ref.asText().trim().startsWith("#")) {
                    throw new IllegalArgumentException("OpenAPI spec still holds an external $ref after inlining; refused");
                }
                JsonNode mapping = node.path("discriminator").path("mapping");
                if (mapping.isObject()) {
                    for (JsonNode value : mapping) {
                        if (value.isValueNode() && !isInternalMappingValue(value.asText().trim())) {
                            throw new IllegalArgumentException("OpenAPI spec has a discriminator mapping to '" + value.asText().trim()
                                    + "', outside the document: only #/... references and component names are allowed there");
                        }
                    }
                }
            }
            if (node.isContainerNode()) {
                node.forEach(pending::push);
            }
        }
    }

    /**
     * A mapping value swagger-parser resolves in the document itself: a {@code #}
     * reference, or a bare component name, which {@code Schema.set$ref} turns into
     * {@code #/components/schemas/<name>} when it holds no {@code .} and no
     * {@code /}.
     */
    static boolean isInternalMappingValue(String value) {
        return value.startsWith("#") || value.indexOf('.') < 0 && value.indexOf('/') < 0;
    }

    private JsonNode expand(JsonNode node, Doc doc, Deque<String> chain) {
        if (++expandedNodes > MAX_EXPANDED_NODES) {
            throw new IllegalArgumentException(
                    "OpenAPI spec expands to more than " + MAX_EXPANDED_NODES + " nodes once its $refs are inlined; refused");
        }
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                JsonNode replaced = resolveRef(ref.asText().trim(), node, doc, chain);
                if (replaced != null) {
                    return replaced;
                }
            }
            ObjectNode out = NODES.objectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if ("$id".equals(field.getKey()) && field.getValue().isTextual()) {
                    continue;
                }
                out.set(field.getKey(), expand(field.getValue(), doc, chain));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode(node.size());
            for (JsonNode element : node) {
                out.add(expand(element, doc, chain));
            }
            return out;
        }
        return node;
    }

    /**
     * What replaces the object holding {@code ref}, or {@code null} when it stays
     * as it is (an internal reference of the root document).
     */
    private JsonNode resolveRef(String ref, JsonNode holder, Doc doc, Deque<String> chain) {
        if (ref.startsWith("#") && doc.root()) {
            return null;
        }
        URI target = absolute(ref, doc);
        String docUrl = withoutFragment(target);
        String fragment = target.getFragment() == null ? "" : target.getFragment();
        if (docUrl.equals(rootUrl)) {
            // Points back into the root document: keep it a reference the parser
            // resolves locally.
            ObjectNode local = copySiblings(holder, NODES.objectNode(), doc, chain);
            local.put("$ref", "#" + fragment);
            return local;
        }
        String key = docUrl + "#" + fragment;
        if (chain.contains(key)) {
            LOGGER.debugf("OpenAPI $ref %s is recursive; replaced by an empty schema", OpenApiSpecFetcher.forMessage(target));
            return NODES.objectNode();
        }
        if (chain.size() >= MAX_REF_DEPTH) {
            throw new IllegalArgumentException("OpenAPI spec $ref chain is deeper than " + MAX_REF_DEPTH + " levels at "
                    + OpenApiSpecFetcher.forMessage(target) + "; refused");
        }
        Doc referenced = load(docUrl, target);
        JsonNode node = pointAt(referenced.tree(), fragment, target);
        chain.push(key);
        JsonNode expanded;
        try {
            expanded = expand(node, referenced, chain);
        } finally {
            chain.pop();
        }
        if (expanded.isObject() && holder.size() > 1) {
            // OpenAPI 3.1 lets a reference carry a summary or description beside it.
            expanded = copySiblings(holder, (ObjectNode) expanded, doc, chain);
        }
        return expanded;
    }

    /** {@code holder}'s fields other than {@code $ref} that {@code into} lacks. */
    private ObjectNode copySiblings(JsonNode holder, ObjectNode into, Doc doc, Deque<String> chain) {
        for (Map.Entry<String, JsonNode> field : holder.properties()) {
            String name = field.getKey();
            if (!"$ref".equals(name) && !into.has(name) && !("$id".equals(name) && field.getValue().isTextual())) {
                into.set(name, expand(field.getValue(), doc, chain));
            }
        }
        return into;
    }

    private static URI absolute(String ref, Doc doc) {
        URI target;
        try {
            URI parsed = new URI(ref);
            if (doc.url() == null) {
                target = parsed;
            } else {
                target = new URI(doc.url()).resolve(parsed);
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalArgumentException("OpenAPI spec contains a $ref that is not a valid URI reference; refused", e);
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!target.isAbsolute() || !("http".equals(scheme) || "https".equals(scheme)) || target.getHost() == null) {
            throw new IllegalArgumentException("OpenAPI spec contains an external $ref that does not resolve to an http(s) URL; refused");
        }
        return target;
    }

    private Doc load(String docUrl, URI target) {
        Doc cached = documents.get(docUrl);
        if (cached != null) {
            return cached;
        }
        if (documents.size() >= MAX_REMOTE_DOCUMENTS) {
            throw new IllegalArgumentException(
                    "OpenAPI spec references more than " + MAX_REMOTE_DOCUMENTS + " remote documents through its $refs; refused");
        }
        OpenApiSpecFetcher.FetchedSpec fetched;
        try {
            fetched = fetcher.fetchSpec(docUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "OpenAPI spec $ref " + OpenApiSpecFetcher.forMessage(target) + " refused: " + e.getMessage(), e);
        }
        fetchedChars += fetched.content().length();
        if (fetchedChars > OpenApiSpecFetcher.MAX_SPEC_BYTES) {
            throw new IllegalArgumentException("The documents an OpenAPI spec references through its $refs are larger than "
                    + OpenApiSpecFetcher.MAX_SPEC_BYTES + " bytes together; refused");
        }
        // A referenced document is the publisher's: the same scheme rules as a
        // downloaded spec (no file:, classpath:, UNC or local $id base).
        McpApiToolBuilder.rejectUnsafeRefs(fetched.content(), true);
        JsonNode tree = McpApiToolBuilder.deserializeLikeParser(fetched.content());
        if (tree == null) {
            throw new IllegalArgumentException("OpenAPI spec $ref " + OpenApiSpecFetcher.forMessage(target) + " points at an empty document");
        }
        // Relative references in it resolve beside the URL it was finally read from.
        Doc doc = new Doc(withoutFragment(URI.create(fetched.location())), tree, false);
        documents.put(docUrl, doc);
        documents.putIfAbsent(doc.url(), doc);
        return doc;
    }

    private static JsonNode pointAt(JsonNode tree, String fragment, URI target) {
        if (fragment.isEmpty()) {
            return tree;
        }
        JsonNode node;
        try {
            node = tree.at(JsonPointer.compile(fragment));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OpenAPI spec $ref " + OpenApiSpecFetcher.forMessage(target)
                    + " has a fragment that is not a JSON pointer; refused", e);
        }
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException("OpenAPI spec $ref " + OpenApiSpecFetcher.forMessage(target) + "#" + fragment
                    + " points at nothing in the referenced document");
        }
        return node;
    }

    private static String withoutFragment(URI uri) {
        String text = uri.toString();
        int hash = text.indexOf('#');
        return hash < 0 ? text : text.substring(0, hash);
    }
}
