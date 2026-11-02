/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ForEachInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.PathNavigator;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * The parts of an httpcall's {@code forEach} that do not send anything: which
 * elements run, how an item's failure is described, and the total size budget.
 */
final class ForEachItems {

    private static final Logger LOGGER = Logger.getLogger(ForEachItems.class);

    /** What {@link #iterationName} falls back to. */
    static final String DEFAULT_ITERATION_NAME = "item";

    private ForEachItems() {
    }

    /**
     * One element that will run.
     *
     * @param index
     *            its position in the source array
     * @param value
     *            the element itself — bound as template DATA, never rendered into a
     *            template's source
     */
    record Element(int index, Object value) {
    }

    /** The name the element is bound to. */
    static String iterationName(ForEachInstruction forEach) {
        String name = forEach.getIterationObjectName();
        return name != null && !name.isBlank() ? name.trim() : DEFAULT_ITERATION_NAME;
    }

    /**
     * The elements of {@code pathToTargetArray} that pass the filter, in order.
     * <p>
     * The path is navigated, not rendered: the element objects themselves are
     * needed (so {@code {item.id}} works), which a rendered template cannot give
     * back. The filter is the author's Qute condition, rendered once per element
     * into {@code {#if <condition>}true{/if}}: the template text is the same for
     * every element (one cached compilation), and the element only ever arrives as
     * data.
     *
     * @throws IllegalArgumentException
     *             when the path is missing or resolves to something that is not an
     *             array
     */
    static List<Element> select(ForEachInstruction forEach, Map<String, Object> templateData, PrePostUtils prePostUtils)
            throws ITemplatingEngine.TemplateEngineException {
        String path = forEach.getPathToTargetArray();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("forEach.pathToTargetArray is required: the dot path to the array to run the call for, "
                    + "e.g. 'properties.orders'.");
        }
        Object target = PathNavigator.getValue(path.trim(), templateData);
        List<?> values;
        if (target == null) {
            values = List.of();
        } else if (target instanceof List<?> list) {
            values = list;
        } else if (target instanceof Object[] array) {
            values = List.of(array);
        } else if (target instanceof Collection<?> collection) {
            values = new ArrayList<>(collection);
        } else {
            throw new IllegalArgumentException("forEach.pathToTargetArray '" + LogSanitizer.sanitize(path) + "' resolves to a "
                    + target.getClass().getSimpleName() + ", not an array. Point it at a list, e.g. 'properties.orders'.");
        }

        String filter = forEach.getTemplateFilterExpression();
        boolean filtered = filter != null && !filter.isBlank();
        String filterTemplate = filtered ? "{#if " + filter.trim() + "}true{/if}" : null;
        String name = iterationName(forEach);
        List<Element> elements = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (filtered) {
                Map<String, Object> data = new LinkedHashMap<>(templateData);
                data.put(name, value);
                String rendered = prePostUtils.templateValues(filterTemplate, data);
                if (rendered == null || !"true".equals(rendered.trim())) {
                    continue;
                }
            }
            elements.add(new Element(i, value));
        }
        return elements;
    }

    /**
     * Whether a failure is a timeout, anywhere in its cause chain — the client
     * wraps the transport's own exception, whose type varies with the engine.
     */
    static boolean isTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TimeoutException || t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
            if (t.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A failure as an item's error text: its type and message, with every plaintext
     * the request substituted removed, credential-shaped text redacted, line breaks
     * neutralised and the length capped.
     */
    static String describe(Throwable failure, Set<String> secrets, int maxChars) {
        String message = failure.getMessage() != null ? failure.getMessage() : "";
        message = RequestRedactor.redactResolvedSecrets(message, secrets);
        message = SecretRedactionFilter.redact(message);
        String text = failure.getClass().getSimpleName() + (message.isBlank() ? "" : ": " + message);
        text = LogSanitizer.sanitize(text);
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    /**
     * Keeps the items' bodies, in order, until {@code budget} UTF-8 bytes are
     * spent; every later body is dropped (its status stays) and the item says so.
     * Conversation memory is loaded on every turn, so the array as a whole gets the
     * budget one response would.
     */
    static List<ForEachItemResult> capTotalSize(List<ForEachItemResult> results, int budget, IJsonSerialization jsonSerialization,
                                                String callName) {
        long used = 0;
        int dropped = 0;
        List<ForEachItemResult> capped = new ArrayList<>(results.size());
        for (ForEachItemResult result : results) {
            if (result.body() == null) {
                capped.add(result);
                continue;
            }
            long size = sizeOf(result.body(), jsonSerialization);
            if (used + size > budget) {
                dropped++;
                capped.add(result.withoutBody("body not stored: the forEach results exceeded maxTotalResponseSizeInBytes (" + budget + ")"));
                continue;
            }
            used += size;
            capped.add(result);
        }
        if (dropped > 0) {
            LOGGER.warnf("forEach of http call '%s': %d item bodies not stored — together they exceed %d bytes. Raise "
                    + "forEach.maxTotalResponseSizeInBytes or narrow the loop.", LogSanitizer.sanitize(callName), dropped, budget);
        }
        return capped;
    }

    /**
     * The stored size of a body, in UTF-8 bytes of its JSON or text form — the unit
     * {@code maxTotalResponseSizeInBytes} is named in; a character count would let
     * a non-ASCII body past a budget it exceeds.
     */
    static long sizeOf(Object body, IJsonSerialization jsonSerialization) {
        if (body instanceof String text) {
            return utf8Size(text);
        }
        try {
            String serialized = jsonSerialization != null ? jsonSerialization.serialize(body) : null;
            return utf8Size(serialized != null ? serialized : String.valueOf(body));
        } catch (IOException e) {
            return utf8Size(String.valueOf(body));
        }
    }

    private static long utf8Size(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
