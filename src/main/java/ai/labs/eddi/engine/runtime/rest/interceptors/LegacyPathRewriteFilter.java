package ai.labs.eddi.engine.runtime.rest.interceptors;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Pre-matching JAX-RS filter that rewrites legacy v5 REST paths and environment
 * segments to their v6 equivalents.
 * <p>
 * This centralizes all backwards-compatibility path mappings in a single class,
 * keeps {@code @Path} annotations clean with only v6 names, and can be removed
 * entirely in v7 as a single class deletion.
 * <p>
 * Rewrites:
 * <ul>
 *   <li>/AgentStore/bots → /agentstore/agents</li>
 *   <li>/WorkflowStore/packages → /workflowstore/workflows</li>
 *   <li>/langchainstore/langchains → /llmstore/llmconfigs</li>
 *   <li>/behaviorstore/behaviorsets → /rulestore/rulesets</li>
 *   <li>/httpcallsstore/httpcalls → /apicallstore/apicalls</li>
 *   <li>/regulardictionarystore/regulardictionaries → /dictionarystore/dictionaries</li>
 *   <li>/AgentTriggerStore/bottriggers → /triggerstore/triggers</li>
 *   <li>/{unrestricted|restricted}/ → /production/</li>
 * </ul>
 */
@PreMatching
@Provider
public class LegacyPathRewriteFilter implements ContainerRequestFilter {
    private static final Logger LOGGER = Logger.getLogger(LegacyPathRewriteFilter.class);

    /**
     * Store path rewrites: old prefix → new prefix.
     * Order matters: longer prefixes should come first to avoid partial matches.
     */
    private static final Map<String, String> PATH_REWRITES = Map.ofEntries(
            Map.entry("/regulardictionarystore/regulardictionaries", "/dictionarystore/dictionaries"),
            Map.entry("/AgentTriggerStore/bottriggers", "/triggerstore/triggers"),
            Map.entry("/behaviorstore/behaviorsets", "/rulestore/rulesets"),
            Map.entry("/langchainstore/langchains", "/llmstore/llmconfigs"),
            Map.entry("/httpcallsstore/httpcalls", "/apicallstore/apicalls"),
            Map.entry("/WorkflowStore/packages", "/workflowstore/workflows"),
            Map.entry("/AgentStore/bots", "/agentstore/agents")
    );

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getRequestUri().getPath();
        String rewritten = rewritePath(path);

        if (!rewritten.equals(path)) {
            LOGGER.debugv("Legacy path rewrite: {0} → {1}", path, rewritten);
            requestContext.setRequestUri(
                    requestContext.getUriInfo().getBaseUri(),
                    UriBuilder.fromPath(rewritten)
                            .replaceQuery(requestContext.getUriInfo().getRequestUri().getRawQuery())
                            .build()
            );
        }
    }

    static String rewritePath(String path) {
        String result = path;

        // Rewrite store paths
        for (var entry : PATH_REWRITES.entrySet()) {
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
                break; // Only one store path match per request
            }
        }

        // Rewrite environment segments: /unrestricted/ → /production/, /restricted/ → /production/
        result = result.replace("/unrestricted/", "/production/");
        result = result.replace("/restricted/", "/production/");

        // Handle trailing paths without slash (e.g., /bots/unrestricted)
        if (result.endsWith("/unrestricted")) {
            result = result.substring(0, result.length() - "/unrestricted".length()) + "/production";
        }
        if (result.endsWith("/restricted")) {
            result = result.substring(0, result.length() - "/restricted".length()) + "/production";
        }

        return result;
    }
}
