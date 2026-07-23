/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages ChatModel creation, caching, and lookup by type and parameters.
 * <p>
 * Models are cached by (type, parameters) tuple so that identical configs reuse
 * the same model instance. Thread-safe via ConcurrentHashMap.
 * <p>
 * Supports both synchronous ({@link ChatModel}) and streaming
 * ({@link StreamingChatModel}) models with separate caches.
 */
@ApplicationScoped
public class ChatModelRegistry {
    private static final String KEY_INCLUDE_FIRST_AGENT_MESSAGE = "includeFirstAgentMessage";
    private static final String KEY_SYSTEM_MESSAGE = "systemMessage";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_LOG_SIZE_LIMIT = "logSizeLimit";
    private static final String KEY_ADD_TO_OUTPUT = "addToOutput";
    private static final String KEY_CONVERT_TO_OBJECT = "convertToObject";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";

    private static final Logger LOGGER = Logger.getLogger(ChatModelRegistry.class);

    private final Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders;
    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;
    private final Map<ModelCacheKey, ChatModel> modelCache = new ConcurrentHashMap<>(1);
    private final Map<ModelCacheKey, StreamingChatModel> streamingModelCache = new ConcurrentHashMap<>(1);

    /**
     * Bumped by every cache invalidation — secret rotation, targeted secret
     * eviction, and global-variable edits. A build snapshots this before it
     * resolves any global or vault value and re-checks it before publishing, so a
     * model built from values that a concurrent invalidation has since made stale
     * is never cached. This is process-wide registry state, not per-conversation
     * state, so it is safe on this singleton.
     */
    private final AtomicLong invalidationGeneration = new AtomicLong();

    /**
     * Serializes a build's publish decision against a concurrent invalidation. The
     * publish path holds it for "re-check the generation, then {@code put}"; every
     * invalidation path holds it for "bump the generation, then
     * {@code clear}/evict". Without it the re-check would itself be a
     * check-then-act — an invalidation landing between the check and the
     * {@code put} would leave a stale model cached — which is exactly the defect
     * this guards against.
     */
    private final Object publishLock = new Object();

    @Inject
    ChatModelRegistry(Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders,
            GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver) {
        this.languageModelApiConnectorBuilders = languageModelApiConnectorBuilders;
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
    }

    /**
     * Register for write-through cache invalidation: when vault secrets are written
     * or rotated, evict affected model instances so they are rebuilt with the new
     * API key on next use. Surgical: only models whose parameters contain the
     * changed vault reference are evicted.
     */
    @PostConstruct
    void registerSecretInvalidation() {
        secretResolver.registerInvalidationListener(this::invalidateForSecret);
        globalVariableResolver.registerInvalidationListener(() -> {
            synchronized (publishLock) {
                invalidationGeneration.incrementAndGet();
                int total = modelCache.size() + streamingModelCache.size();
                modelCache.clear();
                streamingModelCache.clear();
                if (total > 0) {
                    LOGGER.infof("Invalidated all %d model(s) due to global variable change", total);
                }
            }
        });
        LOGGER.info("ChatModelRegistry registered for secret and global variable invalidation events");
    }

    /**
     * Get or create a ChatModel for the given type and processed parameters.
     * Parameters are filtered to remove non-model keys before cache lookup.
     * <p>
     * The filtered map is the cache key, so anything that shapes the constructed
     * model necessarily shapes its identity. {@code timeout}, {@code logRequests}
     * and {@code logResponses} are part of that map: {@code timeout} is consumed by
     * the provider builders and all three are consumed by
     * {@link ObservableChatModel}, so two tasks differing only in those settings
     * must get two different model instances.
     * <p>
     * The lookup is a single {@code get} rather than {@code containsKey} followed
     * by {@code get}: the cache is cleared from other threads (secret rotation,
     * global variable edits), and a clear landing between the two calls made this
     * method return {@code null} to callers that hand the result straight to
     * {@code chat(...)}. A miss simply falls through to construction.
     * <p>
     * A freshly built model is published to the cache only if no invalidation raced
     * the build — see {@link #publishIfCurrent}.
     */
    ChatModel getOrCreate(String type, Map<String, String> processedParams) throws UnsupportedLlmTaskException {

        var filteredParams = filterParams(processedParams);
        var timeoutMs = filteredParams.get(KEY_TIMEOUT);
        var logReq = processedParams.get(KEY_LOG_REQUESTS);
        var logResp = processedParams.get(KEY_LOG_RESPONSES);

        var cacheKey = new ModelCacheKey(type, filteredParams);

        ChatModel cached = modelCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        if (!languageModelApiConnectorBuilders.containsKey(type)) {
            throw new UnsupportedLlmTaskException(String.format("Type \"%s\" is not supported", type));
        }

        warnAboutRejectedTimeout(type, processedParams.get(KEY_TIMEOUT), timeoutMs);

        // Snapshot the invalidation generation BEFORE resolving values: it is the
        // resolved global/secret values that a concurrent rotation or edit makes stale,
        // so publishIfCurrent refuses to cache this model if the generation moves while
        // it is being built.
        long generationAtBuildStart = invalidationGeneration.get();

        // Resolve global variable references, then vault secrets (late-binding:
        // after Qute, before builder.build())
        var resolvedParams = globalVariableResolver.resolveAll(builderParams(filteredParams));
        resolvedParams = secretResolver.resolveSecrets(resolvedParams);
        var rawModel = languageModelApiConnectorBuilders.get(type).get().build(resolvedParams);
        var model = ObservableChatModel.wrapIfNeeded(rawModel, type, timeoutMs, logReq, logResp);
        publishIfCurrent(modelCache, cacheKey, model, generationAtBuildStart);

        return model;
    }

