/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import jakarta.inject.Provider;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Workflow step types across the v6 rename, checked against the extension
 * registry 6.x actually builds rather than a list written down here.
 *
 * <p>
 * An EDDI 5 workflow names its LLM step {@code eddi://ai.labs.langchain}. The
 * URI rewrites of the rename migration end in a slash, so they never matched
 * that bare value, and every agent with an LLM step stayed in ERROR with
 * {@code Extension 'ai.labs.langchain' not found}. The registry is read by
 * running every bootstrap module's registration against an empty map, so a
 * future rename, a new alias or a removed one changes what this test expects
 * without anyone editing it.
 * </p>
 */
@DisplayName("V6RenameMigration — workflow step types resolve to registered extensions")
class V6RenameMigrationStepTypeTest {

    /**
     * Every step type found in a production EDDI 5.5.1 database — the workflow
     * collection and its history agreed on this set.
     */
    private static final List<String> V5_STEP_TYPES = List.of("eddi://ai.labs.parser", "eddi://ai.labs.behavior",
            "eddi://ai.labs.property", "eddi://ai.labs.httpcalls", "eddi://ai.labs.langchain", "eddi://ai.labs.output",
            "eddi://ai.labs.templating");

    private static Set<String> registeredExtensions;
    private static Map<String, Provider<ILifecycleTask>> registry;

    @BeforeAll
    static void readRegistry() throws Exception {
        registry = buildRegistry();
        registeredExtensions = new TreeSet<>(registry.keySet());
    }

    /**
     * Runs every {@code *Module} that takes the {@link LifecycleExtensions} map,
     * exactly as CDI would: construct, then call its {@code configure()}.
     */
    private static Map<String, Provider<ILifecycleTask>> buildRegistry() throws Exception {
        Map<String, Provider<ILifecycleTask>> registry = new HashMap<>();
        Path classes = Path.of(ILifecycleTask.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Path> modules;
        try (Stream<Path> walk = Files.walk(classes.resolve("ai/labs/eddi"))) {
            modules = walk.filter(path -> path.getFileName().toString().endsWith("Module.class")).toList();
        }
        int registrars = 0;
        for (Path module : modules) {
            String className = classes.relativize(module).toString().replace('\\', '/').replace('/', '.').replaceAll("\\.class$", "");
            Class<?> type = Class.forName(className);
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (!takesRegistry(constructor)) {
                    continue;
                }
                Object[] arguments = new Object[constructor.getParameterCount()];
                Parameter[] parameters = constructor.getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    if (parameters[i].isAnnotationPresent(LifecycleExtensions.class)) {
                        arguments[i] = registry;
                    } else if (Map.class.isAssignableFrom(parameters[i].getType())) {
                        arguments[i] = new HashMap<>();
                    } else {
                        arguments[i] = mock(parameters[i].getType());
                    }
                }
                constructor.setAccessible(true);
                Object instance = constructor.newInstance(arguments);
                Method configure = type.getDeclaredMethod("configure");
                configure.setAccessible(true);
                configure.invoke(instance);
                registrars++;
            }
        }
        // Guards against the scan silently finding nothing, which would make every
        // assertion below vacuous.
        assertTrue(registrars >= 9, "expected the bootstrap modules to be found, found " + registrars);
        return registry;
    }

    private static boolean takesRegistry(Constructor<?> constructor) {
        for (Parameter parameter : constructor.getParameters()) {
            if (parameter.isAnnotationPresent(LifecycleExtensions.class)) {
                return true;
            }
        }
        return false;
    }

    /** What {@code WorkflowStoreClientLibrary} looks a step's type up by. */
    private static String extensionId(String stepType) {
        return URI.create(stepType).getHost();
    }

    private static Document v5Workflow(String field, List<String> stepTypes) {
        List<Document> steps = new ArrayList<>();
        for (String type : stepTypes) {
            steps.add(new Document("type", type).append("config", new Document("uri", "eddi://ai.labs.x/xstore/xs/1?version=1")));
        }
        return new Document("_id", "0000000000000000000000c1").append(field, steps);
    }

    @Test
    @DisplayName("every v5 step type resolves to a registered extension once migrated")
    void everyV5StepTypeResolvesAfterMigration() {
        for (String field : List.of("packageExtensions", "workflowSteps")) {
            Document workflow = v5Workflow(field, V5_STEP_TYPES);

            V6RenameMigration.rewriteStepTypes(workflow);

            for (Object step : workflow.getList(field, Object.class)) {
                String type = ((Document) step).getString("type");
                assertTrue(registeredExtensions.contains(extensionId(type)),
                        () -> "step type " + type + " (under " + field + ") is not a registered extension: " + registeredExtensions);
            }
        }
    }

    /**
     * A renamed type may stay registered — as an alias for data the migration never
     * reached — but only as an alias of the very task it is renamed to. Anything
     * else would make the rewrite change which task a step runs.
     */
    @Test
    @DisplayName("every rename points at a registered extension; a v5 type still registered is an alias of the same task")
    void renameTableAgreesWithTheRegistry() {
        assertFalse(V6RenameMigration.STEP_TYPE_REWRITES.isEmpty());
        V6RenameMigration.STEP_TYPE_REWRITES.forEach((v5, v6) -> {
            assertTrue(registeredExtensions.contains(extensionId(v6)), () -> v6 + " is not registered: " + registeredExtensions);
            if (registeredExtensions.contains(extensionId(v5))) {
                assertSame(registry.get(extensionId(v6)), registry.get(extensionId(v5)),
                        () -> v5 + " is registered to a different task than " + v6 + ", so the rewrite would change what runs");
            }
        });
    }

    @Test
    @DisplayName("a workflow the migration never reached still resolves: the v5 LLM step type is an alias")
    void unmigratedLlmStepTypeStillResolves() {
        assertTrue(registeredExtensions.contains(extensionId("eddi://ai.labs.langchain")),
                "a database migrated by 6.0-6.4 still holds eddi://ai.labs.langchain in its workflows");
    }

    @Test
    @DisplayName("the LLM step is renamed; behavior and httpcalls, still registered, are not")
    void onlyTheLlmStepIsRenamed() {
        Document workflow = v5Workflow("packageExtensions",
                List.of("eddi://ai.labs.langchain", "eddi://ai.labs.behavior", "eddi://ai.labs.httpcalls"));

        assertTrue(V6RenameMigration.rewriteStepTypes(workflow));

        List<String> types = workflow.getList("packageExtensions", Document.class).stream().map(step -> step.getString("type")).toList();
        assertEquals(List.of("eddi://ai.labs.llm", "eddi://ai.labs.behavior", "eddi://ai.labs.httpcalls"), types);
    }

    @Test
    @DisplayName("a value that merely starts with a renamed type is left alone")
    void substringIsNotRewritten() {
        Document workflow = v5Workflow("packageExtensions", List.of("eddi://ai.labs.langchainX", "eddi://ai.labs.langchain/"));

        assertFalse(V6RenameMigration.rewriteStepTypes(workflow));

        List<String> types = workflow.getList("packageExtensions", Document.class).stream().map(step -> step.getString("type")).toList();
        assertEquals(List.of("eddi://ai.labs.langchainX", "eddi://ai.labs.langchain/"), types);
    }

    @Test
    @DisplayName("config URIs are still rewritten by the URI pass, with the trailing slash that keeps step types out of it")
    void configUrisStillRewritten() {
        var migration = new V6RenameMigration(null, null, true);

        assertEquals("eddi://ai.labs.llm/llmstore/llms/abc?version=1",
                migration.rewriteUriString("eddi://ai.labs.langchain/langchainstore/langchains/abc?version=1"));
        assertEquals("eddi://ai.labs.rules/rulestore/rulesets/abc?version=1",
                migration.rewriteUriString("eddi://ai.labs.behavior/behaviorstore/behaviorsets/abc?version=1"));
        assertEquals("eddi://ai.labs.behavior", migration.rewriteUriString("eddi://ai.labs.behavior"));
        assertEquals("eddi://ai.labs.langchain", migration.rewriteUriString("eddi://ai.labs.langchain"),
                "the URI pass alone does not touch a bare step type — rewriteStepTypes does");
    }
}
