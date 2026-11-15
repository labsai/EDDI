/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.impl.DiscoverToolsTool;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolGroup;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code toolLoadingStrategy: LAZY} over the externally-discovered sources
 * ({@code lazySources}): which tools {@code discover_tools} manages, its
 * grouped form, and the activated names kept in conversation memory for
 * {@code lazyRetention}.
 * <p>
 * The lazily-managed specs travel in
 * {@link AgentOrchestrator.ToolSetup#builtInSpecs()} — the component LAZY has
 * always activated from — so the live loop, the resume path and the refusal of
 * an un-activated call all read one list. With the default {@code lazySources}
 * ({@code builtin} only) that list is exactly what it always was.
 * <p>
 * Stateless: every method is static, and what a turn activated lives on the
 * conversation step, never here.
 */
final class LazyToolCatalog {

    private static final Logger LOGGER = Logger.getLogger(LazyToolCatalog.class);

    static final String SOURCE_BUILTIN = "builtin";
    static final Set<String> EXTERNAL_SOURCES = Set.of("http", "mcp", "a2a");
    private static final Set<String> KNOWN_SOURCES = Set.of(SOURCE_BUILTIN, "http", "mcp", "a2a");

    static final String RETENTION_TURN = "turn";
    static final String RETENTION_CONVERSATION = "conversation";

    /**
     * Step data key prefix for the names a LAZY task activated; suffixed with the
     * task id. Not an {@code output}/{@code actions} key, so clients never see it.
     */
    static final String ACTIVATED_KEY_PREFIX = "llm:lazyTools:activated:";

    /**
     * Tool result for a call to a lazily-loaded tool that {@code discover_tools}
     * has not activated. The tool is never run.
     */
    static final String NOT_ACTIVATED_REFUSAL = "{\"status\":\"NOT_EXECUTED\",\"reason\":\"this tool has not been loaded yet; call "
            + "discover_tools first (by group or keywords) and then call the tool it returns\"}";

    private LazyToolCatalog() {
    }

    /**
     * The effective {@code lazySources}, lower-cased: {@code ["builtin"]} when
     * unset or empty. Unknown entries are dropped with a WARN.
     */
    static Set<String> lazySources(LlmConfiguration.Task task) {
        List<String> configured = task.getLazySources();
        if (configured == null || configured.isEmpty()) {
            return Set.of(SOURCE_BUILTIN);
        }
        Set<String> sources = new LinkedHashSet<>();
        for (String source : configured) {
            String normalized = source == null ? "" : source.strip().toLowerCase(Locale.ROOT);
            if (KNOWN_SOURCES.contains(normalized)) {
                sources.add(normalized);
            } else {
                LOGGER.warnf("Ignoring unknown lazySources entry '%s' on LLM task '%s' (known: builtin, http, mcp, a2a)",
                        source, task.getId());
            }
        }
        return sources;
    }

    /** True when LAZY is on and at least one external source is lazy. */
    static boolean externalLazy(LlmConfiguration.Task task) {
        if (task.getToolLoadingStrategy() != LlmConfiguration.ToolLoadingStrategy.LAZY) {
            return false;
        }
        for (String source : lazySources(task)) {
            if (EXTERNAL_SOURCES.contains(source)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reserves the {@code discover_tools} name in the first assembly phase, so no
     * external tool can take it; {@link #complete} replaces the spec and executor
     * once the externally-discovered sources have merged.
     */
    static ToolContribution placeholder() {
        var spec = ToolSpecification.builder().name(DiscoverToolsTool.NAME).description("discover tools").build();
        ToolExecutor unreachable = (request, memoryId) -> "{\"tools\":[]}";
        return new ToolContribution(List.of(spec), Map.of(DiscoverToolsTool.NAME, unreachable), Map.of(DiscoverToolsTool.NAME, SOURCE_BUILTIN),
                Map.of());
    }

    /**
     * Turns an assembly that reserved {@code discover_tools} into the turn's setup:
     * the lazily-managed specs become the catalog, the meta-tool is given its
     * grouped spec and executor, and the catalog travels as {@code builtInSpecs}.
     * With an empty catalog the meta-tool is removed again, so the request looks
     * exactly as it would without LAZY.
     *
     * @param phaseOneNames
     *            the names the object-producing sources contributed
     * @param lazyBuiltins
     *            whether those are lazily managed too (built-ins enabled and
     *            {@code builtin} listed)
     */
    static AgentOrchestrator.ToolSetup complete(ToolSourceRegistry.Assembled assembled, Set<String> phaseOneNames, boolean lazyBuiltins,
                                                LlmConfiguration.Task task) {
        Set<String> sources = lazySources(task);
        List<ToolSpecification> specs = new ArrayList<>(assembled.specs());
        Map<String, ToolExecutor> executors = new HashMap<>(assembled.executors());
        Map<String, String> toolSources = new HashMap<>(assembled.toolSources());

        List<ToolSpecification> catalog = new ArrayList<>();
        Map<String, ToolGroup> groups = new LinkedHashMap<>();
        for (ToolSpecification spec : specs) {
            String name = spec.name();
            if (DiscoverToolsTool.NAME.equals(name)) {
                continue;
            }
            String source = toolSources.getOrDefault(name, SOURCE_BUILTIN);
            boolean lazy = phaseOneNames.contains(name) ? lazyBuiltins : sources.contains(source);
            if (!lazy) {
                continue;
            }
            catalog.add(spec);
            ToolGroup group = assembled.toolGroups().get(name);
            groups.put(name, group != null ? group : new ToolGroup(phaseOneNames.contains(name) ? SOURCE_BUILTIN : source, null));
        }

        if (catalog.isEmpty()) {
            specs.removeIf(spec -> DiscoverToolsTool.NAME.equals(spec.name()));
            executors.remove(DiscoverToolsTool.NAME);
            toolSources.remove(DiscoverToolsTool.NAME);
            return new AgentOrchestrator.ToolSetup(specs, executors, toolSources, List.of(), assembled.toolCanonicalNames(),
                    assembled.toolEndpoints(), assembled.toolRequestResolvers());
        }

        var discover = new DiscoverToolsTool(catalog, task.getMaxToolsInContext(), groups);
        ToolSpecification groupedSpec = discover.groupedSpec();
        for (int i = 0; i < specs.size(); i++) {
            if (DiscoverToolsTool.NAME.equals(specs.get(i).name())) {
                specs.set(i, groupedSpec);
            }
        }
        executors.put(DiscoverToolsTool.NAME, discover.groupedExecutor());
        List<ToolSpecification> managed = new ArrayList<>(catalog);
        managed.add(groupedSpec);
        LOGGER.debugf("LAZY tool loading (lazySources=%s): %d of %d tools behind discover_tools", sources, catalog.size(),
                specs.size() - 1);
        return new AgentOrchestrator.ToolSetup(specs, executors, toolSources, managed, assembled.toolCanonicalNames(),
                assembled.toolEndpoints(), assembled.toolRequestResolvers());
    }

    // ─── Refusal of a call to a tool that is not loaded ───

    /**
     * Whether {@code request} names a lazily-managed tool that is not in the
     * request's tool surface — a call the model can only have guessed, which is
     * refused instead of run.
     */
    static boolean isUnactivated(ToolExecutionRequest request, List<ToolSpecification> lazySpecs, List<ToolSpecification> activeSpecs) {
        String name = request.name();
        if (name == null || DiscoverToolsTool.NAME.equals(name) || lazySpecs == null || lazySpecs.isEmpty()) {
            return false;
        }
        boolean managed = false;
        for (ToolSpecification spec : lazySpecs) {
            if (name.equals(spec.name())) {
                managed = true;
                break;
            }
        }
        if (!managed) {
            return false;
        }
        for (ToolSpecification spec : activeSpecs) {
            if (name.equals(spec.name())) {
                return false;
            }
        }
        return true;
    }

    /** Counts one refused un-activated call. */
    static void countRefusal(String source) {
        Metrics.globalRegistry.counter("eddi.llm.lazy_tools.refused", "source", source != null ? source : "unknown").increment();
    }

    /** Counts one discover_tools activation round. */
    static void countActivation(int activated) {
        Metrics.globalRegistry.counter("eddi.llm.lazy_tools.activated").increment(activated);
    }

    // ─── Retention: activated names in conversation memory ───

    static String retention(LlmConfiguration.Task task) {
        String configured = task.getLazyRetention();
        if (configured != null && RETENTION_CONVERSATION.equalsIgnoreCase(configured.strip())) {
            return RETENTION_CONVERSATION;
        }
        if (configured != null && !RETENTION_TURN.equalsIgnoreCase(configured.strip())) {
            LOGGER.warnf("Unknown lazyRetention '%s' on LLM task '%s'; using 'turn'", configured, task.getId());
        }
        return RETENTION_TURN;
    }

    static String activatedKey(LlmConfiguration.Task task) {
        return ACTIVATED_KEY_PREFIX + (task.getId() != null ? task.getId() : "default");
    }

    /**
     * Records the lazily-managed names now active on the current step, so a later
     * run in this turn (a cascade step, a resume) — and, with
     * {@code lazyRetention: conversation}, a later turn — starts with them.
     */
    static void remember(IConversationMemory memory, LlmConfiguration.Task task, List<ToolSpecification> lazySpecs,
                         List<ToolSpecification> activeSpecs) {
        if (memory == null || memory.getCurrentStep() == null || lazySpecs == null || lazySpecs.isEmpty()) {
            return;
        }
        Set<String> managed = new HashSet<>();
        for (ToolSpecification spec : lazySpecs) {
            managed.add(spec.name());
        }
        List<String> names = new ArrayList<>();
        for (ToolSpecification spec : activeSpecs) {
            if (managed.contains(spec.name()) && !DiscoverToolsTool.NAME.equals(spec.name())) {
                names.add(spec.name());
            }
        }
        memory.getCurrentStep().storeData(new Data<>(activatedKey(task), names));
    }

    /**
     * Adds to {@code activeSpecs} the lazily-managed tools activated earlier in
     * this turn and, with {@code lazyRetention: conversation}, in the
     * conversation's latest earlier turn — at most {@code maxToolsInContext}
     * carried from earlier turns, the most recently activated kept. Names no longer
     * in the catalog are skipped.
     */
    static void reactivate(IConversationMemory memory, LlmConfiguration.Task task, List<ToolSpecification> lazySpecs,
                           List<ToolSpecification> activeSpecs) {
        if (memory == null || lazySpecs == null || lazySpecs.isEmpty()) {
            return;
        }
        String key = activatedKey(task);
        List<String> names = new ArrayList<>();
        if (memory.getCurrentStep() != null) {
            names.addAll(namesOf(memory.getCurrentStep().getLatestData(key)));
        }
        if (names.isEmpty() && RETENTION_CONVERSATION.equals(retention(task)) && memory.getPreviousSteps() != null) {
            List<String> carried = namesOf(memory.getPreviousSteps().getLatestData(key));
            int cap = task.getMaxToolsInContext() > 0 ? task.getMaxToolsInContext() : 20;
            names.addAll(carried.size() > cap ? carried.subList(carried.size() - cap, carried.size()) : carried);
        }
        if (names.isEmpty()) {
            return;
        }
        Set<String> wanted = new HashSet<>(names);
        Set<String> active = new HashSet<>();
        for (ToolSpecification spec : activeSpecs) {
            active.add(spec.name());
        }
        for (ToolSpecification spec : lazySpecs) {
            if (wanted.contains(spec.name()) && active.add(spec.name())) {
                activeSpecs.add(spec);
            }
        }
    }

    private static List<String> namesOf(IData<Object> data) {
        if (data == null || !(data.getResult() instanceof List<?> list)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                names.add(o.toString());
            }
        }
        return names;
    }
}
