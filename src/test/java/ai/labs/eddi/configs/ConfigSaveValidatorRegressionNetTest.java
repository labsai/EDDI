/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import ai.labs.eddi.configs.workflows.WorkflowConfigSaveValidator;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.modules.llm.bootstrap.LlmModule;
import ai.labs.eddi.modules.llm.impl.LlmConfigSaveValidator;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleProviders;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.ISecretProvider;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The regression net of the two save-time validators: everything EDDI ships —
 * the reference agent configs, every LLM or workflow example in the docs — and
 * everything it generates (the agent setup wizard, MCP {@code setup_agent} and
 * {@code create_api_agent}, the Operator bootstrap) passes the <em>strict</em>
 * checks an interactive save runs. A rule that refuses one of them refuses
 * something that works.
 * <p>
 * The setup paths are not run inside {@link LenientConfigWrite}: they write
 * through loopback REST clients (separate HTTP requests the thread-bound marker
 * cannot reach), so their configs must be valid by construction, and this test
 * keeps them so.
 */
@DisplayName("Save-time validators — shipped and generated configs pass")
class ConfigSaveValidatorRegressionNetTest {

    /** Every step type the engine registers (the @LifecycleExtensions keys). */
    private static final Predicate<String> REGISTERED_STEPS = Set.of("ai.labs.parser", "ai.labs.behavior", "ai.labs.rules",
            "ai.labs.property", "ai.labs.httpcalls", "ai.labs.apicalls", "ai.labs.llm", "ai.labs.langchain", "ai.labs.mcpcalls",
            "ai.labs.rag", "ai.labs.output", "ai.labs.templating")::contains;

    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*\\n(.*?)```", Pattern.DOTALL);

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("every LLM config and workflow under docs/ (fixtures and Markdown examples) passes the strict checks")
    void shippedConfigsPass() throws IOException {
        List<String> failures = new ArrayList<>();
        int[] checked = {0, 0};
        try (Stream<Path> files = Files.walk(Path.of("docs"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.toString().replace('\\', '/');
                if (name.contains("/changelog")) {
                    continue;
                }
                if (name.endsWith(".json")) {
                    check(name, Files.readString(file), failures, checked);
                } else if (name.endsWith(".md")) {
                    Matcher blocks = JSON_BLOCK.matcher(Files.readString(file));
                    while (blocks.find()) {
                        check(name + " (example at offset " + blocks.start() + ")", blocks.group(1), failures, checked);
                    }
                }
            }
        }
        assertTrue(checked[0] >= 10 && checked[1] >= 3, "the sweep found too few configs to mean anything: " + checked[0] + " LLM, "
                + checked[1] + " workflow");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private void check(String where, String json, List<String> failures, int[] checked) {
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (IOException notJson) {
            return; // an excerpt with "..." in it
        }
        if (node == null || !node.isObject()) {
            return;
        }
        try {
            if (isLlmConfig(node)) {
                checked[0]++;
                LlmConfigSaveValidator.findProblems(mapper.treeToValue(node, LlmConfiguration.class))
                        .forEach(problem -> failures.add(where + ": " + problem));
            }
            if (node.path("workflowSteps").isArray()) {
                checked[1]++;
                WorkflowConfigSaveValidator.findProblems(mapper.treeToValue(node, WorkflowConfiguration.class), REGISTERED_STEPS).stream()
                        .filter(problem -> !namesAPlaceholder(problem))
                        .forEach(problem -> failures.add(where + ": " + problem));
            }
        } catch (IOException | IllegalArgumentException notThisModel) {
            // Not an LLM or workflow document after all (another config's "tasks").
        }
    }

    /**
     * A docs example that writes the reference as a placeholder
     * ({@code {behaviorId}}, {@code <id>}, {@code …0002}) for the reader to fill
     * in: not a config anyone saves as it stands.
     */
    private static boolean namesAPlaceholder(String problem) {
        int start = problem.indexOf("config.uri '");
        if (start < 0) {
            return false;
        }
        String uri = problem.substring(start + "config.uri '".length(), Math.max(start, problem.indexOf('\'', start + "config.uri '".length())));
        return uri.contains("{") || uri.contains("<") || uri.contains("…");
    }

    private static boolean isLlmConfig(JsonNode node) {
        if (!node.path("tasks").isArray()) {
            return false;
        }
        for (JsonNode task : node.path("tasks")) {
            if (task.isObject() && (task.has("type") || task.has("parameters"))) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the LLM config the setup wizard generates passes for every provider it can name")
    void generatedLlmConfigsPass() {
        var setup = new AgentSetupService(mock(IRestInterfaceFactory.class), mock(IRestAgentAdministration.class), mock(ISecretProvider.class),
                "http://localhost:11434");
        Set<String> providers = new TreeSet<>(LlmModule.BUILT_IN_TYPES);
        OpenAiCompatibleProviders.all().forEach(provider -> providers.add(provider.id()));
        List<String> failures = new ArrayList<>();
        for (String provider : providers) {
            for (boolean tools : List.of(true, false)) {
                LlmConfiguration generated = setup.createLlmConfig(provider, AgentSetupService.defaultModelFor(provider), "${vault:llm-key}",
                        "You are helpful.", tools, null, "https://llm.example.com/v1", null, false, false, List.of());
                LlmConfigSaveValidator.findProblems(generated).forEach(problem -> failures.add(provider + ": " + problem));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    @DisplayName("the workflow the setup wizard generates passes, with and without its optional steps")
    void generatedWorkflowsPass() {
        var setup = new AgentSetupService(mock(IRestInterfaceFactory.class), mock(IRestAgentAdministration.class), mock(ISecretProvider.class),
                "http://localhost:11434");
        String parser = "eddi://ai.labs.parser/parserstore/parsers/000000000000000000000001?version=1";
        String behavior = "eddi://ai.labs.behavior/behaviorstore/behaviorsets/000000000000000000000002?version=1";
        String httpCalls = "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/000000000000000000000003?version=1";
        String mcpCalls = "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/000000000000000000000004?version=1";
        String llm = "eddi://ai.labs.llm/llmstore/llms/000000000000000000000005?version=1";
        String output = "eddi://ai.labs.output/outputstore/outputsets/000000000000000000000006?version=1";

        for (WorkflowConfiguration generated : List.of(
                setup.createWorkflowConfig(parser, behavior, List.of(httpCalls), List.of(mcpCalls), llm, output),
                setup.createWorkflowConfig(null, behavior, null, null, llm, null))) {
            List<String> problems = WorkflowConfigSaveValidator.findProblems(generated, REGISTERED_STEPS);
            assertTrue(problems.isEmpty(), problems.toString());
        }
    }
}
