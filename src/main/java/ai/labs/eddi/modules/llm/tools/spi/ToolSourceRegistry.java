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
 * Every {@code contribute} call is wrapped, so a provider that throws
 * contributes nothing and the loop continues. Providers are still expected not
 * to throw — this is the backstop, not the excuse.
 * <p>
 * <b>Order is fixed and load-bearing.</b> Providers are supplied in the order
 * their tools should appear in the model's spec list, and merge is
 * first-write-wins per dispatch name: an earlier source's tool is never
 * displaced by a later source registering the same name. That makes collisions
 * between, say, a built-in and an MCP tool resolve deterministically instead of
 * by whichever map happened to be merged last, and it means an operator cannot
 * shadow a governed built-in by naming an MCP tool after it. Collisions log at
 * WARN, because a silently-dropped tool reads to an agent designer as "the
 * model ignored my tool".
 * <p>
 * The drop rules are {@code AgentOrchestrator#mergeExternalTools}' rules,
 * carried over verbatim so the rewiring changed no behaviour: a spec with no
 * name, or with no executor to dispatch to, is skipped with a WARN rather than
 * offered to the model as a tool that cannot possibly run.
 */
public final class ToolSourceRegistry {

    private static final Logger LOGGER = Logger.getLogger(ToolSourceRegistry.class);

    private ToolSourceRegistry() {
        // static utility — use assemble() or newMerger()
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
     *            dispatch name → configuration slug. Absent, not defaulted, for
     *            sources that supply none: {@code ToolNameResolver.canonical}
     *            already falls back to the dispatch name, and writing identity
     *            entries would change the map the rate-limit, pricing and
     *            cache-scope lookups see
     * @param failures
     *            every provider's structured failures, concatenated
     */
    public record Assembled(List<ToolSpecification> specs, Map<String, ToolExecutor> executors,
            Map<String, String> toolSources, Map<String, String> toolEndpoints,
            Map<String, String> toolCanonicalNames, List<ProviderFailure> failures) {
    }

    /**
     * Runs every provider in order and merges the results.
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
        Merger merger = newMerger();
        merger.addAll(providers, ctx);
        return merger.build();
    }

    /**
     * A merge in progress.
     * <p>
     * Exists because tool assembly is not one uniform pass. The LAZY loading
     * strategy has to build its {@code discover_tools} meta-tool from the specs the
     * <em>object-producing</em> sources contributed, and the orchestrator has to
     * snapshot exactly those same specs as {@code builtInSpecs} (what LAZY later
     * activates against) before the externally-discovered sources merge in. Both
     * need a look at the half-assembled state, which a single {@code assemble} call
     * cannot offer — while still sharing one collision namespace across the whole
     * turn, which two independent {@code assemble} calls would lose.
     */
    public static Merger newMerger() {
        return new Merger();
    }

    /** @see #newMerger() */
    public static final class Merger {

        private final List<ToolSpecification> specs = new ArrayList<>();
        private final Map<String, ToolExecutor> executors = new LinkedHashMap<>();
        private final Map<String, String> toolSources = new LinkedHashMap<>();
        private final Map<String, String> toolEndpoints = new LinkedHashMap<>();
        private final Map<String, String> toolCanonicalNames = new LinkedHashMap<>();
        private final List<ProviderFailure> failures = new ArrayList<>();

        private Merger() {
        }

        /** Runs one provider and merges its contribution. Never throws. */
        public Merger add(ToolSourceProvider provider, ToolAssemblyContext ctx) {
            if (provider == null) {
                return this;
            }
            merge(provider.source(), contributeSafely(provider, ctx));
            return this;
        }

        /** Runs each provider in order, skipping {@code null} entries. */
        public Merger addAll(List<ToolSourceProvider> providers, ToolAssemblyContext ctx) {
            for (ToolSourceProvider provider : providers) {
                add(provider, ctx);
            }
            return this;
        }

        /**
         * Merges a contribution that no provider produced — the LAZY
         * {@code discover_tools} meta-tool, which can only be built once the sources it
         * advertises have already contributed.
         */
        public Merger addContribution(String source, ToolContribution contribution) {
            merge(source, contribution != null ? contribution : ToolContribution.empty());
            return this;
        }

        /**
         * The specs merged so far, as an independent copy.
         * <p>
         * This is how the orchestrator takes its {@code builtInSpecs} snapshot at the
         * boundary between the object-producing and externally-discovered sources.
         */
        public List<ToolSpecification> specsSoFar() {
            return List.copyOf(specs);
        }

        public Assembled build() {
            return new Assembled(List.copyOf(specs), Map.copyOf(executors), Map.copyOf(toolSources),
                    Map.copyOf(toolEndpoints), Map.copyOf(toolCanonicalNames), List.copyOf(failures));
        }

        private void merge(String source, ToolContribution contribution) {
            failures.addAll(contribution.failures());

            for (ToolSpecification spec : contribution.specs()) {
                String name = spec.name();
                if (name == null) {
                    LOGGER.warnf("Skipping %s tool with no name", source);
                    continue;
                }
                if (executors.containsKey(name)) {
                    String incumbent = toolSources.getOrDefault(name, "builtin");
                    LOGGER.warnf("Tool name collision: %s tool '%s' clashes with the already-registered %s tool of "
                            + "the same name — the %s tool is DROPPED and the %s tool keeps the name. Rename the "
                            + "remote tool or exclude it via toolsBlacklist.", source, name, incumbent, source,
                            incumbent);
                    continue;
                }
                ToolExecutor executor = contribution.executors().get(name);
                if (executor == null) {
                    LOGGER.warnf("%s tool '%s' has a specification but no executor — skipping", source, name);
                    continue;
                }

                specs.add(spec);
                executors.put(name, executor);
                // Per-tool tag where the provider supplied one (bean sources emit
                // memory/recall/builtin across a single contribution); the provider's own
                // source() only as fallback. See ToolSourceProvider#source.
                toolSources.put(name, contribution.toolSources().getOrDefault(name, source));

                String endpoint = contribution.toolEndpoints().get(name);
                if (endpoint != null) {
                    toolEndpoints.put(name, endpoint);
                }
                String canonical = contribution.toolCanonicalNames().get(name);
                if (canonical != null) {
                    toolCanonicalNames.put(name, canonical);
                }
            }
        }
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
}
