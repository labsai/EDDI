/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.secrets.ISecretProvider;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The setup wizard must be able to save the configs it generates.
 * <p>
 * {@code AgentSetupService} does not write to the stores directly: it builds
 * each configuration as an object and posts it through the internal typed REST
 * clients ({@code createParser}, {@code createRuleSet}, {@code createLlm},
 * {@code createOutputSet}, {@code createWorkflow}, {@code createAgent}). Those
 * are real HTTP calls to EDDI's own REST resources, so every generated config
 * crosses {@link ai.labs.eddi.configs.rest.StrictConfigurationBodyInterceptor}
 * — which now rejects unknown keys.
 * <p>
 * If any generated config carries a key its own model cannot read back, the
 * wizard 400s on its own output and the cause is swallowed by the generic
 * {@code "Failed to set up agent: …"} wrapper, surfacing to CI as nothing more
 * informative than {@code Expected status code <201> but was <400>}. This
 * asserts the round trip per generated config, in the plain unit suite, and
 * names the key.
 */
class SetupWizardConfigsPassStrictBoundaryTest {

    private AgentSetupService wizard;
    private ObjectMapper restMapper;
    private ObjectMapper strictMapper;

    @BeforeEach
    void setUp() {
        wizard = new AgentSetupService(mock(IRestInterfaceFactory.class), mock(IRestAgentAdministration.class),
                mock(ISecretProvider.class), "http://localhost:11434");
        restMapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
        strictMapper = restMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Test
    @DisplayName("every config the setup wizard generates survives the strict REST boundary")
    void generatedConfigsSurviveTheStrictBoundary() {
        // The same shapes the wizard posts for the IT's request: provider openai,
        // model gpt-4o-mini, tooling on, no MCP, with an intro message.
        var generated = new LinkedHashMap<String, Object>();
        generated.put("parser", wizard.createParserConfig());
        generated.put("behavior", wizard.createBehaviorConfig());
        generated.put("llm", wizard.createLlmConfig("openai", "gpt-4o-mini", "sk-test-not-real",
                "You are a test assistant.", true, null, null, null, false, false, List.of()));
        generated.put("output", wizard.createOutputConfig("Hello!"));
        generated.put("mcpCalls", wizard.createMcpCallsConfig("https://mcp.example.com/sse"));
        generated.put("workflow", wizard.createWorkflowConfig(
                "eddi://ai.labs.parser/parserstore/parsers/aaa000000000000000000001?version=1",
                "eddi://ai.labs.rules/rulestore/rulesets/aaa000000000000000000002?version=1",
                List.of(), List.of(),
                "eddi://ai.labs.llm/llmstore/llms/aaa000000000000000000003?version=1",
                "eddi://ai.labs.output/outputstore/outputsets/aaa000000000000000000004?version=1"));

        var failures = new LinkedHashMap<String, String>();
        generated.forEach((label, config) -> {
            if (config == null) {
                return;
            }
            try {
                String json = restMapper.writeValueAsString(config);
                strictMapper.readValue(json, config.getClass());
            } catch (UnrecognizedPropertyException e) {
                failures.put(label, "'%s' is not readable on %s — known: %s".formatted(
                        e.getPropertyName(), config.getClass().getSimpleName(), e.getKnownPropertyIds()));
            } catch (Exception e) {
                failures.put(label, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });

        assertTrue(failures.isEmpty(), () -> "the setup wizard generates configs it cannot save — "
                + "each is a 400 on POST /administration/agents/setup:\n"
                + failures.entrySet().stream()
                        .map(e -> "  " + e.getKey() + ": " + e.getValue())
                        .reduce("", (a, b) -> a + b + "\n"));
    }
}
