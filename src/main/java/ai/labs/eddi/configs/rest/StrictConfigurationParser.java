/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Parses a first-party configuration document, rejecting any field the model
 * does not declare.
 * <p>
 * EDDI is a config-driven engine: a typo'd key in {@code behavior.json} or
 * {@code langchain.json} is a behavioural bug, and silently discarding it is
 * the worst possible failure mode — the author is told the save succeeded, a
 * subsequent read no longer shows the key, and the agent misbehaves with no
 * signal anywhere.
 * <p>
 * This lives apart from {@link StrictConfigurationBodyInterceptor} because the
 * check belongs to <em>writing a configuration</em>, not to reading an HTTP
 * body. A {@code ReaderInterceptor} only fires on a real inbound request, so
 * MCP's {@code create_resource} — which calls the same REST store in-process
 * with its own lenient parse — bypassed it entirely. The same payload therefore
 * had opposite outcomes on the two surfaces: REST answered
 * {@code 400 Unknown field 'setProperties' … Known fields: [setOnActions]},
 * while MCP answered {@code 201 created} and stored {@code {"setOnActions":[]}}
 * — an empty configuration that then read back happily and did nothing. That
 * path is reachable by every MCP client, including the Platform Operator agent,
 * which creates resources this way.
 *
 * @see StrictConfigurationBodyInterceptor
 */
@ApplicationScoped
public class StrictConfigurationParser {

    private final ObjectMapper strictMapper;

