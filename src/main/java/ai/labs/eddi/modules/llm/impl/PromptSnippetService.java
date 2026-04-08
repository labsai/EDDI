package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.datastore.IResourceStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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
 * All snippets are auto-available via {@code {{snippets.<name>}}} in system
 * prompt templates. The cache is invalidated on any snippet store update via
 * the {@link IResourceStore.ConfigurationUpdate} CDI event.
 * <p>
 * For snippets with {@code templateEnabled=false}, the content has its template
 * markers ({@code {{}} }) escaped so the Jinja2 engine outputs them as
 * literals.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class PromptSnippetService {

    private static final Logger LOGGER = Logger.getLogger(PromptSnippetService.class);
    private static final String CACHE_KEY = "all_snippets";
    private static final String TEMPLATE_MARKER = "{{";

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
     * map keys are snippet names, values are snippet content strings.
     * <p>
     * For snippets with {@code templateEnabled=false}, template markers are escaped
     * to prevent Jinja2 resolution.
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
     * Invalidate the cache when any configuration is updated. This reacts to the
     * {@link IResourceStore.ConfigurationUpdate} CDI event fired by stores.
     */
    public void onConfigurationUpdate(@Observes IResourceStore.ConfigurationUpdate event) {
        snippetCache.invalidateAll();
        LOGGER.debug("Snippet cache invalidated due to configuration update");
    }

    /**
     * Explicitly invalidate the cache (e.g., from tests).
     */
    public void invalidateCache() {
        snippetCache.invalidateAll();
    }

    private Map<String, Object> loadAllSnippets() {
        try {
            // Use descriptor store to enumerate all snippet resources
            List<DocumentDescriptor> descriptors = descriptorStore.readDescriptors(
                    "ai.labs.snippet", "", 0, 0, false);

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
                        String content = snippet.getContent();
                        if (!snippet.isTemplateEnabled() && content.contains(TEMPLATE_MARKER)) {
                            content = escapeTemplateMarkers(content);
                        }
                        result.put(snippet.getName(), content);
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
     * Escape Jinja2 template markers so the content is output literally. Uses
     * Jinja2's built-in raw block syntax.
     */
    private static String escapeTemplateMarkers(String content) {
        // Wrap the entire content in a Jinja2 raw block
        return "{% raw %}" + content + "{% endraw %}";
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
        if (query != null && query.startsWith("version=")) {
            try {
                return Integer.parseInt(query.substring("version=".length()));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        return 1;
    }
}
