/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.Collection;
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
     *             if the body names a field the model does not declare; the message
     *             names the field and lists the legal ones
     * @throws IOException
     *             for every other parse failure, so callers keep whatever error
     *             they already produced for malformed JSON
     */
    public <T> T parse(String json, Class<T> type) throws IOException {
        try {
            return strictMapper.readValue(json, type);
        } catch (UnrecognizedPropertyException e) {
            throw badRequest(describe(e, type));
        }
    }

    /**
     * Checks a body for unknown fields without taking its parse result. Used where
     * the caller already has a deserialised object and only wants the rejection.
     *
     * @throws BadRequestException
     *             if the body names an undeclared field
     */
    public void rejectUnknownFields(byte[] body, Class<?> type) {
        try {
            strictMapper.readValue(body, type);
        } catch (UnrecognizedPropertyException e) {
            throw badRequest(describe(e, type));
        } catch (IOException e) {
            // Malformed JSON, wrong value type, etc. — not this class's business.
            // Proceeding lets the regular reader produce the response it always has.
        }
    }

    private static BadRequestException badRequest(String message) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                .entity(message).type(MediaType.TEXT_PLAIN_TYPE).build());
    }

    private static String describe(UnrecognizedPropertyException e, Class<?> type) {
        var message = new StringBuilder("Unknown field '").append(e.getPropertyName()).append("' in ").append(type.getSimpleName());

        String path = e.getPathReference();
        if (path != null && !path.isBlank()) {
            message.append(" at ").append(path);
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
