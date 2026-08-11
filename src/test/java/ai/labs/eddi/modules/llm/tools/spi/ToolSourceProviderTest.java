/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2's post-condition test (plan §3.2): provider isolation. A tool source that
 * fails must cost the turn only its own tools — never anyone else's.
 * <p>
 * This is the guarantee the whole SPI was introduced for. Before it,
 * {@code buildToolSetup} called each source by name in one straight line, so an
 * unreachable MCP server or a malformed httpcall config could abort assembly
 * and silently take the agent's calculator down with it. The tests here pin
 * that a throwing provider yields an empty contribution and the loop continues,
 * and that merge order resolves name collisions deterministically rather than
 * by whichever map happened to be merged last.
 *
 * @author tests
 */
class ToolSourceProviderTest {

    /**
     * A provider contributing one tool named {@code name}, tagged {@code source}.
     */
    private static ToolSourceProvider provider(String source, String name) {
        return provider(source, name, Map.of(), Map.of(), Map.of());
    }

    private static ToolSourceProvider provider(String source, String name, Map<String, String> perToolSources,
                                               Map<String, String> endpoints, Map<String, String> canonicalNames) {
        return new ToolSourceProvider() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public ToolContribution contribute(ToolAssemblyContext ctx) {
                var spec = ToolSpecification.builder().name(name).description("d").build();
                ToolExecutor executor = (request, memoryId) -> "ok";
                return new ToolContribution(List.of(spec), Map.of(name, executor), perToolSources, endpoints,
                        List.of(), canonicalNames);
            }
        };
    }

    private static ToolSourceProvider throwingProvider(String source, RuntimeException boom) {
        return new ToolSourceProvider() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public ToolContribution contribute(ToolAssemblyContext ctx) {
                throw boom;
            }
        };
    }

    // =================================================================
    // Isolation — the reason this SPI exists
    // =================================================================

    @Test
    void aThrowingProviderYieldsAnEmptyContributionAndTheLoopContinues() {
        var assembled = ToolSourceRegistry.assemble(List.of(
                provider("builtin", "calculator"),
                throwingProvider("mcp", new IllegalStateException("MCP server unreachable")),
                provider("http", "createOrder")), null);

        assertEquals(List.of("calculator", "createOrder"),
                assembled.specs().stream().map(ToolSpecification::name).toList(),
                "the failing source contributes nothing; every other source still assembles");
        assertEquals(2, assembled.executors().size());
    }

    /**
     * {@code Throwable}, not just {@code Exception}: an optional integration whose
     * dependency is missing at runtime fails with {@code NoClassDefFoundError},
     * which is exactly the per-source problem isolation is for.
     */
    @Test
    void aProviderThrowingAnErrorIsAlsoIsolated() {
        ToolSourceProvider linkageFailure = new ToolSourceProvider() {
            @Override
            public String source() {
                return "a2a";
            }

            @Override
            public ToolContribution contribute(ToolAssemblyContext ctx) {
                throw new NoClassDefFoundError("some/optional/Dependency");
            }
        };

        var assembled = ToolSourceRegistry.assemble(
                List.of(linkageFailure, provider("builtin", "calculator")), null);

        assertEquals(List.of("calculator"), assembled.specs().stream().map(ToolSpecification::name).toList());
    }

    @Test
    void everyProviderFailing_yieldsAnEmptyAssemblyRatherThanThrowing() {
        var assembled = ToolSourceRegistry.assemble(List.of(
                throwingProvider("http", new RuntimeException("a")),
                throwingProvider("mcp", new RuntimeException("b"))), null);

        assertTrue(assembled.specs().isEmpty());
        assertTrue(assembled.executors().isEmpty());
        assertTrue(assembled.failures().isEmpty());
    }

    @Test
    void aProviderReturningNullIsTreatedAsEmpty() {
        ToolSourceProvider nullReturner = new ToolSourceProvider() {
            @Override
            public String source() {
                return "dynamic";
            }

            @Override
            public ToolContribution contribute(ToolAssemblyContext ctx) {
                return null;
            }
        };

        var assembled = ToolSourceRegistry.assemble(
                List.of(nullReturner, provider("builtin", "calculator")), null);

        assertEquals(List.of("calculator"), assembled.specs().stream().map(ToolSpecification::name).toList());
    }

    @Test
    void nullProviderEntriesAreSkipped() {
        var assembled = ToolSourceRegistry.assemble(
                Arrays.asList(null, provider("builtin", "calculator"), null), null);

        assertEquals(1, assembled.specs().size());
    }

    // =================================================================
    // Merge semantics
    // =================================================================

    /**
     * First-write-wins, so an operator cannot shadow a governed built-in by naming
     * an MCP tool after it — and so the winner does not depend on map iteration
     * order.
     */
    @Test
    void nameCollision_firstProviderWins() {
        var assembled = ToolSourceRegistry.assemble(List.of(
                provider("builtin", "search"),
                provider("mcp", "search")), null);

        assertEquals(1, assembled.specs().size());
        assertEquals("builtin", assembled.toolSources().get("search"),
                "the earlier source keeps both the tool and its provenance tag");
    }

    @Test
    void nameCollision_incumbentKeepsItsEXECUTOR_notJustItsName() throws Exception {
        // F15. The only test asserting this ran against mergeExternalTools, which has
        // no production caller — so on the live path a collision branch that
        // overwrote the executor while leaving the spec count and provenance tag
        // intact would have gone unnoticed, and a remote MCP server advertising
        // "calculator" would have served every calculator call.
        var assembled = ToolSourceRegistry.assemble(List.of(
                echoProvider("builtin", "calculator", "from-builtin"),
                echoProvider("mcp", "calculator", "from-mcp")), null);

        assertEquals(1, assembled.specs().size());
        assertEquals("builtin", assembled.toolSources().get("calculator"));
        assertEquals("from-builtin", assembled.executors().get("calculator").execute(null, null),
                "the remote tool must not shadow the governed built-in's EXECUTOR");
    }

    /**
     * Like {@link #provider}, but each source's executor returns a distinguishable
     * value.
     */
    private static ToolSourceProvider echoProvider(String source, String name, String result) {
        return new ToolSourceProvider() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public ToolContribution contribute(ToolAssemblyContext ctx) {
                var spec = ToolSpecification.builder().name(name).description("d").build();
                ToolExecutor executor = (request, memoryId) -> result;
                return new ToolContribution(List.of(spec), Map.of(name, executor), Map.of(), Map.of(), List.of(), Map.of());
            }
        };
    }

    /**
     * Per-tool tags win over {@code source()}. A bean source legitimately emits
     * {@code memory}/{@code recall}/{@code builtin} across one contribution, and
     * {@code ToolApprovalGate} matches {@code source:name} globs — stamping one tag
     * over the contribution would unmatch a {@code require: ["memory:*"]} pattern.
     */
    @Test
    void perToolSourceTagsWinOverTheProvidersNominalSource() {
        var assembled = ToolSourceRegistry.assemble(List.of(
                provider("builtin", "rememberThis", Map.of("rememberThis", "memory"), Map.of(), Map.of())), null);

        assertEquals("memory", assembled.toolSources().get("rememberThis"));
    }

    @Test
    void providerSourceIsTheFallbackWhenNoPerToolTagIsSupplied() {
        var assembled = ToolSourceRegistry.assemble(List.of(provider("mcp", "listIssues")), null);

        assertEquals("mcp", assembled.toolSources().get("listIssues"));
    }

    /**
     * Absent, not defaulted. {@code ToolNameResolver.canonical} already falls back
     * to the dispatch name for an unmapped tool, and the pre-SPI
     * {@code ToolSetup.toolCanonicalNames} carried built-ins only — writing an
     * identity entry for every http/mcp/a2a tool would have changed the map the
     * rate-limit, pricing and cache-scope lookups see.
     */
    @Test
    void canonicalNameIsAbsentForSourcesThatSupplyNone() {
        var assembled = ToolSourceRegistry.assemble(List.of(provider("http", "createOrder")), null);

        assertFalse(assembled.toolCanonicalNames().containsKey("createOrder"));
    }

    @Test
    void canonicalNameIsCarriedThroughWhenTheSourceSuppliesOne() {
        var assembled = ToolSourceRegistry.assemble(List.of(
                provider("builtin", "searchWeb", Map.of(), Map.of(), Map.of("searchWeb", "websearch"))), null);

        assertEquals("websearch", assembled.toolCanonicalNames().get("searchWeb"));
    }

    @Test
    void endpointsAreCarriedThroughForTheHttpSource() {
        var assembled = ToolSourceRegistry.assemble(List.of(
                provider("http", "createOrder", Map.of(), Map.of("createOrder", "post:/orders"), Map.of())), null);

        assertEquals("post:/orders", assembled.toolEndpoints().get("createOrder"),
                "endpoint-qualified approval patterns depend on this surviving the merge");
    }

    @Test
    void specOrderFollowsProviderOrder() {
        var assembled = ToolSourceRegistry.assemble(List.of(
                provider("builtin", "a"), provider("http", "b"), provider("mcp", "c")), null);

        assertEquals(List.of("a", "b", "c"), assembled.specs().stream().map(ToolSpecification::name).toList());
    }

    @Test
    void failuresFromEveryProviderAreConcatenated() {
        ToolSourceProvider reporting = new ToolSourceProvider() {
            @Override
            public String source() {
                return "mcp";
            }

            @Override
            public ToolContribution contribute(ToolAssemblyContext ctx) {
                return new ToolContribution(List.of(), Map.of(), Map.of(), Map.of(),
                        List.of(new ProviderFailure("mcp", "srv-1", ProviderFailure.Kind.CONNECTION_FAILURE,
                                "connect timeout")),
                        Map.of());
            }
        };

        var assembled = ToolSourceRegistry.assemble(List.of(reporting, reporting), null);

        assertEquals(2, assembled.failures().size(),
                "a structured failure is a report, not an error — both are surfaced");
    }

    // =================================================================
    // Drop rules, carried over verbatim from mergeExternalTools
    // =================================================================

    /**
     * A spec the model can call but nothing can dispatch is worse than no tool at
     * all — the model spends a turn on it and gets an error.
     */
    @Test
    void specWithNoExecutorIsDropped() {
        ToolSourceProvider specOnly = new ToolSourceProvider() {
            @Override
            public String source() {
                return "mcp";
            }

            @Override
            public ToolContribution contribute(ToolAssemblyContext ctx) {
                return new ToolContribution(List.of(ToolSpecification.builder().name("orphan").build()),
                        Map.of(), Map.of(), Map.of());
            }
        };

        var assembled = ToolSourceRegistry.assemble(List.of(specOnly, provider("builtin", "calculator")), null);

        assertEquals(List.of("calculator"), assembled.specs().stream().map(ToolSpecification::name).toList());
    }

    @Test
    void specWithNoNameIsDropped() {
        ToolSourceProvider nameless = new ToolSourceProvider() {
            @Override
            public String source() {
                return "a2a";
            }

            @Override
            public ToolContribution contribute(ToolAssemblyContext ctx) {
                return new ToolContribution(List.of(ToolSpecification.builder().description("no name").build()),
                        Map.of(), Map.of(), Map.of());
            }
        };

        var assembled = ToolSourceRegistry.assemble(List.of(nameless, provider("builtin", "calculator")), null);

        assertEquals(List.of("calculator"), assembled.specs().stream().map(ToolSpecification::name).toList());
    }

    // =================================================================
    // Merger — the half-assembled view LAZY needs
    // =================================================================

    @Test
    void specsSoFar_snapshotsTheBoundaryBetweenLocalAndExternalSources() {
        var merger = ToolSourceRegistry.newMerger();
        merger.add(provider("builtin", "calculator"), null);

        var builtInSpecs = merger.specsSoFar();

        merger.add(provider("mcp", "listIssues"), null);

        assertEquals(List.of("calculator"), builtInSpecs.stream().map(ToolSpecification::name).toList(),
                "the snapshot must not see sources merged after it was taken");
        assertEquals(List.of("calculator", "listIssues"),
                merger.build().specs().stream().map(ToolSpecification::name).toList());
    }

    @Test
    void addContribution_mergesToolsNoProviderProduced() {
        var merger = ToolSourceRegistry.newMerger();
        merger.add(provider("builtin", "calculator"), null);
        merger.addContribution("builtin", new ToolContribution(
                List.of(ToolSpecification.builder().name("discover_tools").build()),
                Map.of("discover_tools", (ToolExecutor) (request, memoryId) -> "{}"), Map.of(), Map.of()));

        assertEquals(List.of("calculator", "discover_tools"),
                merger.build().specs().stream().map(ToolSpecification::name).toList());
    }

    // =================================================================
    // ToolContribution immutability
    // =================================================================

    @Test
    void contributionComponentsAreImmutableRegardlessOfWhatAProviderPassed() {
        var mutableSpecs = new ArrayList<ToolSpecification>();
        var mutableSources = new HashMap<String, String>();
        var contribution = new ToolContribution(mutableSpecs, Map.of(), mutableSources, Map.of(), List.of(), Map.of());

        assertThrows(UnsupportedOperationException.class,
                () -> contribution.specs().add(ToolSpecification.builder().name("x").build()));
        assertThrows(UnsupportedOperationException.class, () -> contribution.toolSources().put("x", "y"));
    }

    @Test
    void nullComponentsBecomeEmptyRatherThanNull() {
        var contribution = new ToolContribution(null, null, null, null, null, null);

        assertTrue(contribution.specs().isEmpty());
        assertTrue(contribution.executors().isEmpty());
        assertTrue(contribution.toolSources().isEmpty());
        assertTrue(contribution.toolEndpoints().isEmpty());
        assertTrue(contribution.failures().isEmpty());
        assertTrue(contribution.toolCanonicalNames().isEmpty());
    }
}