    @Inject
    public StrictConfigurationParser(ObjectMapper restMapper) {
        // copy() so the injected mapper — shared with the REST client and with every
        // service that parses third-party JSON — keeps its lenient behaviour. Flipping
        // FAIL_ON_UNKNOWN_PROPERTIES on a shared mapper would also make MongoDB reads
        // and ZIP imports strict, which must stay schema-evolution-tolerant.
        this.strictMapper = restMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Deserialises a configuration document strictly.
     *
     * @param json
     *            the configuration body
     * @param type
     *            the configuration model to read it into
     * @return the parsed configuration
     * @throws BadRequestException
     *             if the body names a field the model does not declare (the message
     *             names the field and lists the legal ones), or puts a value of the
     *             wrong shape in a field that exists
     * @throws IOException
     *             for syntactically malformed JSON, so callers keep whatever error
     *             they already produced for it
     */
    public <T> T parse(String json, Class<T> type) throws IOException {
        try {
            return strictMapper.readValue(json, type);
        } catch (UnrecognizedPropertyException e) {
            throw badRequest(describe(e, type));
        } catch (MismatchedInputException e) {
            throw badRequest(describeMismatch(e, type, json));
        }
    }

    /**
     * Checks a body without taking its parse result. Used where the caller already
     * has a deserialised object and only wants the rejection.
     *
     * @throws BadRequestException
     *             if the body names an undeclared field, or puts a value of the
     *             wrong shape in a declared one
     */
    public void rejectUnknownFields(byte[] body, Class<?> type) {
        try {
            strictMapper.readValue(body, type);
        } catch (UnrecognizedPropertyException e) {
            throw badRequest(describe(e, type));
        } catch (MismatchedInputException e) {
            throw badRequest(describeMismatch(e, type, body));
        } catch (IOException e) {
            // Syntactically malformed JSON — not this class's business. Proceeding
            // lets the regular reader produce the response it always has.
        }
    }

    private static BadRequestException badRequest(String message) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                .entity(message).type(MediaType.TEXT_PLAIN_TYPE).build());
    }

    /**
     * Explains a value whose <em>shape</em> is wrong, as opposed to a field whose
     * name is.
     * <p>
     * Jackson's own message is unusable as an API response — it names Java classes
     * and generic signatures — but the response it replaces was worse: RESTEasy
     * answers a databind failure with a bare {@code 400} and
     * {@code content-length: 0}. A user following the quickstart posted
     * {@code "valueAlternatives": ["Hello!"]} where the model wants
     * {@code [{"type":"text","text":"Hello!"}]} and got no hint at all — not which
     * field, not what was expected, not even that the body was the problem. So this
     * says which JSON path failed and which kind of value belongs there, and leaves
     * the Java type names out.
     */
    private String describeMismatch(MismatchedInputException e, Class<?> type, Object rawBody) {
        var message = new StringBuilder("Cannot read ").append(type.getSimpleName());

        String path = jsonPath(e);
        if (!path.isEmpty()) {
            message.append(" at ").append(path);
        }

        if (e instanceof InvalidTypeIdException typeId) {
            // A polymorphic value whose discriminator is missing or unknown —
            // {"type":"txt"} on an output item, say. "expected a JSON object here,
            // found an object" would be true and useless; the legal ids are the
            // only thing that helps.
            message.append(": ").append(describeTypeId(typeId, rawBody));
        } else {
            message.append(": ").append(expected(e.getTargetType())).append(found(e, rawBody));
        }

        return message.append(" Check this field against the resource's JSON Schema at "
                + "GET /<store>/<resource>/jsonSchema.").toString();
    }

    /**
     * The legal values of a polymorphic model's {@code type} discriminator.
     * <p>
     * Read off the Jackson type resolver rather than hard-coded, so a new
     * {@code @JsonSubTypes} entry cannot leave this list stale.
     */
    private String describeTypeId(InvalidTypeIdException e, Object rawBody) {
        var known = new TreeSet<String>();
        try {
            var resolver = strictMapper.getSubtypeResolver();
            var config = strictMapper.getDeserializationConfig();
            for (var subtype : resolver.collectAndResolveSubtypesByTypeId(config,
                    config.introspectClassAnnotations(e.getBaseType()).getClassInfo())) {
                if (subtype.getName() != null) {
                    known.add(subtype.getName());
                }
            }
        } catch (RuntimeException ignored) {
            // Decoration only — fall through to the id-less sentence below.
        }

        String id = e.getTypeId();
        var message = new StringBuilder();
        if (id == null || id.isBlank()) {
            // Usually a bare scalar where a typed object belongs, so name what was
            // there — "this object needs a 'type'" is simply untrue of a string.
            message.append("expected an object with a 'type' field").append(found(e, rawBody));
        } else {
            message.append("'").append(id).append("' is not a known 'type'.");
        }
        if (!known.isEmpty()) {
            message.append(" Known types: ").append(known).append(".");
        }
        return message.toString();
    }

    /**
     * The failing position as a JSON path — {@code outputSet[0].outputs[0]} — built
     * from Jackson's reference chain.
     * <p>
     * Not {@code getPathReference()}, which renders the same chain as
     * {@code ai.labs.eddi.configs.output.model.OutputConfigurationSet["outputSet"]
     * ->java.util.ArrayList[0]->…}. That is a debugging aid for whoever wrote the
     * Java, and this string goes to whoever wrote the JSON: it names classes they
     * cannot see, in a syntax that does not match the document they are holding,
     * and it publishes the internal package layout to every API client.
     */
    private static String jsonPath(JsonMappingException e) {
        return jsonPath(e, e.getPath().size());
    }

    /**
     * @param depth
     *            how many leading references to render — the full chain for a
     *            wrong-shaped value, one short of it for an unknown field, whose
     *            last reference IS the unknown field.
     */
    private static String jsonPath(JsonMappingException e, int depth) {
        var path = new StringBuilder();
        for (JsonMappingException.Reference reference : e.getPath().subList(0, Math.max(0, depth))) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String expected(Class<?> target) {
        if (target == null) {
            // Jackson reports no target type for a polymorphic property whose value is
            // a scalar — which is exactly the OutputItem case, the most likely one to
            // reach here. "An object" is right for every polymorphic model in configs.
            return "expected a JSON object here";
        }
        if (Collection.class.isAssignableFrom(target) || target.isArray()) {
            return "expected a JSON array here";
        }
        if (Map.class.isAssignableFrom(target)) {
            return "expected a JSON object here";
        }
        if (target.isEnum()) {
            return "expected one of " + Arrays.stream(target.getEnumConstants()).map(String::valueOf).sorted()
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        if (target == String.class) {
            return "expected a string here";
        }
        if (target == Boolean.class || target == boolean.class) {
            return "expected true or false here";
        }
        if (target == Integer.class || target == int.class || target == Long.class || target == long.class
                || target == Short.class || target == short.class) {
            return "expected a whole number here";
        }
        if (Number.class.isAssignableFrom(target) || target.isPrimitive()) {
            // "expected a int here" was the alternative. The Java type name adds
            // nothing a JSON author can act on, and reads as a bug in the message.
            return "expected a number here";
        }
        // Everything else is a nested configuration object.
        return "expected a JSON object here";
    }

    /**
     * What was there instead, resolved by re-reading the body at the failing
     * position.
     * <p>
     * Naming only the expectation leaves the reader to work out which of several
     * candidate values on the line was wrong; naming what was found identifies it
     * immediately, and does so without Jackson's own message, which spells the
     * answer as "Cannot construct instance of `ai.labs.eddi...OutputItem` (although
     * at least one Creator exists)".
     * <p>
     * Not read off {@code e.getProcessor()}: {@code readValue} closes the parser in
     * a finally block before the exception propagates, so its current token is
     * always {@code null} by the time this is asked. The body is still in hand and
     * is known to be syntactically valid JSON — a syntax error would have been an
     * {@link IOException} rather than a {@link MismatchedInputException} — so a
     * second, tree-shaped read of it is cheap and cannot introduce a new failure
     * mode. Anything unexpected simply omits the clause.
     */
    private String found(MismatchedInputException e, Object rawBody) {
        try {
            JsonNode root = rawBody instanceof byte[] bytes
                    ? strictMapper.readTree(bytes)
                    : strictMapper.readTree(String.valueOf(rawBody));
            JsonNode node = root.at(jsonPointer(e));

            if (node.isMissingNode()) {
                return ".";
            }
            if (node.isTextual()) {
                return ", found a string.";
            }
            if (node.isNumber()) {
                return ", found a number.";
            }
            if (node.isBoolean()) {
                return ", found a boolean.";
            }
            if (node.isNull()) {
                return ", found null.";
            }
            if (node.isArray()) {
                return ", found an array.";
            }
            if (node.isObject()) {
                return ", found an object.";
            }
            return ".";
        } catch (IOException | RuntimeException ignored) {
            return ".";
        }
    }

    /** The failing position as a JSON Pointer, for looking the value back up. */
    private static String jsonPointer(JsonMappingException e) {
        var pointer = new StringBuilder();
        for (JsonMappingException.Reference reference : e.getPath()) {
            if (reference.getFieldName() != null) {
                pointer.append('/').append(reference.getFieldName().replace("~", "~0").replace("/", "~1"));
            } else if (reference.getIndex() >= 0) {
                pointer.append('/').append(reference.getIndex());
            }
        }
        return pointer.toString();
    }

    private static String describe(UnrecognizedPropertyException e, Class<?> type) {
        var message = new StringBuilder("Unknown field '").append(e.getPropertyName()).append("' in ").append(type.getSimpleName());

        // The CONTAINER of the offending field, not the field itself: the message
        // has already named it, so "Unknown field 'x' … at x" says it twice, which
        // is what a root-level typo produced.
        String path = jsonPath(e, e.getPath().size() - 1);
        if (!path.isEmpty()) {
            message.append(" in ").append(path);
        }

        Collection<Object> known = e.getKnownPropertyIds();
        if (known != null && !known.isEmpty()) {
            message.append(". Known fields: ")
                    .append(known.stream().map(String::valueOf).sorted().collect(Collectors.joining(", ", "[", "]")));
        }

        return message.append(". The field was rejected instead of being silently discarded — "
                + "a dropped key looks like a successful save but changes nothing.").toString();
    }
}
