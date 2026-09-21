/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.rest.interceptors;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 * <li>/botstore/bots → /agentstore/agents</li>
 * <li>/packagestore/packages → /workflowstore/workflows</li>
 * <li>/langchainstore/langchains → /llmstore/llms</li>
 * <li>/behaviorstore/behaviorsets → /rulestore/rulesets</li>
 * <li>/httpcallsstore/httpcalls → /apicallstore/apicalls</li>
 * <li>/regulardictionarystore/regulardictionaries →
 * /dictionarystore/dictionaries</li>
 * <li>/bottriggerstore/bottriggers → /AgentTriggerStore/agenttriggers</li>
 * <li>/{unrestricted|restricted}/ → /production/</li>
 * </ul>
 * <p>
 * The environment rewrites mirror {@code Deployment.Environment.fromString},
 * which still maps the v5 names {@code unrestricted} and {@code restricted}
 * onto {@code production}. Target paths must match the declared {@code @Path}
 * values exactly — JAX-RS matching is case-sensitive, so
 * {@code /AgentTriggerStore/agenttriggers} keeps its capitalisation.
 */
@PreMatching
@Provider
public class LegacyPathRewriteFilter implements ContainerRequestFilter {
    private static final Logger LOGGER = Logger.getLogger(LegacyPathRewriteFilter.class);

    private static final String LEGACY_ENV_UNRESTRICTED = "unrestricted";
    private static final String LEGACY_ENV_RESTRICTED = "restricted";
    private static final String ENV_PRODUCTION = "production";

    /**
     * Store path rewrites: old prefix → new prefix. Backed by a
     * {@link LinkedHashMap} so iteration order is the insertion order declared
     * below — longer prefixes first, so a shorter prefix can never shadow a longer
     * one (a plain {@code Map.of}/{@code Map.ofEntries} gives no order guarantee).
     */
    private static final Map<String, String> PATH_REWRITES = createPathRewrites();

    private static Map<String, String> createPathRewrites() {
        var rewrites = new LinkedHashMap<String, String>();
        rewrites.put("/regulardictionarystore/regulardictionaries", "/dictionarystore/dictionaries");
        rewrites.put("/bottriggerstore/bottriggers", "/AgentTriggerStore/agenttriggers");
        rewrites.put("/behaviorstore/behaviorsets", "/rulestore/rulesets");
        rewrites.put("/langchainstore/langchains", "/llmstore/llms");
        rewrites.put("/httpcallsstore/httpcalls", "/apicallstore/apicalls");
        rewrites.put("/packagestore/packages", "/workflowstore/workflows");
        rewrites.put("/botstore/bots", "/agentstore/agents");
        rewrites.put("/langchain/tools", "/llm/tools");
        return Collections.unmodifiableMap(rewrites);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getRequestUri().getPath();
        String rewritten = rewritePath(path);

        if (!rewritten.equals(path)) {
            LOGGER.debugv("Legacy path rewrite: {0} → {1}", path, rewritten);
            requestContext.setRequestUri(requestContext.getUriInfo().getBaseUri(),
                    UriBuilder.fromPath(rewritten).replaceQuery(requestContext.getUriInfo().getRequestUri().getRawQuery()).build());
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

        // Rewrite environment segments: /unrestricted/ → /production/, /restricted/ →
        // /production/
        result = rewriteEnvironment(result, LEGACY_ENV_UNRESTRICTED);
        result = rewriteEnvironment(result, LEGACY_ENV_RESTRICTED);

        return result;
    }

    /**
     * Replaces a legacy environment segment with {@code production}, both when it
     * sits between two slashes and when it terminates the path (e.g.
     * {@code /agents/{id}/unrestricted}).
     */
    private static String rewriteEnvironment(String path, String legacyEnvironment) {
        String result = path.replace("/" + legacyEnvironment + "/", "/" + ENV_PRODUCTION + "/");

        String trailing = "/" + legacyEnvironment;
        if (result.endsWith(trailing)) {
            result = result.substring(0, result.length() - trailing.length()) + "/" + ENV_PRODUCTION;
        }

        return result;
    }
}