    /**
     * Get or create a StreamingChatModel for the given type and parameters. Returns
     * {@code null} if the builder does not support streaming.
     * <p>
     * As on the sync path, {@code timeout}/{@code logRequests}/{@code logResponses}
     * are part of the cache key. {@code timeout} reaches the streaming builder and
     * becomes the provider's streaming HTTP request/read timeout; the logging flags
     * wrap the model in {@link ObservableStreamingChatModel} so they are honoured
     * uniformly, including for providers whose streaming builder has no logging
     * switch of its own.
     * <p>
     * The lookup is a single {@code get} for the same reason as on the sync path —
     * a concurrent cache clear must never turn a hit into a {@code null} return,
     * which callers would mistake for "streaming unsupported".
     */
    StreamingChatModel getOrCreateStreaming(String type, Map<String, String> processedParams) throws UnsupportedLlmTaskException {

        var logReq = processedParams.get(KEY_LOG_REQUESTS);
        var logResp = processedParams.get(KEY_LOG_RESPONSES);

        var filteredParams = filterParams(processedParams);
        var cacheKey = new ModelCacheKey(type, filteredParams);

        StreamingChatModel cached = streamingModelCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        if (!languageModelApiConnectorBuilders.containsKey(type)) {
            throw new UnsupportedLlmTaskException(String.format("Type \"%s\" is not supported", type));
        }

        warnAboutRejectedTimeout(type, processedParams.get(KEY_TIMEOUT), filteredParams.get(KEY_TIMEOUT));

        try {
            // Snapshot the invalidation generation before resolving values a concurrent
            // invalidation could make stale (see publishIfCurrent), as on the sync path.
            long generationAtBuildStart = invalidationGeneration.get();

            // Resolve global variable references, then vault secrets (late-binding:
            // after Qute, before builder.build())
            var resolvedParams = globalVariableResolver.resolveAll(builderParams(filteredParams));
            resolvedParams = secretResolver.resolveSecrets(resolvedParams);
            var rawModel = languageModelApiConnectorBuilders.get(type).get().buildStreaming(resolvedParams);
            var model = ObservableStreamingChatModel.wrapIfNeeded(rawModel, type, logReq, logResp);
            publishIfCurrent(streamingModelCache, cacheKey, model, generationAtBuildStart);
            return model;
        } catch (UnsupportedOperationException e) {
            LOGGER.debugf("Streaming not supported for type '%s', falling back to sync", type);
            return null;
        }
    }

    /**
     * Publish a freshly built model to its cache only if no invalidation has
     * occurred since the build started.
     * <p>
     * The model was constructed from global-variable and vault-secret values
     * resolved after {@code generationAtBuildStart} was snapshotted. If the
     * generation has moved since, a secret rotation or global-variable edit landed
     * while the model was being built, so those resolved values — and therefore
     * this model — may be stale: it must not enter the cache, where later turns
     * would keep reusing it until the next unrelated invalidation. The current
     * caller still receives this instance for its own turn (its build began before
     * the rotation committed — an unavoidable, one-turn window); the next lookup
     * misses and rebuilds from current values.
     * <p>
     * The re-check and the {@code put} run under {@link #publishLock}, which every
     * invalidation path also holds around its generation bump and clear/evict, so
     * the two cannot interleave — closing the check-then-act window that a bare
     * re-check would leave open. The generation is a coarse, registry-wide signal:
     * an unrelated invalidation may cause a model to be rebuilt needlessly, which
     * is wasteful but always correct.
     */
    private <T> void publishIfCurrent(Map<ModelCacheKey, T> cache, ModelCacheKey cacheKey, T model,
                                      long generationAtBuildStart) {
        synchronized (publishLock) {
            if (invalidationGeneration.get() == generationAtBuildStart) {
                cache.put(cacheKey, model);
            } else {
                LOGGER.debugf("Model built while an invalidation landed — not caching it; the next use rebuilds");
            }
        }
    }

