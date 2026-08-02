/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.tools.ToolNameResolver;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns tool <em>objects</em> (beans carrying {@code @Tool}-annotated methods)
 * into the specs / executors / provenance / canonical-name components the tool
 * registry needs (R2 step 2). Extracted verbatim from the reflection loop in
 * {@code AgentOrchestrator#buildToolSetup} as a pure move — no behavior change.
 * <p>
 * Note for the rewiring step: {@link Reflected} carries four components, but
 * {@link ai.labs.eddi.modules.llm.tools.spi.ToolContribution} has no slot for
 * {@code toolCanonicalNames}, so the providers that call this drop it.
 * Canonical names are what let the executor boundary price a call and pick its
 * cache TTL under the configured slug rather than the dispatch name, so the SPI
 * needs a fifth component (or the caller needs to collect these separately)
 * before {@code buildToolSetup} can source built-ins through providers.
 * <p>
 * Exists because the object-producing tool sources (built-ins, dynamic-agent
 * tools, user memory, conversation recall, attachments) are shaped differently
 * from the externally-discovered ones (http/mcp/a2a): the latter arrive as
 * specs + executors already, while the former arrive as beans that must be
 * reflected over. {@code buildToolSetup} did that reflection in one shared
 * loop, so extracting those five sources into {@code ToolSourceProvider}s —
 * whose contract is specs + executors — needs exactly one shared copy of the
 * loop rather than five duplicates.
 */
final class ToolObjectReflector {

    private ToolObjectReflector() {
        // static utility class
    }

    /**
     * The registry contributions of one batch of tool objects.
     *
     * @param specs
     *            every {@code @Tool} method's specification, in object order
     * @param executors
     *            dispatch name → executor bound to the owning object instance
     * @param toolSources
     *            dispatch name → provenance tag, from {@link #sourceForBuiltInTool}
     * @param toolCanonicalNames
     *            dispatch name → configuration slug
     *            ({@code searchWeb → websearch}), so the executor boundary prices a
     *            call and picks its cache TTL under the token the agent designer
     *            actually configured
     */
    record Reflected(List<ToolSpecification> specs, Map<String, ToolExecutor> executors,
            Map<String, String> toolSources, Map<String, String> toolCanonicalNames) {
    }

    /**
     * Reflects over every {@code @Tool}-annotated method of every supplied object.
     * <p>
     * CDI proxies do not carry the annotations, so each object's class is unwrapped
     * to the actual bean class first — {@code tool.getClass()} would be
     * {@code CalculatorTool_ClientProxy} under CDI, which no
     * {@link ToolNameResolver} case matches, and every built-in would silently fall
     * back to its dispatch name.
     */
    static Reflected reflect(List<Object> toolObjects) {
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> toolExecutors = new HashMap<>();
        Map<String, String> toolSources = new HashMap<>();
        Map<String, String> toolCanonicalNames = new HashMap<>();

        for (Object tool : toolObjects) {
            // CDI proxies don't carry @Tool annotations — resolve to actual bean class
            Class<?> toolClass = unwrapProxy(tool.getClass());

            // Resolved from the UNWRAPPED class on purpose: tool.getClass() would be
            // "CalculatorTool_ClientProxy" under CDI, which no resolver case matches, and
            // every built-in would silently fall back to its dispatch name again.
            String canonicalToolName = ToolNameResolver.canonicalForClass(toolClass.getSimpleName());

            var specs = ToolSpecifications.toolSpecificationsFrom(toolClass);
            toolSpecs.addAll(specs);

            // Find methods annotated with @Tool and map them to executors
            for (Method method : toolClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    dev.langchain4j.agent.tool.Tool toolAnnotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                    String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                    toolExecutors.put(toolName, new DefaultToolExecutor(tool, method));
                    toolSources.put(toolName, sourceForBuiltInTool(tool));
                    toolCanonicalNames.put(toolName, canonicalToolName != null ? canonicalToolName : toolName);
                }
            }
        }

        return new Reflected(toolSpecs, toolExecutors, toolSources, toolCanonicalNames);
    }

    /** The actual bean class behind a possible CDI proxy. */
    static Class<?> unwrapProxy(Class<?> toolClass) {
        if (toolClass.getName().contains("_ClientProxy") || toolClass.getName().contains("$$")) {
            return toolClass.getSuperclass();
        }
        return toolClass;
    }

    /**
     * Provenance tag for one built-in tool object, so the approval gate can match
     * qualified {@code source:name} patterns.
     */
    static String sourceForBuiltInTool(Object tool) {
        Class<?> c = unwrapProxy(tool.getClass());
        String simple = c.getSimpleName();
        return switch (simple) {
            case "UserMemoryTool" -> "memory";
            case "ConversationRecallTool" -> "recall";
            case "CreateSubAgentTool", "ConverseWithAgentTool", "FindAgentsByCapabilityTool", "TeardownAgentTool" -> "dynamic";
            default -> "builtin";
        };
    }
}
