/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

/**
 * One tool call as it travels through {@link ToolExecutionService}, carrying
 * both names a tool has.
 *
 * <p>
 * A built-in tool is <em>configured</em> under a slug — the token an agent
 * designer writes into {@code builtInToolsWhitelist}, {@code toolRateLimits} or
 * {@code toolPricing} ({@code websearch}, {@code calculator}, …) — but it is
 * <em>dispatched</em> under the name of the {@code @Tool} method the model
 * actually called ({@code searchWeb}, {@code calculate}, …). The two are
 * different strings for every built-in, because no built-in sets
 * {@code @Tool(name = …)}. Keeping only one of them at the executor boundary is
 * what made per-call prices resolve to {@code $0.00} and the documented
 * slug-keyed {@code toolRateLimits} silently inert.
 * </p>
 *
 * <p>
 * The split matters for correctness, not just tidiness:
 * </p>
 * <ul>
 * <li>{@link #dispatchName()} identifies the <em>call</em>. Cache keys, rate
 * limit buckets, metric tags and per-tool cost breakdowns all use it —
 * {@code searchWeb}, {@code searchNews} and {@code searchWikipedia} are three
 * distinct operations that must never share a cache entry.</li>
 * <li>{@link #canonicalName()} identifies the <em>tool</em> as configured. Only
 * the price table and the TTL table are looked up under it, because those are
 * properties of the tool, not of the individual method.</li>
 * </ul>
 *
 * <p>
 * Immutable, and therefore safe to pass across the stateless singletons in this
 * package.
 * </p>
 *
 * @param dispatchName
 *            the name the model invoked and langchain4j dispatches on; never
 *            null in practice
 * @param canonicalName
 *            the configuration-facing slug; falls back to {@code dispatchName}
 *            when the tool has no slug (http/mcp/a2a/dynamic tools, where the
 *            two names are the same thing)
 * @param priceOverride
 *            operator-supplied per-call price in USD from {@code toolPricing},
 *            or {@code null} to use the built-in default price
 */
public record ToolInvocation(String dispatchName, String canonicalName, Double priceOverride) {

    public ToolInvocation {
        // A null canonical name is normalised rather than rejected: every lookup that
        // consumes it degrades gracefully to the dispatch name, and a
        // NullPointerException
        // deep inside a cost lookup would be a poor trade for a tool call that is
        // otherwise perfectly executable.
        if (canonicalName == null) {
            canonicalName = dispatchName;
        }
    }

    /**
     * An invocation with no canonical mapping and no price override — the identity
     * case used by callers that never had a slug to begin with.
     */
    public static ToolInvocation of(String dispatchName) {
        return new ToolInvocation(dispatchName, dispatchName, null);
    }
}