    /**
     * Build the cache key / model-identity map: remove all props that do not shape
     * the constructed model, and normalise {@code timeout}.
     * <p>
     * Only keys that shape nothing may be removed here. {@code timeout},
     * {@code logRequests} and {@code logResponses} are <em>not</em> among them:
     * {@code timeout} is read by every provider builder and all three are read by
     * {@link ObservableChatModel}/{@link ObservableStreamingChatModel}. Stripping
     * them dropped them from the cache key, so a task configured with a
     * {@code timeout} silently received a timeout-free model whenever a task with
     * otherwise identical parameters happened to be built first.
     */
    private Map<String, String> filterParams(Map<String, String> processedParams) {
        var returnMap = new HashMap<>(processedParams);
        returnMap.remove(KEY_INCLUDE_FIRST_AGENT_MESSAGE);
        returnMap.remove(KEY_SYSTEM_MESSAGE);
        returnMap.remove(KEY_PROMPT);
        returnMap.remove(KEY_LOG_SIZE_LIMIT);
        returnMap.remove(KEY_ADD_TO_OUTPUT);
        returnMap.remove(KEY_CONVERT_TO_OBJECT);
        normalizeTimeout(returnMap);
        return returnMap;
    }

    /**
     * Normalise a stored {@code timeout} at the single boundary that feeds both the
     * cache key and the provider builders.
     * <p>
     * The builders parse the value with an unguarded {@code Long.parseLong}, so a
     * stored {@code " "}, {@code "30s"} or {@code "0"} would abort {@code build()}
     * on every turn of that agent. Those values were previously tolerated (their
     * only consumer, {@link ObservableChatModel#wrapIfNeeded}, guards blank,
     * swallows {@link NumberFormatException} and drops non-positive durations), and
     * stored configs get no migration — so the same tolerance is applied here:
     * trim, and drop the key entirely when it is blank, non-numeric or
     * non-positive.
     * <p>
     * Normalising before the key is built also means {@code "5000"} and
     * {@code " 5000 "} are one cached model rather than two.
     */
    private static void normalizeTimeout(Map<String, String> params) {
        String raw = params.get(KEY_TIMEOUT);
        if (raw == null) {
            return;
        }
        String trimmed = raw.trim();
        if (!trimmed.isEmpty()) {
            try {
                long millis = Long.parseLong(trimmed);
                if (millis > 0) {
                    params.put(KEY_TIMEOUT, Long.toString(millis));
                    return;
                }
            } catch (NumberFormatException ignored) {
                // falls through to removal below
            }
        }
        params.remove(KEY_TIMEOUT);
    }

    /**
     * Warn once per constructed model when a stored {@code timeout} was rejected by
     * {@link #normalizeTimeout}. Deliberately emitted on the build path only — a
     * warning on every cache hit would repeat on every turn. The registry has no
     * task identity, so the model type is the most specific context available.
     */
    private static void warnAboutRejectedTimeout(String type, String rawTimeout, String normalizedTimeout) {
        if (rawTimeout != null && normalizedTimeout == null) {
            LOGGER.warnf("Ignoring unusable 'timeout' parameter '%s' for model type '%s' — expected a positive number of "
                    + "milliseconds; the model is built without a provider timeout", sanitize(rawTimeout), sanitize(type));
        }
    }

    /**
     * The parameter map actually handed to a provider builder.
     * <p>
     * {@code logRequests}/{@code logResponses} are removed: the provider builders
     * turn them into langchain4j's {@code LoggingHttpClient}, which writes the
     * entire request and response body to the application log at INFO with no
     * truncation — full prompts, full conversation history, full model output.
     * EDDI's own {@link ObservableChatModel}/{@link ObservableStreamingChatModel}
     * already honour both flags and deliberately truncate to 200/500 chars, so they
     * stay the single logging path. The flags remain part of the cache key above,
     * because they still change which model instance a task gets.
     */
    private static Map<String, String> builderParams(Map<String, String> filteredParams) {
        var builderMap = new HashMap<>(filteredParams);
        builderMap.remove(KEY_LOG_REQUESTS);
        builderMap.remove(KEY_LOG_RESPONSES);
        return builderMap;
    }

