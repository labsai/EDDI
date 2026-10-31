/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.patch;

import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies an RFC 6902 JSON Patch to a stored configuration document and hands
 * back the result as the same typed model a {@code PUT} would have produced.
 * <p>
 * <b>Why this exists.</b> A client that edits a configuration with a full
 * {@code PUT} has to send the whole document, and the document it read back
 * carries every credential <em>reference</em> ({@code ${vault:…}}) the
 * configuration holds. Sending those references back is not a no-op: an API
 * call made by an agent resolves references in its rendered request just before
 * it is sent, so an echoed reference would reach the store as the
 * <em>plaintext</em> secret — and {@code ConfigReferenceGuard} refuses the echo
 * in the first place, because a reference in a request body that the
 * configuration did not itself write is indistinguishable from one an attacker
 * smuggled through conversation data. A patch never needs to mention what it
 * does not change, so it never needs to carry a credential, and the guard can
 * stay exactly as strict as it is.
 * <p>
 * <b>Validation is the {@code PUT} validation.</b> The patched tree is
 * re-parsed with {@link StrictConfigurationParser} — the same strict parse the
 * {@code PUT} body interceptor applies — so an unknown field or a value of the
 * wrong shape is a 400 that names it, and nothing a {@code PUT} would refuse is
 * storable through here.
 * <p>
 * <b>Library.</b> {@code jakarta.json} ({@link Json#createPatch}); Parsson is
 * already on the classpath through {@code quarkus-rest}, so no dependency is
 * added. The patch body is parsed and bounded here rather than bound by a
 * JAX-RS reader, so a malformed body is always this class's 400.
 */
@ApplicationScoped
public class ConfigJsonPatcher {

    /** The media type of an RFC 6902 patch document. */
    public static final String JSON_PATCH_MEDIA_TYPE = "application/json-patch+json";

    /**
     * Upper bound on the patch body, in UTF-8 bytes: a patch is a diff, not a
     * document.
     */
    static final int MAX_PATCH_BYTES = 256 * 1024;

    /**
     * Reads a patch body once, only to refuse what two parsers could read
     * differently. The Manager's approval guard reads the same body with
     * {@code JSON.parse}, where the LAST of two equal keys wins; if this side ever
     * resolved a duplicate the other way, the guard would judge one patch and the
     * server would apply another. A body with a duplicate key at any depth, or with
     * anything after its first value, is refused instead of interpreted.
     */
    private static final ObjectMapper UNAMBIGUOUS = new ObjectMapper(new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Upper bound on the number of operations in one patch. */
    static final int MAX_OPERATIONS = 200;

    /**
     * Copies may grow the document to at most this many times its stored
     * {@linkplain #weight size}, plus {@link #MAX_GROWTH_BYTES}.
     */
    static final int MAX_GROWTH_FACTOR = 2;

    /** Head-room on top of {@link #MAX_GROWTH_FACTOR}, for small documents. */
    static final long MAX_GROWTH_BYTES = 4L * MAX_PATCH_BYTES;

    private final ObjectMapper mapper;
    private final StrictConfigurationParser parser;

    @Inject
    public ConfigJsonPatcher(ObjectMapper mapper, StrictConfigurationParser parser) {
        this.mapper = mapper;
        this.parser = parser;
    }

    /**
     * {@link #apply(Object, String, Class, boolean)} for a caller who may read
     * every credential the document holds (an administrator).
     */
    public <T> T apply(T current, String patchBody, Class<T> type) {
        return apply(current, patchBody, type, false);
    }

    /**
     * @param current
     *            the stored document the patch is applied to
     * @param patchBody
     *            the RFC 6902 patch, as JSON text
     * @param type
     *            the configuration model
     * @param protectCredentials
     *            {@code true} for a caller whose reads are redacted (anyone but an
     *            eddi-admin). The patch is then applied to a view in which every
     *            plaintext credential is replaced by an opaque token, and a token
     *            is turned back into its credential only where it still sits at the
     *            path it came from. A {@code copy} or {@code move} that would carry
     *            a credential into an ordinary, readable field is refused, and a
     *            {@code test} probing one never sees the value.
     * @return the patched document, parsed strictly
     * @throws BadRequestException
     *             when the body is not a patch, an operation is unsupported or
     *             fails (a {@code test} that does not hold, a path that does not
     *             resolve), copies grow the result past {@link #MAX_GROWTH_FACTOR}
     *             times the stored document, a protected credential would be
     *             relocated, or the patched document is not a valid {@code type}
     */
    public <T> T apply(T current, String patchBody, Class<T> type, boolean protectCredentials) {
        JsonArray operations = parseOperations(patchBody);
        CredentialMask mask = protectCredentials ? CredentialMask.of(mapper, current, type) : null;
        JsonStructure document = mask != null ? toJson(mask.masked()) : toJson(current);

        long budget = MAX_GROWTH_FACTOR * weight(document, Long.MAX_VALUE) + MAX_GROWTH_BYTES;
        JsonStructure patched = document;
        try {
            // One operation at a time so the size budget is checked after every copy:
            // JSON-P results share structure, so a handful of copies of a growing
            // container builds a document whose serialised size is exponential in the
            // operation count. A patch is atomic either way: any failure discards the
            // partially patched tree.
            for (JsonValue operation : operations) {
                JsonPatch single = Json.createPatch(Json.createArrayBuilder().add(operation).build());
                patched = single.apply(patched);
                if ("copy".equals(operation.asJsonObject().getString("op", null)) && weight(patched, budget) > budget) {
                    throw badRequest("The patch grows the document past what a diff can: copy operations may at most double it.");
                }
            }
        } catch (JsonException | IllegalArgumentException | ClassCastException | UnsupportedOperationException e) {
            throw badRequest("The patch could not be applied: " + e.getMessage());
        }
        if (!(patched instanceof JsonObject)) {
            throw badRequest("The patch must leave a JSON object, as the document is one.");
        }

        String json = patched.toString();
        List<String> shifted = new ArrayList<>();
        if (mask != null) {
            json = mask.restore(json, shifted);
        }
        T result;
        try {
            result = parser.parse(json, type);
        } catch (IOException e) {
            throw badRequest("The patched document is not a valid " + type.getSimpleName() + ": " + e.getMessage());
        }
        if (!shifted.isEmpty()) {
            requireStillRedacted(result, type, shifted);
        }
        return result;
    }

    /**
     * A credential whose token ended up at another path — most often because an
     * {@code add} or {@code remove} earlier in an array shifted every index after
     * it — is kept only where the redacted read still hides it. Anywhere else it
     * would be readable by the very caller it was hidden from, so the patch is
     * refused. Fails closed when the path no longer resolves after parsing.
     */
    private <T> void requireStillRedacted(T result, Class<T> type, List<String> pointers) {
        JsonNode plain = mapper.valueToTree(result);
        JsonNode redacted = mapper.valueToTree(ConfigSecretRedactor.redact(result, type));
        List<String> exposed = new ArrayList<>();
        for (String pointer : pointers) {
            JsonNode value = plain.at(pointer);
            if (value.isMissingNode() || value.equals(redacted.at(pointer))) {
                exposed.add(pointer);
            }
        }
        if (!exposed.isEmpty()) {
            throw badRequest(relocationMessage(exposed));
        }
    }

    private static String relocationMessage(List<String> pointers) {
        return "The patch copies or moves a stored credential (to " + String.join(", ", pointers)
                + "). Credentials are redacted for you, so a patch may leave them in place, replace or remove them, "
                + "but not relocate them into a field you can read.";
    }

    /**
     * The size of {@code value}: one per node plus the length of every string and
     * key, counted only until it passes {@code limit}, so a document whose shared
     * structure would be exponential to walk is rejected after linear work.
     */
    static long weight(JsonValue value, long limit) {
        long[] total = {0};
        accumulate(value, limit, total);
        return total[0];
    }

    private static void accumulate(JsonValue value, long limit, long[] total) {
        if (total[0] > limit) {
            return;
        }
        total[0]++;
        switch (value.getValueType()) {
            case OBJECT -> {
                for (Map.Entry<String, JsonValue> entry : value.asJsonObject().entrySet()) {
                    total[0] += entry.getKey().length();
                    accumulate(entry.getValue(), limit, total);
                    if (total[0] > limit) {
                        return;
                    }
                }
            }
            case ARRAY -> {
                for (JsonValue element : value.asJsonArray()) {
                    accumulate(element, limit, total);
                    if (total[0] > limit) {
                        return;
                    }
                }
            }
            case STRING -> total[0] += ((JsonString) value).getString().length();
            default -> {
                // numbers, booleans and null count as one node
            }
        }
    }

    private JsonArray parseOperations(String patchBody) {
        if (patchBody == null || patchBody.isBlank()) {
            throw badRequest("A JSON Patch body (an array of operations) is required.");
        }
        if (patchBody.getBytes(StandardCharsets.UTF_8).length > MAX_PATCH_BYTES) {
            throw badRequest("The patch is larger than " + MAX_PATCH_BYTES + " bytes. A patch is a diff: send only what changes.");
        }
        try {
            UNAMBIGUOUS.readTree(patchBody);
        } catch (JsonProcessingException e) {
            throw badRequest("The patch body is not unambiguous JSON (a duplicate key, text after the patch, or invalid syntax): "
                    + e.getOriginalMessage());
        }
        try (JsonReader reader = Json.createReader(new StringReader(patchBody))) {
            JsonStructure body = reader.read();
            if (!(body instanceof JsonArray operations)) {
                throw badRequest("A JSON Patch is an array of operations, e.g. [{\"op\":\"add\",\"path\":\"/a\",\"value\":1}].");
            }
            if (operations.size() > MAX_OPERATIONS) {
                throw badRequest("The patch has " + operations.size() + " operations; the limit is " + MAX_OPERATIONS + ".");
            }
            for (JsonValue operation : operations) {
                if (!(operation instanceof JsonObject object) || !object.containsKey("op") || !object.containsKey("path")) {
                    throw badRequest("Every patch operation must be an object with an 'op' and a 'path'.");
                }
            }
            return operations;
        } catch (JsonException e) {
            throw badRequest("The patch body is not valid JSON: " + e.getMessage());
        }
    }

    private JsonStructure toJson(Object document) {
        try (JsonReader reader = Json.createReader(new StringReader(mapper.writeValueAsString(document)))) {
            return reader.read();
        } catch (IOException e) {
            throw new IllegalStateException("The stored document could not be serialised for patching", e);
        }
    }

    /**
     * The stored document with every plaintext credential the caller cannot read
     * swapped for a random token, and what is needed to swap it back.
     * <p>
     * Which leaves are credentials is decided by {@link ConfigSecretRedactor}, the
     * same rules the redacted read applies, by comparing the document with its
     * redacted copy. Tokens carry {@link ConfigSecretRedactor#MARKER_PREFIX}, so
     * one left behind would also be refused by {@code RedactionMarkerGuard}; they
     * are random per request so a patch cannot forge one.
     */
    private record CredentialMask(JsonNode masked, Map<String, Hidden> hidden) {

        private record Hidden(String pointer, JsonNode value) {
        }

        static <T> CredentialMask of(ObjectMapper mapper, T current, Class<T> type) {
            JsonNode original = mapper.valueToTree(current);
            JsonNode redacted = mapper.valueToTree(ConfigSecretRedactor.redact(current, type));
            JsonNode working = original.deepCopy();
            Map<String, Hidden> hidden = new HashMap<>();
            String nonce = UUID.randomUUID().toString();
            mark(original, redacted, working, "", nonce, hidden);
            return new CredentialMask(working, hidden);
        }

        private static void mark(JsonNode original, JsonNode redacted, JsonNode working, String pointer, String nonce,
                                 Map<String, Hidden> hidden) {
            if (original instanceof ObjectNode object && redacted instanceof ObjectNode redactedObject
                    && working instanceof ObjectNode workingObject) {
                for (Map.Entry<String, JsonNode> field : object.properties()) {
                    String name = field.getKey();
                    String childPointer = pointer + "/" + escape(name);
                    JsonNode value = field.getValue();
                    if (value.isTextual() && !value.equals(redactedObject.get(name))) {
                        workingObject.put(name, token(nonce, hidden, childPointer, value));
                    } else {
                        mark(value, redactedObject.get(name), workingObject.get(name), childPointer, nonce, hidden);
                    }
                }
            } else if (original instanceof ArrayNode array && redacted instanceof ArrayNode redactedArray
                    && working instanceof ArrayNode workingArray) {
                for (int i = 0; i < array.size(); i++) {
                    String childPointer = pointer + "/" + i;
                    JsonNode value = array.get(i);
                    if (value.isTextual() && !value.equals(redactedArray.get(i))) {
                        workingArray.set(i, TextNode.valueOf(token(nonce, hidden, childPointer, value)));
                    } else {
                        mark(value, redactedArray.get(i), workingArray.get(i), childPointer, nonce, hidden);
                    }
                }
            }
        }

        private static String token(String nonce, Map<String, Hidden> hidden, String pointer, JsonNode value) {
            String token = ConfigSecretRedactor.MARKER_PREFIX + "credential " + nonce + "#" + hidden.size() + ">";
            hidden.put(token, new Hidden(pointer, value));
            return token;
        }

        private static String escape(String name) {
            return name.replace("~", "~0").replace("/", "~1");
        }

        /**
         * Puts each credential back wherever its token is a whole value. A token found
         * at a path other than the one it came from is reported in {@code shifted}, for
         * the caller to admit only where the result still redacts it; a token embedded
         * in a larger string is refused outright, as no redaction rule matches it
         * there.
         */
        String restore(String patchedJson, List<String> shifted) {
            JsonNode tree;
            try {
                tree = PLAIN.readTree(patchedJson);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("The patched document could not be re-read", e);
            }
            List<String> relocated = new ArrayList<>();
            restore(tree, "", relocated, shifted);
            if (!relocated.isEmpty()) {
                throw badRequest(relocationMessage(relocated));
            }
            try {
                return PLAIN.writeValueAsString(tree);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("The patched document could not be serialised", e);
            }
        }

        private void restore(JsonNode node, String pointer, List<String> relocated, List<String> shifted) {
            if (node instanceof ObjectNode object) {
                for (Map.Entry<String, JsonNode> field : object.properties()) {
                    String childPointer = pointer + "/" + escape(field.getKey());
                    JsonNode replacement = restoredLeaf(field.getValue(), childPointer, relocated, shifted);
                    if (replacement != null) {
                        field.setValue(replacement);
                    } else {
                        restore(field.getValue(), childPointer, relocated, shifted);
                    }
                }
            } else if (node instanceof ArrayNode array) {
                for (int i = 0; i < array.size(); i++) {
                    String childPointer = pointer + "/" + i;
                    JsonNode replacement = restoredLeaf(array.get(i), childPointer, relocated, shifted);
                    if (replacement != null) {
                        array.set(i, replacement);
                    } else {
                        restore(array.get(i), childPointer, relocated, shifted);
                    }
                }
            }
        }

        private JsonNode restoredLeaf(JsonNode value, String pointer, List<String> relocated, List<String> shifted) {
            if (!value.isTextual()) {
                return null;
            }
            String text = value.asText();
            Hidden original = hidden.get(text);
            if (original != null) {
                if (!original.pointer().equals(pointer)) {
                    relocated.add(pointer);
                    return null;
                }
                return original.value();
            }
            for (String token : hidden.keySet()) {
                if (text.contains(token)) {
                    relocated.add(pointer.isEmpty() ? "/" : pointer);
                    break;
                }
            }
            return null;
        }
    }

    /**
     * Re-reads the patched tree for {@link CredentialMask#restore}; plain JSON, no
     * model binding.
     */
    private static final ObjectMapper PLAIN = new ObjectMapper();

    private static BadRequestException badRequest(String message) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST).entity(message).type(MediaType.TEXT_PLAIN_TYPE).build());
    }
}
