/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.patch;

import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonReader;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

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

    private final ObjectMapper mapper;
    private final StrictConfigurationParser parser;

    @Inject
    public ConfigJsonPatcher(ObjectMapper mapper, StrictConfigurationParser parser) {
        this.mapper = mapper;
        this.parser = parser;
    }

    /**
     * @param current
     *            the stored document the patch is applied to
     * @param patchBody
     *            the RFC 6902 patch, as JSON text
     * @param type
     *            the configuration model
     * @return the patched document, parsed strictly
     * @throws BadRequestException
     *             when the body is not a patch, an operation is unsupported or
     *             fails (a {@code test} that does not hold, a path that does not
     *             resolve), or the patched document is not a valid {@code type}
     */
    public <T> T apply(T current, String patchBody, Class<T> type) {
        JsonArray operations = parseOperations(patchBody);
        JsonStructure document = toJson(current);

        JsonValue patched;
        try {
            JsonPatch patch = Json.createPatch(operations);
            patched = patch.apply(document);
        } catch (JsonException | IllegalArgumentException | ClassCastException | UnsupportedOperationException e) {
            throw badRequest("The patch could not be applied: " + e.getMessage());
        }
        if (!(patched instanceof JsonObject)) {
            throw badRequest("The patch must leave a JSON object, as the document is one.");
        }

        try {
            return parser.parse(patched.toString(), type);
        } catch (IOException e) {
            throw badRequest("The patched document is not a valid " + type.getSimpleName() + ": " + e.getMessage());
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

    private static BadRequestException badRequest(String message) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST).entity(message).type(MediaType.TEXT_PLAIN_TYPE).build());
    }
}
