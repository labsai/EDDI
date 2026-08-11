/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cached service that loads all prompt snippets and provides them as a template
 * data map for LLM task system prompts.
 * <p>
 * All snippets are auto-available via {@code {snippets.<name>}} in system
 * prompt templates. The cache auto-expires after 5 minutes (TTL) and can be
 * explicitly invalidated via {@link #invalidateCache()}.
 * <p>
 * <b>Content is stored raw.</b> A snippet is never concatenated into a
 * template's SOURCE — it is put into the template DATA map and pulled in by an
 * expression, and Qute does not re-parse what an expression resolved to. Any
 * {@code {...}} inside a snippet therefore reaches the model literally already,
 * which is exactly the {@code templateEnabled=false} guarantee, for free and
 * for every snippet.
 * <p>
 * This used to wrap {@code templateEnabled=false} content in a Qute unparsed
 * block. That protected nothing it was not already protected from, and since
 * the wrapper is itself a resolved value it was likewise never re-parsed: the
 * {@code {|…|}} delimiters travelled into the system prompt verbatim. Escaping
 * belongs only where generated text is concatenated into template source — see
 * {@link ai.labs.eddi.modules.templating.TemplateEscaping}.
 * <p>
 * The corollary is that {@code templateEnabled=true} does not make a snippet's
 * markers resolve either; the flag currently has no effect on this path.
 * Honouring it would mean rendering snippet content in a second pass, which is
 * a design decision with an injection surface attached — snippet text is
 * admin-authored, but a second evaluation pass over data is precisely the shape
 * EDDI avoids elsewhere. Left as-is deliberately rather than by oversight.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class PromptSnippetService {

    private static final Logger LOGGER = Logger.getLogger(PromptSnippetService.class);
    private static final String CACHE_KEY = "all_snippets";

    private final IPromptSnippetStore snippetStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    /**
     * Single-entry cache holding the full snippet map. Invalidated on any
     * configuration update event. TTL fallback ensures eventual consistency even if
     * events are missed.
     */
    private final Cache<String, Map<String, Object>> snippetCache;

    @Inject
    public PromptSnippetService(IPromptSnippetStore snippetStore,
            IDocumentDescriptorStore descriptorStore,
            MeterRegistry meterRegistry) {
        this.snippetStore = snippetStore;
        this.descriptorStore = descriptorStore;
        this.cacheHitCounter = meterRegistry.counter("eddi.snippets.cache.hits");
        this.cacheMissCounter = meterRegistry.counter("eddi.snippets.cache.misses");

        this.snippetCache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    @PostConstruct
    void warmCache() {
        try {
            getAll(); // Pre-populate cache at startup
        } catch (Exception e) {
            LOGGER.warnv("Failed to warm snippet cache at startup: {0}", e.getMessage());
        }
    }

    /**
     * Get all snippets as a map suitable for injection into the template data. The
     * map keys are snippet names, values are snippet content strings, verbatim.
     * <p>
     * Nothing is escaped on the way in, and nothing needs to be — see the class
     * javadoc for why a value reached through this map is never re-parsed.
     *
     * @return unmodifiable map of snippet name → content
     */
    public Map<String, Object> getAll() {
        Map<String, Object> cached = snippetCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            cacheHitCounter.increment();
            return cached;
        }

        cacheMissCounter.increment();
        Map<String, Object> snippetMap = loadAllSnippets();
        snippetCache.put(CACHE_KEY, snippetMap);
        return snippetMap;
    }

    /**
     * Explicitly invalidate the snippet cache. Call when snippets are updated
     * (e.g., from the REST layer) or from tests.
     */
    public void invalidateCache() {
        snippetCache.invalidateAll();
        LOGGER.debug("Snippet cache invalidated");
    }

    private Map<String, Object> loadAllSnippets() {
        try {
            // Use descriptor store to enumerate all snippet resources
            List<DocumentDescriptor> descriptors = descriptorStore.readDescriptors(
                    "ai.labs.snippet", "", 0, IDescriptorStore.NO_LIMIT, false);

            if (descriptors == null || descriptors.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            for (DocumentDescriptor descriptor : descriptors) {
                try {
                    URI resourceUri = descriptor.getResource();
                    String id = extractIdFromUri(resourceUri);
                    Integer version = extractVersionFromUri(resourceUri);
                    PromptSnippet snippet = snippetStore.read(id, version);
                    if (snippet != null && snippet.getName() != null && snippet.getContent() != null) {
                        // Stored RAW — see the class javadoc. A snippet reaches a prompt as a
                        // template DATA VALUE, and Qute does not re-parse what an expression
                        // resolved to, so its markers are already literal. Wrapping it in an
                        // unparsed block only added the block's own delimiters to the prompt.
                        result.put(snippet.getName(), snippet.getContent());
                    }
                } catch (IResourceStore.ResourceNotFoundException e) {
                    LOGGER.debugv("Snippet descriptor references missing resource: {0}", descriptor.getResource());
                }
            }

            LOGGER.debugv("Loaded {0} prompt snippets into cache", result.size());
            return Collections.unmodifiableMap(result);

        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            LOGGER.errorv("Failed to load prompt snippets: {0}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Extract the resource ID from a resource URI like
     * {@code eddi://ai.labs.snippet/snippetstore/snippets/<id>?version=1}.
     */
    private static String extractIdFromUri(URI resourceUri) {
        if (resourceUri == null)
            return "";
        String path = resourceUri.getPath();
        if (path == null)
            return "";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * Extract the version number from a resource URI query string. Falls back to
     * version 1 if not present.
     */
    private static Integer extractVersionFromUri(URI resourceUri) {
        if (resourceUri == null)
            return 1;
        String query = resourceUri.getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("version=")) {
                    try {
                        return Integer.parseInt(param.substring("version=".length()));
                    } catch (NumberFormatException e) {
                        return 1;
                    }
                }
            }
        }
        return 1;
    }
}
