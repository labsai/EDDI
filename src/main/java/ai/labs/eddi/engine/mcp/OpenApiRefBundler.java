/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.parser.ObjectMapperFactory;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
 * every reference has been resolved here already. That base is not tracked: a
 * relative or {@code #} reference inside a schema whose {@code $id} names a
 * different base is refused, rather than resolved against the document's own
 * URL and so against the wrong schema. The serialized result is capped at
 * {@value #MAX_SERIALIZED_CHARS} characters while it is written.
 */
final class OpenApiRefBundler {

    private static final Logger LOGGER = Logger.getLogger(OpenApiRefBundler.class);

    /** Distinct remote documents a spec may reference, directly or not. */
    static final int MAX_REMOTE_DOCUMENTS = 32;

    /** Longest chain of references followed into remote documents. */
    static final int MAX_REF_DEPTH = 16;

    /** Most JSON nodes the bundled document may hold. */
    static final int MAX_EXPANDED_NODES = 500_000;

    /**
     * Most characters the bundled document may serialize to: twice the cap on one
     * fetched spec, room for a spec plus the documents it references.
     */
    static final int MAX_SERIALIZED_CHARS = 32 * 1024 * 1024;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final OpenApiSpecFetcher fetcher;
    private final String rootUrl;
    private final Map<String, Doc> documents = new HashMap<>();
    private long fetchedChars;
    private int expandedNodes;
    /**
     * Distinct documents fetched: {@link #documents} also holds redirect aliases.
     */
    private int fetchedDocuments;
    /**
     * How many enclosing objects carry an {@code $id} that changes the base URI.
     */
    private int foreignBaseDepth;
    /**
     * Schemas a remote discriminator mapping names, moved into the root: key to
     * name.
     */
    private final Map<String, String> relocatedNames = new HashMap<>();
    /** Their expanded content, by the root component name they were given. */
    private final ObjectNode relocated = NODES.objectNode();
    /** Component schema names the root document already has. */
    private final Set<String> rootSchemaNames = new HashSet<>();

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
        return bundle(content, location, fetcher, MAX_SERIALIZED_CHARS);
    }

    /** {@link #bundle(String, String, OpenApiSpecFetcher)} with its output cap. */
    static String bundle(String content, String location, OpenApiSpecFetcher fetcher, int maxSerializedChars) {
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
        root.path("components").path("schemas").properties().forEach(entry -> bundler.rootSchemaNames.add(entry.getKey()));
        JsonNode bundled = bundler.expand(root, new Doc(bundler.rootUrl, root, true), new ArrayDeque<>());
        bundler.attachRelocated(bundled);
        requireInternalRefsOnly(bundled);
        // Written through a bounded writer, not into one String: inlining shares a
        // referenced value instead of copying it, so the node cap does not measure
        // output size - a 1 MB description referenced 10,000 times serializes to
        // 10 GB. The writer refuses past the cap as it is written.
        var out = new BoundedWriter(maxSerializedChars);
        try {
            ObjectMapperFactory.createJson().writeValue(out, bundled);
        } catch (IOException e) {
            throw new IllegalArgumentException("OpenAPI spec is larger than " + maxSerializedChars
                    + " characters once its $refs are inlined, or could not be re-serialized; refused", e);
        }
        return out.toString();
    }

    /** Collects serialized output and refuses to grow past a fixed size. */
    static final class BoundedWriter extends Writer {
        private final StringBuilder out = new StringBuilder();
        private final int limit;

        BoundedWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            if ((long) out.length() + length > limit) {
                throw new IOException("serialized output exceeds " + limit + " characters");
            }
            out.append(buffer, offset, length);
        }

        @Override
        public void flush() {
            // in memory
        }

        @Override
        public void close() {
            // in memory
        }

        @Override
        public String toString() {
            return out.toString();
        }
    }

    /**
     * Whether {@code root} holds an external {@code $ref} or a textual {@code $id}.
     */
    private static boolean needsBundling(JsonNode root) {
        boolean[] found = {false};
        forEachKeywordObject(root, false, node -> {
            JsonNode ref = node.get("$ref");
            JsonNode id = node.get("$id");
            if (ref != null && ref.isTextual() && !ref.asText().trim().startsWith("#") || id != null && id.isTextual()) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * Fields whose value is the spec author's data, not OpenAPI structure: an
     * {@code example}, {@code default}, {@code const} or {@code enum} value, an
     * Example Object's {@code value}, a JSON Schema {@code examples} array, and any
     * {@code x-} extension. A {@code $ref} inside one is a value like any other —
     * an example payload may well hold one — and is neither a reference nor
     * fetched.
     */
    private static final Set<String> LITERAL_FIELDS = Set.of("example", "default", "const", "enum", "value");

    /**
     * Fields whose value maps names to OpenAPI objects: inside one, a key is a name
     * (a property may be called {@code example}), never a keyword.
     */
    private static final Set<String> NAME_MAPS = Set.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas",
            "schemas", "parameters", "responses", "examples", "headers", "requestBodies", "securitySchemes", "links", "callbacks",
            "pathItems", "paths", "webhooks", "content", "encoding", "variables");

    static boolean isLiteralField(String name, JsonNode value, boolean inNameMap) {
        if (inNameMap) {
            // A key of a name map is a name - a schema may be called "x-tenant" - and its
            // value is checked like any other object.
            return false;
        }
        return name.startsWith("x-") || LITERAL_FIELDS.contains(name) || "examples".equals(name) && value.isArray();
    }

    private static boolean isNameMap(String name, JsonNode value, boolean inNameMap) {
        return !inNameMap && value.isObject() && NAME_MAPS.contains(name);
    }

    /**
     * Visits every object of {@code node} that holds OpenAPI keywords — not a name
     * map, not inside literal data — in one pass.
     */
    private static void forEachKeywordObject(JsonNode node, boolean nameMap, Consumer<JsonNode> visit) {
        if (node.isObject()) {
            if (!nameMap) {
                visit.accept(node);
            }
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!isLiteralField(field.getKey(), field.getValue(), nameMap)) {
                    forEachKeywordObject(field.getValue(), isNameMap(field.getKey(), field.getValue(), nameMap), visit);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                forEachKeywordObject(element, false, visit);
            }
        }
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
        forEachKeywordObject(root, false, node -> {
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
        });
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
        return expand(node, doc, chain, false);
    }

    private JsonNode expand(JsonNode node, Doc doc, Deque<String> chain, boolean nameMap) {
        if (++expandedNodes > MAX_EXPANDED_NODES) {
            throw new IllegalArgumentException(
                    "OpenAPI spec expands to more than " + MAX_EXPANDED_NODES + " nodes once its $refs are inlined; refused");
        }
        if (node.isObject() && nameMap) {
            // A map of names: every value is an OpenAPI object of its own, and no key is
            // a keyword ($ref, $id, example, ... are names here).
            ObjectNode out = NODES.objectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                out.set(field.getKey(), expand(field.getValue(), doc, chain, false));
            }
            return out;
        }
        if (node.isObject()) {
            JsonNode id = node.get("$id");
            boolean entersForeignBase = id != null && id.isTextual() && changesBase(id.asText().trim(), doc);
            if (entersForeignBase) {
                foreignBaseDepth++;
            }
            try {
                JsonNode ref = node.get("$ref");
                if (ref != null && ref.isTextual()) {
                    String refText = ref.asText().trim();
                    if (foreignBaseDepth > 0 && !hasScheme(refText)) {
                        // OpenAPI 3.1: a schema's $id is the base its relative $refs (and its
                        // #-references) resolve against. EDDI does not track that base, and
                        // resolving against the document's own URL would load a different
                        // schema - so the reference is refused, never resolved wrongly.
                        throw new IllegalArgumentException("OpenAPI spec has a relative $ref inside a schema whose $id changes the base "
                                + "URI; such references are not resolved. Write the $ref as an absolute http(s) URL; refused");
                    }
                    JsonNode replaced = resolveRef(refText, node, doc, chain);
                    if (replaced != null) {
                        return replaced;
                    }
                }
                ObjectNode out = NODES.objectNode();
                for (Map.Entry<String, JsonNode> field : node.properties()) {
                    String name = field.getKey();
                    JsonNode value = field.getValue();
                    if ("$id".equals(name) && value.isTextual()) {
                        continue;
                    }
                    // Literal data is copied as it is: a $ref in an example is not a reference.
                    out.set(name, isLiteralField(name, value, false) ? value : expand(value, doc, chain, isNameMap(name, value, false)));
                }
                if (!doc.root()) {
                    relocateDiscriminatorMapping(out, doc, chain);
                }
                return out;
            } finally {
                if (entersForeignBase) {
                    foreignBaseDepth--;
                }
            }
        }
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode(node.size());
            for (JsonNode element : node) {
                out.add(expand(element, doc, chain, false));
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
            LOGGER.debugf("OpenAPI $ref %s is recursive; replaced by an empty schema", sanitize(OpenApiSpecFetcher.forMessage(target)));
            return NODES.objectNode();
        }
        if (chain.size() >= MAX_REF_DEPTH) {
            throw new IllegalArgumentException("OpenAPI spec $ref chain is deeper than " + MAX_REF_DEPTH + " levels at "
                    + OpenApiSpecFetcher.forMessage(target) + "; refused");
        }
        Doc referenced = load(docUrl, target);
        JsonNode node = pointAt(referenced.tree(), fragment, target);
        chain.push(key);
        // The referenced document is its own base scope: an $id around the reference
        // does not reach into it.
        int outerForeignBaseDepth = foreignBaseDepth;
        foreignBaseDepth = 0;
        JsonNode expanded;
        try {
            expanded = expand(node, referenced, chain);
        } finally {
            foreignBaseDepth = outerForeignBaseDepth;
            chain.pop();
        }
        if (expanded.isObject() && holder.size() > 1) {
            // OpenAPI 3.1 lets a reference carry a summary or description beside it.
            expanded = copySiblings(holder, (ObjectNode) expanded, doc, chain);
        }
        return expanded;
    }

    /** Bounds where the smaller value is the stricter one. */
    private static final Set<String> UPPER_BOUNDS = Set.of("maxLength", "maxItems", "maxProperties", "maximum", "exclusiveMaximum",
            "maxContains");

    /** Bounds where the larger value is the stricter one. */
    private static final Set<String> LOWER_BOUNDS = Set.of("minLength", "minItems", "minProperties", "minimum", "exclusiveMinimum",
            "minContains");

    /**
     * {@code holder}'s fields other than {@code $ref}, merged into {@code into}.
     * Under OpenAPI 3.1 both the reference's target and its siblings apply, and
     * {@code OpenApiArgumentSchemas} turns an {@code allOf} of non-object parts
     * into an untyped schema, so the two are merged into one schema here instead: a
     * field only one side has is kept; a numeric bound both have keeps the stricter
     * value (the smaller maximum, the larger minimum); {@code required} keeps the
     * union; {@code enum} keeps the values both allow; any other field both have
     * takes the holder's value (the author's local refinement — a description, a
     * pattern).
     */
    private ObjectNode copySiblings(JsonNode holder, ObjectNode into, Doc doc, Deque<String> chain) {
        for (Map.Entry<String, JsonNode> field : holder.properties()) {
            String name = field.getKey();
            if ("$ref".equals(name) || "$id".equals(name) && field.getValue().isTextual()) {
                continue;
            }
            JsonNode sibling = isLiteralField(name, field.getValue(), false)
                    ? field.getValue()
                    : expand(field.getValue(), doc, chain, isNameMap(name, field.getValue(), false));
            JsonNode existing = into.get(name);
            into.set(name, existing == null ? sibling : stricter(name, existing, sibling));
        }
        return into;
    }

    private static JsonNode stricter(String name, JsonNode target, JsonNode holder) {
        if (target.isNumber() && holder.isNumber()) {
            int order = new BigDecimal(target.asText()).compareTo(new BigDecimal(holder.asText()));
            if (UPPER_BOUNDS.contains(name)) {
                return order <= 0 ? target : holder;
            }
            if (LOWER_BOUNDS.contains(name)) {
                return order >= 0 ? target : holder;
            }
        }
        if ("required".equals(name) && target.isArray() && holder.isArray()) {
            Set<String> union = new LinkedHashSet<>();
            target.forEach(value -> union.add(value.asText()));
            holder.forEach(value -> union.add(value.asText()));
            ArrayNode merged = NODES.arrayNode();
            union.forEach(merged::add);
            return merged;
        }
        if ("enum".equals(name) && target.isArray() && holder.isArray()) {
            // Indexed, so two large disjoint enums cost n + m, not n x m comparisons;
            // the holder's order is kept.
            Set<JsonNode> allowed = new HashSet<>();
            target.forEach(allowed::add);
            ArrayNode both = NODES.arrayNode();
            for (JsonNode value : holder) {
                if (allowed.contains(value)) {
                    both.add(value);
                }
            }
            return both;
        }
        return holder;
    }

    /**
     * Rewrites the discriminator mapping of an object copied in from a referenced
     * document. Its values name schemas of THAT document ({@code #/components/...}
     * there, or a bare component name), which the root does not have: each mapped
     * schema is moved into the root's {@code components.schemas} under a name of
     * its own, and the value is rewritten to point at it.
     */
    private void relocateDiscriminatorMapping(ObjectNode out, Doc doc, Deque<String> chain) {
        JsonNode discriminator = out.get("discriminator");
        JsonNode mapping = discriminator == null ? null : discriminator.get("mapping");
        if (!(discriminator instanceof ObjectNode discriminatorNode) || mapping == null || !mapping.isObject()) {
            return;
        }
        ObjectNode rewritten = NODES.objectNode();
        for (Map.Entry<String, JsonNode> entry : mapping.properties()) {
            if (!entry.getValue().isTextual()) {
                rewritten.set(entry.getKey(), entry.getValue());
                continue;
            }
            String value = entry.getValue().asText().trim();
            boolean bareName = !value.startsWith("#") && value.indexOf('.') < 0 && value.indexOf('/') < 0;
            rewritten.put(entry.getKey(), relocate(bareName ? "#/components/schemas/" + value : value, doc, chain));
        }
        discriminatorNode.set("mapping", rewritten);
    }

    /**
     * The root reference a mapped schema of {@code doc} is reached by once moved.
     */
    private String relocate(String ref, Doc doc, Deque<String> chain) {
        URI target = absolute(ref, doc);
        String docUrl = withoutFragment(target);
        String fragment = target.getFragment() == null ? "" : target.getFragment();
        if (docUrl.equals(rootUrl)) {
            return "#" + fragment;
        }
        String key = docUrl + "#" + fragment;
        String existing = relocatedNames.get(key);
        if (existing != null) {
            return "#/components/schemas/" + existing;
        }
        String name = relocatedName(fragment);
        // Recorded before expanding, so a mapped schema whose own mapping names this
        // one again reuses the name instead of recursing.
        relocatedNames.put(key, name);
        relocated.set(name, NODES.objectNode());
        ObjectNode holder = NODES.objectNode();
        holder.put("$ref", ref);
        JsonNode schema = resolveRef(ref, holder, doc, chain);
        relocated.set(name, schema == null ? NODES.objectNode() : schema);
        return "#/components/schemas/" + name;
    }

    /** A component name from the fragment's last segment, unique in the root. */
    private String relocatedName(String fragment) {
        String last = fragment.substring(fragment.lastIndexOf('/') + 1).replace("~1", "/").replace("~0", "~");
        StringBuilder base = new StringBuilder(last.length());
        for (int i = 0; i < last.length(); i++) {
            char c = last.charAt(i);
            base.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        String stem = base.isEmpty() ? "Schema" : base.toString();
        String name = stem;
        for (int n = 2; rootSchemaNames.contains(name) || relocated.has(name); n++) {
            name = stem + "_" + n;
        }
        return name;
    }

    /**
     * Adds the relocated schemas to the bundled root's {@code components.schemas}.
     */
    private void attachRelocated(JsonNode bundled) {
        if (relocated.isEmpty() || !(bundled instanceof ObjectNode root)) {
            return;
        }
        JsonNode components = root.get("components");
        ObjectNode componentsNode = components instanceof ObjectNode object ? object : root.putObject("components");
        JsonNode schemas = componentsNode.get("schemas");
        ObjectNode schemasNode = schemas instanceof ObjectNode object ? object : componentsNode.putObject("schemas");
        relocated.properties().forEach(entry -> schemasNode.set(entry.getKey(), entry.getValue()));
    }

    /**
     * Whether an {@code $id} sets a base other than the document's own: anything
     * but a fragment ({@code #anchor}) or the document's URL itself.
     */
    private static boolean changesBase(String id, Doc doc) {
        if (id.isEmpty() || id.startsWith("#")) {
            return false;
        }
        if (doc.url() == null) {
            return true;
        }
        try {
            return !withoutFragment(new URI(doc.url()).resolve(new URI(id))).equals(doc.url());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean hasScheme(String ref) {
        try {
            return new URI(ref).getScheme() != null;
        } catch (URISyntaxException e) {
            return false;
        }
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
        if (fetchedDocuments >= MAX_REMOTE_DOCUMENTS) {
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
        fetchedDocuments++;
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
