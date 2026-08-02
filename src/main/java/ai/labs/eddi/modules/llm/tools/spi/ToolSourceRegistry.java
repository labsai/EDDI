/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Iterates {@link ToolSourceProvider}s in a fixed order and merges their
 * {@link ToolContribution}s into one assembled result (R2 step 2 — the rewiring
 * this SPI existed for).
 * <p>
 * <b>Isolation is the point.</b> Before this, one failing tool source could
 * abort assembly for every other source: a single unreachable MCP server, or a
 * malformed httpcall config, and the agent silently lost its calculator too.
 * {@link #assemble} wraps each {@code contribute} call, so a provider that
 * throws contributes nothing and the loop continues. Providers are still
 * expected not to throw — this is the backstop, not the excuse.
 * <p>
 * <b>Order is fixed and load-bearing.</b> The provider list is supplied in the
 * order tools should appear in the model's spec list, and merge is
 * first-write-wins per dispatch name: an earlier source's tool is never
 * displaced by a later source registering the same name. That makes name
 * collisions between, say, a built-in and an MCP tool resolve deterministically
 * instead of by whichever map happened to be merged last, and it means an
 * operator cannot shadow a governed built-in by naming an MCP tool after it.
 * Collisions are logged at WARN, because a silently-dropped tool is the kind of
 * thing that reads as "the model ignored my tool".
 */
public final class ToolSourceRegistry {

    private static final Logger LOGGER = Logger.getLogger(ToolSourceRegistry.class);

    private ToolSourceRegistry() {
        // static utility
    }

    /**
     * The merged result of every provider's contribution for one turn.
     *
     * @param specs
     *            every contributed spec, in provider order then contribution order
     * @param executors
     *            dispatch name → executor
     * @param toolSources
     *            dispatch name → provenance tag, per-tool where the provider
     *            supplied one and {@code provider.source()} otherwise
     * @param toolEndpoints
     *            dispatch name → {@code "post:/path"}, http source only
     * @param toolCanonicalNames
     *            dispatch name → configuration slug, defaulting to the dispatch
     *            name for sources that have no separate slug
     * @param failures
     *            every provider's structured failures, concatenated
     */
    public record Assembled(List<ToolSpecification> specs, Map<String, ToolExecutor> executors,
            Map<String, String> toolSources, Map<String, String> toolEndpoints,
            Map<String, String> toolCanonicalNames, List<ProviderFailure> failures) {
    }

    /**
     * Runs every provider and merges the results.
     *
     * @param providers
     *            in the order their tools should appear; a {@code null} entry is
     *            skipped so a caller can pass a conditionally-built list without
     *            filtering first
     * @param ctx
     *            the turn's assembly context, shared by every provider so they all
     *            see one consistent snapshot
     */
    public static Assembled assemble(List<ToolSourceProvider> providers, ToolAssemblyContext ctx) {
        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new LinkedHashMap<>();
        Map<String, String> toolSources = new LinkedHashMap<>();
        Map<String, String> toolEndpoints = new LinkedHashMap<>();
        Map<String, String> toolCanonicalNames = new LinkedHashMap<>();
        List<ProviderFailure> failures = new ArrayList<>();

        for (ToolSourceProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            ToolContribution contribution = contributeSafely(provider, ctx);
            mergeOne(provider, contribution, specs, executors, toolSources, toolEndpoints, toolCanonicalNames);
            failures.addAll(contribution.failures());
        }

        return new Assembled(specs, executors, toolSources, toolEndpoints, toolCanonicalNames, failures);
    }

    /**
     * One provider's contribution, or {@link ToolContribution#empty()} if it threw.
     * <p>
     * Catches {@link Throwable} rather than {@link Exception} on purpose. The
     * realistic non-Exception here is {@code NoClassDefFoundError} or
     * {@code LinkageError} from an optional integration whose dependency is absent
     * at runtime — precisely a per-source problem that must not take the other
     * sources down with it. {@code Error}s that genuinely indicate a doomed JVM
     * ({@code OutOfMemoryError}, {@code StackOverflowError}) will resurface at the
     * next allocation regardless, so swallowing them here delays nothing.
     */
    private static ToolContribution contributeSafely(ToolSourceProvider provider, ToolAssemblyContext ctx) {
        try {
            ToolContribution contribution = provider.contribute(ctx);
            return contribution != null ? contribution : ToolContribution.empty();
        } catch (Throwable t) {
            LOGGER.warnf("Tool source '%s' failed to contribute and was skipped — the remaining sources still "
                    + "assemble: %s", provider.source(), t.toString());
            return ToolContribution.empty();
        }
    }

    private static void mergeOne(ToolSourceProvider provider, ToolContribution contribution,
                                 List<ToolSpecification> specs, Map<String, ToolExecutor> executors, Map<String, String> toolSources,
                                 Map<String, String> toolEndpoints, Map<String, String> toolCanonicalNames) {

        for (ToolSpecification spec : contribution.specs()) {
            String name = spec.name();
            if (executors.containsKey(name) || toolSources.containsKey(name)) {
                LOGGER.warnf("Tool name collision: '%s' from source '%s' is shadowed by an earlier source ('%s') "
                        + "and was dropped", name, provider.source(), toolSources.getOrDefault(name, "unknown"));
                continue;
            }
            specs.add(spec);
            // Per-tool tag where the provider supplied one (bean sources emit
            // memory/recall/builtin across a single contribution); the provider's own
            // source() only as fallback. See ToolSourceProvider#source.
            toolSources.put(name, contribution.toolSources().getOrDefault(name, provider.source()));

            ToolExecutor executor = contribution.executors().get(name);
            if (executor != null) {
                executors.put(name, executor);
            }
            String endpoint = contribution.toolEndpoints().get(name);
            if (endpoint != null) {
                toolEndpoints.put(name, endpoint);
            }
            // Dispatch name is its own canonical name unless the source says otherwise,
            // so the executor boundary always has a slug to price and cache under.
            toolCanonicalNames.put(name, contribution.toolCanonicalNames().getOrDefault(name, name));
        }
    }
}