    /**
     * Evict cached model instances affected by a secret change.
     * <p>
     * When {@code reference} is non-null (single secret changed), scan cache
     * entries and evict only those whose parameter values contain a vault reference
     * to the changed secret — either short form ({@code ${vault:keyName}}) or full
     * form ({@code ${vault:tenantId/keyName}}). Also matches the legacy
     * {@code ${eddivault:...}} prefix for backward compatibility.
     * <p>
     * When {@code reference} is null (DEK/KEK rotation — all secrets affected),
     * clear everything.
     * <p>
     * The whole event runs under {@link #publishLock} and first bumps
     * {@link #invalidationGeneration}, so a build whose secret resolution overlaps
     * this rotation cannot publish its now-stale model afterwards (see
     * {@link #publishIfCurrent}). One bump covers both the full-clear and the
     * targeted {@link #evictMatching} paths.
     *
     * @param reference
     *            the changed secret, or null for full invalidation
     */
    void invalidateForSecret(SecretReference reference) {
        synchronized (publishLock) {
            invalidationGeneration.incrementAndGet();

            if (reference == null) {
                // Full invalidation (DEK/KEK rotation)
                int total = modelCache.size() + streamingModelCache.size();
                modelCache.clear();
                streamingModelCache.clear();
                if (total > 0) {
                    LOGGER.infof("Invalidated all %d model(s) due to bulk secret rotation", total);
                }
                return;
            }

            // Build the vault reference forms to search for in cached parameters.
            // New canonical form: ${vault:tenantId/keyName} — always valid
            String fullRef = "${vault:" + reference.tenantId() + "/" + reference.keyName() + "}";
            // Legacy form: ${eddivault:tenantId/keyName} — for backward compat
            String legacyFullRef = "${eddivault:" + reference.tenantId() + "/" + reference.keyName() + "}";
            // Short form: ${vault:keyName} — only valid for the default tenant.
            // The short form always resolves to "default", so a non-default tenant's
            // secret must NOT match short-form references (that would be a false positive).
            String shortRef = SecretReference.DEFAULT_TENANT.equals(reference.tenantId())
                    ? "${vault:" + reference.keyName() + "}"
                    : null;
            String legacyShortRef = SecretReference.DEFAULT_TENANT.equals(reference.tenantId())
                    ? "${eddivault:" + reference.keyName() + "}"
                    : null;

            int evicted = evictMatching(modelCache, fullRef, legacyFullRef, shortRef, legacyShortRef)
                    + evictMatching(streamingModelCache, fullRef, legacyFullRef, shortRef, legacyShortRef);

            if (evicted > 0) {
                LOGGER.infof("Evicted %d model(s) using secret '%s/%s'",
                        evicted, sanitize(reference.tenantId()), sanitize(reference.keyName()));
            }
        }
    }

    /**
     * Scan a cache and remove entries whose parameter values contain any of the
     * given vault reference forms (new and legacy prefixes).
     */
    private <T> int evictMatching(Map<ModelCacheKey, T> cache, String... refs) {
        int count = 0;
        Iterator<ModelCacheKey> it = cache.keySet().iterator();
        while (it.hasNext()) {
            ModelCacheKey key = it.next();
            if (parametersContainRef(key.parameters(), refs)) {
                it.remove();
                count++;
            }
        }
        return count;
    }

    private static boolean parametersContainRef(Map<String, String> params, String... refs) {
        for (String value : params.values()) {
            if (value == null)
                continue;
            for (String ref : refs) {
                if (ref != null && value.contains(ref)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Cache key for model lookup — defensive copy of parameters for immutability.
     */
    record ModelCacheKey(String type, Map<String, String> parameters) {
        ModelCacheKey(String type, Map<String, String> parameters) {
            this.type = type;
            this.parameters = new HashMap<>(parameters);
        }
    }

    /**
     * Thrown when a requested model type has no registered builder.
     */
    public static class UnsupportedLlmTaskException extends Exception {
        public UnsupportedLlmTaskException(String message) {
            super(message);
        }
    }
}
