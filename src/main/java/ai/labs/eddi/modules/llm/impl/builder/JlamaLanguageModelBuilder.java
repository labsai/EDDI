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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyBoolean;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyDouble;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyInt;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Builds Jlama's in-process chat model — the only provider that runs inference
 * inside the EDDI JVM rather than over a network.
 * <p>
 * Two properties of {@link JlamaChatModel} shape everything here, and both live
 * in its <em>constructor</em> rather than in {@code chat(...)}: it downloads
 * the model weights from Hugging Face, and it loads them into memory. So
 * {@link #build} is expensive, does network I/O, and needs somewhere to put
 * several gigabytes. {@code modelCachePath} is how an operator answers the last
 * part; without it Jlama writes to {@code ${user.home}/.jlama/models}, which in
 * a container is the pod's ephemeral writable layer. It works, which is what
 * makes it a trap: the weights survive exactly as long as the pod does, so
 * every restart re-downloads multiple gigabytes from Hugging Face before the
 * first turn can be answered, and an air-gapped deployment cannot start at all.
 * Point it at a mounted volume.
 * <p>
 * Inference speed depends on a JVM flag that cannot be set from here; see
 * {@link JlamaRuntimeSupport}.
 */
@ApplicationScoped
public class JlamaLanguageModelBuilder implements ILanguageModelBuilder {
    private static final Logger LOGGER = Logger.getLogger(JlamaLanguageModelBuilder.class);

    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_AUTH_TOKEN = "authToken";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_MODEL_CACHE_PATH = "modelCachePath";
    private static final String KEY_QUANTIZE_AT_RUNTIME = "quantizeModelAtRuntime";
    private static final String KEY_WORKING_DIRECTORY = "workingDirectory";
    private static final String KEY_WORKING_QUANTIZED_TYPE = "workingQuantizedType";

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_MODEL_NAME, KEY_AUTH_TOKEN, KEY_TEMPERATURE, KEY_MAX_TOKENS, KEY_MODEL_CACHE_PATH,
                KEY_QUANTIZE_AT_RUNTIME, KEY_WORKING_DIRECTORY, KEY_WORKING_QUANTIZED_TYPE);
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        JlamaRuntimeSupport.warnOnceIfDegraded();
        return applyTo(JlamaChatModel.builder(), parameters).build();
    }

    /**
     * Applies the configured parameters to a Jlama builder and hands it back
     * <em>without</em> building it.
     * <p>
     * Split out because {@code JlamaChatModel.builder().build()} downloads weights
     * and loads a model: a unit test that called {@link #build} would need network
     * access and gigabytes of disk, so the parameter mapping — the part that can
     * actually regress — would go untested, which is how it stayed at four of the
     * nine settings Jlama accepts (one of which, threadCount, turns out to be
     * unsafe to expose at all — see the note in the body). Constructing and
     * populating the builder is free, and its {@code toString()} exposes every
     * field, so {@code LanguageModelBuildersTest} asserts on the mapping directly.
     *
     * @param builder
     *            a fresh Jlama builder
     * @param parameters
     *            the agent's configured parameter map; may be empty
     *
     * @return the same builder, for chaining
     */
    static JlamaChatModel.JlamaChatModelBuilder applyTo(JlamaChatModel.JlamaChatModelBuilder builder,
                                                        Map<String, String> parameters) {

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

        // Where the weights are cached. The most important setting for a containerised
        // deployment, and the one with the least forgiving default.
        if (!isNullOrEmpty(parameters.get(KEY_MODEL_CACHE_PATH))) {
            builder.modelCachePath(Path.of(parameters.get(KEY_MODEL_CACHE_PATH)));
        }

        // NOTE: threadCount is deliberately NOT mapped, even though Jlama's builder
        // accepts it. It is not a per-model setting: JlamaModel.Loader hands it to
        // ModelSupport.loadModel, which calls the process-global
        // PhysicalCoreExecutor.overrideThreadCount. That method is one-shot —
        // if (!started.compareAndSet(false, true)) throw new IllegalStateException(...)
        // — and the executor's memoized `instance` supplier ALSO sets `started`, so
        // merely running inference once arms the latch. Exposing it per model would
        // mean the second Jlama model built in a process throws "Executor already
        // started" during load, even with an identical value; and since this registry
        // rebuilds models on cache eviction, secret rotation and a 30-minute idle TTL,
        // that second build is routine rather than exotic. Jlama offers no model-local
        // thread configuration, so there is nothing correct to expose here. Operators
        // size it by giving the pod a CPU *limit*: availableProcessors() honours a
        // limit, and Jlama's default is max(2, availableProcessors() / 2).

        // applyBoolean, not Boolean.parseBoolean: the latter maps every typo to false
        // without a word, so "ture" would silently pin quantization off. See
        // ModelParameterValues#applyBoolean.
        applyBoolean(parameters, KEY_QUANTIZE_AT_RUNTIME, builder::quantizeModelAtRuntime);

        if (!isNullOrEmpty(parameters.get(KEY_WORKING_DIRECTORY))) {
            builder.workingDirectory(Path.of(parameters.get(KEY_WORKING_DIRECTORY)));
        }

        applyWorkingQuantizedType(builder, parameters);

        return builder;
    }

    /**
     * Maps {@code workingQuantizedType} onto Jlama's {@code DType} enum.
     * <p>
     * Kept out of {@link #applyTo}'s main flow because it is the one setting whose
     * value space is a third-party enum: an unrecognised name has to be reported
     * rather than passed through, and it has to be reported the same way
     * {@code ModelParameterValues} reports a bad number — log the key, keep the
     * provider default, never fail the turn. A typo here would otherwise be an
     * {@link IllegalArgumentException} out of {@code valueOf} on every conversation
     * the agent serves.
     */
    private static void applyWorkingQuantizedType(JlamaChatModel.JlamaChatModelBuilder builder,
                                                  Map<String, String> parameters) {

        String raw = parameters.get(KEY_WORKING_QUANTIZED_TYPE);
        if (isNullOrEmpty(raw)) {
            return;
        }
        try {
            builder.workingQuantizedType(DType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            LOGGER.warnv("Ignoring unusable ''{0}'' value ''{1}'' for the jlama model builder; using Jlama''s"
                    + " default. Supported: {2}.", KEY_WORKING_QUANTIZED_TYPE, sanitize(raw),
                    Arrays.toString(DType.values()));
        }
    }
}
