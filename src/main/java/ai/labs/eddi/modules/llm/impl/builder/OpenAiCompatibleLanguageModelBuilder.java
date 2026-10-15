/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.booleanOrNull;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.doubleValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.intValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.jsonValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringList;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringValue;

/**
 * Builds a chat model for one named OpenAI-compatible provider (xAI, DeepSeek,
 * Moonshot, ...) by applying the provider's preset to the generic
 * {@link OpenAILanguageModelBuilder}.
 * <p>
 * Not a CDI bean: {@code LlmModule} creates one instance per catalog entry.
 * <p>
 * Precedence: an explicit {@code baseUrl} wins over {@code region}, which wins
 * over the preset's default endpoint; an explicit {@code modelName} wins over
 * the preset's default; explicit parameters win over the preset's
 * {@code parameterDefaults}.
 * <p>
 * <b>Vendor fields.</b> Settings the OpenAI protocol has no field for
 * ({@code enable_thinking} on Qwen, {@code provider.order} on OpenRouter, ...)
 * are {@link ModelParameterCatalog} entries marked {@code body}; each one
 * present is coerced to its catalog type and written at its dotted {@code wire}
 * path into the request body, on top of the preset's {@code customParameters},
 * and the user's own {@code customParameters} still win. Entries with a
 * {@code header} become HTTP headers, under the user's {@code customHeaders}.
 */
public class OpenAiCompatibleLanguageModelBuilder implements ILanguageModelBuilder {
    static final String KEY_REGION = "region";
    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_CUSTOM_HEADERS = OpenAILanguageModelBuilder.KEY_CUSTOM_HEADERS;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenAiCompatibleProvider provider;
    private final OpenAILanguageModelBuilder delegate;
    private final ModelParameterCatalog catalog;

    public OpenAiCompatibleLanguageModelBuilder(OpenAiCompatibleProvider provider, OpenAILanguageModelBuilder delegate) {
        this(provider, delegate, ModelParameterCatalog.defaultCatalog());
    }

    OpenAiCompatibleLanguageModelBuilder(OpenAiCompatibleProvider provider, OpenAILanguageModelBuilder delegate,
            ModelParameterCatalog catalog) {
        this.provider = provider;
        this.delegate = delegate;
        this.catalog = catalog;
    }

    @Override
    public Set<String> recognisedParameters() {
        Set<String> recognised = new HashSet<>(delegate.recognisedParameters());
        recognised.add(KEY_REGION);
        recognised.addAll(catalog.keys(provider.id()));
        return recognised;
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        Prepared prepared = prepare(parameters);
        return delegate.build(prepared.parameters(), prepared.body());
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        Prepared prepared = prepare(parameters);
        return delegate.buildStreaming(prepared.parameters(), prepared.body());
    }

    /** What the delegate receives: the parameters it reads, and the extra body. */
    record Prepared(Map<String, String> parameters, Map<String, Object> body) {
    }

    Prepared prepare(Map<String, String> parameters) {
        Map<String, String> merged = merged(parameters);
        Map<String, Object> body = deepCopy(provider.customParameters());
        Map<String, String> headers = new LinkedHashMap<>();

        for (ModelParameterCatalog.Param param : catalog.params(provider.id())) {
            if (param.body()) {
                Object value = coerce(param, merged);
                // Removed either way: the delegate must not also send a body key
                // under its own name (OpenRouter's reasoningEffort goes to
                // reasoning.effort, not to the top-level reasoning_effort).
                merged.remove(param.key());
                if (value != null) {
                    putPath(body, param.wire(), value);
                }
            } else if (param.header() != null) {
                String value = stringValue(merged, param.key());
                merged.remove(param.key());
                if (value != null) {
                    headers.put(param.header(), value);
                }
            }
        }

        if (!headers.isEmpty()) {
            Map<String, String> userHeaders = stringMap(merged, KEY_CUSTOM_HEADERS);
            if (userHeaders != null) {
                headers.putAll(userHeaders);
            }
            try {
                merged.put(KEY_CUSTOM_HEADERS, JSON.writeValueAsString(headers));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Cannot serialise custom headers for " + provider.displayName(), e);
            }
        }
        return new Prepared(merged, body);
    }

    private static Object coerce(ModelParameterCatalog.Param param, Map<String, String> p) {
        String key = param.key();
        return switch (param.type()) {
            case "double" -> doubleValue(p, key);
            case "int" -> intValue(p, key);
            case "boolean" -> booleanOrNull(p, key);
            case "stringList" -> stringList(p, key);
            case "json" -> jsonValue(p, key);
            default -> stringValue(p, key);
        };
    }

    /**
     * Write {@code value} at a dotted path, creating (or copying, so nothing shared
     * is mutated) the nested objects on the way; a non-object in the way is
     * replaced.
     */
    @SuppressWarnings("unchecked")
    static void putPath(Map<String, Object> root, String dottedPath, Object value) {
        String[] segments = dottedPath.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            Object next = current.get(segments[i]);
            Map<String, Object> child = next instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map)
                    : new LinkedHashMap<>();
            current.put(segments[i], child);
            current = child;
        }
        current.put(segments[segments.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((k, v) -> copy.put(k, v instanceof Map<?, ?> map
                ? deepCopy((Map<String, Object>) map)
                : v instanceof List<?> list ? new ArrayList<>(list) : v));
        return copy;
    }

    private Map<String, String> merged(Map<String, String> parameters) {
        Map<String, String> merged = new HashMap<>(parameters == null ? Map.of() : parameters);
        // A present-but-blank value counts as absent: the Manager and templated
        // configs submit "" for an untouched field, and that must not switch a
        // preset default (sendThinking, ...) off.
        provider.parameterDefaults().forEach((k, def) -> merged.merge(k, def, (old, d) -> old == null || old.isBlank() ? d : old));

        if (isBlank(merged.get(KEY_BASE_URL))) {
            merged.put(KEY_BASE_URL, provider.resolveBaseUrl(merged.get(KEY_REGION)));
        }
        merged.remove(KEY_REGION);

        if (isBlank(merged.get(KEY_MODEL_NAME))) {
            merged.put(KEY_MODEL_NAME, provider.defaultModel());
        }
        if (isBlank(merged.get(KEY_API_KEY))) {
            throw new IllegalArgumentException(provider.displayName() + " requires an apiKey, e.g. ${vault:" + provider.id() + "-key}");
        }
        return merged;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
