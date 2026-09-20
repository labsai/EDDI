/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.github.tjake.jlama.safetensors.DType;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.jlama.JlamaChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyBoolean;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyDouble;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyInt;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyPath;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Jlama runs the model <em>in this JVM</em> — there is no endpoint, so no
 * {@code baseUrl}. What it does need is somewhere to put the weights: the model
 * is resolved through Jlama's own registry, which downloads it from Hugging
 * Face on first use and caches it on disk.
 * <p>
 * Two consequences shape the parameters below.
 * <ul>
 * <li>{@code modelName} must be a Hugging Face repository id in
 * {@code owner/name} form (e.g. {@code tjake/Llama-3.2-1B-Instruct-JQ4}). A
 * bare name has no owner to look up and the download fails on the first turn,
 * long after the configuration was saved.</li>
 * <li>{@code modelCachePath} defaults to {@code ~/.jlama/models}, which in a
 * container is the ephemeral writable layer. Left unset, every pod restart
 * re-downloads multiple gigabytes. Point it at a mounted volume.</li>
 * </ul>
 * <p>
 * {@code DType} comes from {@code jlama-core}, which reaches the classpath
 * through {@code langchain4j-jlama} rather than being declared in
 * {@code pom.xml} — deliberately, so the two cannot be pinned to versions that
 * disagree.
 */
@ApplicationScoped
public class JlamaLanguageModelBuilder implements ILanguageModelBuilder {
    private static final Logger LOGGER = Logger.getLogger(JlamaLanguageModelBuilder.class);

    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_AUTH_TOKEN = "authToken";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_MODEL_CACHE_PATH = "modelCachePath";
    private static final String KEY_THREAD_COUNT = "threadCount";
    private static final String KEY_QUANTIZE_AT_RUNTIME = "quantizeModelAtRuntime";
    private static final String KEY_WORKING_DIRECTORY = "workingDirectory";
    private static final String KEY_WORKING_QUANTIZED_TYPE = "workingQuantizedType";

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_MODEL_NAME, KEY_AUTH_TOKEN, KEY_TEMPERATURE, KEY_MAX_TOKENS, KEY_MODEL_CACHE_PATH,
                KEY_THREAD_COUNT, KEY_QUANTIZE_AT_RUNTIME, KEY_WORKING_DIRECTORY, KEY_WORKING_QUANTIZED_TYPE);
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = JlamaChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }

        if (!isNullOrEmpty(parameters.get(KEY_AUTH_TOKEN))) {
            builder.authToken(parameters.get(KEY_AUTH_TOKEN));
        }

        // Parsed as a double and narrowed: this setter takes a float, and a separate
        // float helper would buy nothing — every value a float accepts, a double
        // accepts too.
        applyDouble(parameters, KEY_TEMPERATURE, temperature -> builder.temperature((float) temperature));

        applyInt(parameters, KEY_MAX_TOKENS, builder::maxTokens);

        applyPath(parameters, KEY_MODEL_CACHE_PATH, builder::modelCachePath);
        applyPath(parameters, KEY_WORKING_DIRECTORY, builder::workingDirectory);
        applyInt(parameters, KEY_THREAD_COUNT, builder::threadCount);
        applyBoolean(parameters, KEY_QUANTIZE_AT_RUNTIME, builder::quantizeModelAtRuntime);

        DType workingQuantizedType = workingQuantizedType(parameters);
        if (workingQuantizedType != null) {
            builder.workingQuantizedType(workingQuantizedType);
        }

        return builder.build();
    }

    /**
     * The configured working quantization type, or {@code null} when absent or
     * unusable — leaving Jlama's own default in place.
     * <p>
     * Lenient in the same way the numeric reads are: this value is typed by hand in
     * the Manager, and an unusable one is a configuration mistake, not a reason to
     * fail every conversation the agent serves. Case is ignored because {@code q4}
     * is what a user writes and {@code Q4} is what the enum calls it.
     */
    static DType workingQuantizedType(Map<String, String> parameters) {
        String raw = parameters == null ? null : parameters.get(KEY_WORKING_QUANTIZED_TYPE);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        for (DType candidate : DType.values()) {
            if (candidate.name().equals(normalised)) {
                return candidate;
            }
        }
        LOGGER.warnf("LLM parameter '%s' is not a known Jlama quantization type ('%s') — "
                + "leaving the model default in place. Known types: %s.",
                KEY_WORKING_QUANTIZED_TYPE, sanitize(raw), Arrays.toString(DType.values()));
        return null;
    }
}
