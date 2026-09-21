/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules.mongo;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.DocumentBuilder;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.rules.impl.RuleDeserialization;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.ISecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every ruleset EDDI itself ships — and the one its setup wizard generates —
 * must pass {@code RuleSetStore.validate()}.
 * <p>
 * Hoisting the structural rule-condition checks from deploy time to save time
 * is a real improvement: the author hears about a broken condition on save
 * instead of on a failed deploy. But it also means any ruleset that was
 * accepted before and is now refused turns into a 400, and EDDI ships rulesets
 * of its own — as IT fixtures, as the documented rule-based reference config,
 * and as the config the agent setup wizard builds and posts through its
 * internal REST clients.
 * <p>
 * Those all only fail in CI or in production. This runs in the plain unit
 * suite.
 * <p>
 * It complements {@code StrictBoundaryShippedConfigsTest}, which checks the
 * same documents for unknown <em>field names</em>. Field-name validity says
 * nothing about semantic validity: {@code tests/rules/createRules.json} parses
 * perfectly and still fails here, because its {@code occurrence} carries the
 * obsolete {@code maxOccurrence: "ever"} key instead of a
 * {@code maxTimesOccurred} bound — the key was silently ignored for years,
 * leaving the condition unbounded.
 */
class RuleSetStoreShippedRulesetsTest {

    /** Roots holding rulesets that EDDI ships or tests against. */
    private static final List<Path> ROOTS = List.of(
            Path.of("src", "test", "resources", "tests"),
            Path.of("docs", "agent-configs"));

    private RuleSetStore ruleSetStore;
    private ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        var storageFactory = mock(IResourceStorageFactory.class);
        when(storageFactory.create(eq("rulesets"), any(), eq(RuleSetConfiguration.class), any(String[].class)))
                .thenReturn(mock(IResourceStorage.class));

        objectMapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
        var documentBuilder = new DocumentBuilder(new JsonSerialization(objectMapper));
        // A REAL expression provider, not a mock. A mocked one parses every
        // 'expressions' config to empty, so every inputmatcher in every ruleset fails
        // validation and the sweep "finds" bugs that do not exist — it reported the
        // setup wizard's own `expressions: "*"` as an empty matcher. The provider is
        // cheap to build for real, and only the real one makes this sweep mean
        // anything.
        var expressionProvider = new ExpressionProvider(new ExpressionFactory());
        var ruleDeserialization = new RuleDeserialization(objectMapper, expressionProvider,
                new JsonSerialization(objectMapper), mock(IMemoryItemConverter.class),
                mock(CapabilityRegistryService.class), mock(ITemplatingEngine.class));

        ruleSetStore = new RuleSetStore(storageFactory, documentBuilder, ruleDeserialization);
    }

    @Test
    @DisplayName("the setup wizard's own generated ruleset passes save-time validation")
    void wizardGeneratedRulesetIsAccepted() {
        // The wizard posts this through IRestRuleSetStore.createRuleSet, so a ruleset
        // it
        // cannot save is a 400 on /administration/agents/setup — the wizard failing on
        // its own output, with the cause buried in a generic "Failed to set up agent".
        var wizard = new AgentSetupService(mock(IRestInterfaceFactory.class), mock(IRestAgentAdministration.class),
                mock(ISecretProvider.class), "http://localhost:11434");

        assertDoesNotThrow(() -> ruleSetStore.validate(wizard.createBehaviorConfig()),
                "the agent setup wizard generates a ruleset its own store refuses to save");
    }

    @Test
    @DisplayName("no ruleset EDDI ships is refused at save time")
    void shippedRulesetsAreAccepted() throws IOException {
        var failures = new LinkedHashMap<String, String>();
        var checked = new ArrayList<String>();

        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().endsWith(".json"))
                        .toList()) {
                    RuleSetConfiguration config = readRuleSet(p);
                    if (config == null) {
                        continue;
                    }
                    checked.add(p.toString());
                    try {
                        ruleSetStore.validate(config);
                    } catch (RuntimeException e) {
                        failures.put(p.toString(), e.getMessage());
                    }
                }
            }
        }

        assertFalse(checked.isEmpty(), "sweep found no rulesets — the roots or the "
                + "behaviorGroups shape must have moved, so this test guards nothing");
        System.out.printf("save-time ruleset sweep: %d rulesets checked%n", checked.size());

        assertTrue(failures.isEmpty(), () -> "rulesets EDDI ships that RuleSetStore.validate() "
                + "now refuses — each would be a 400 on save:\n"
                + failures.entrySet().stream()
                        .map(e -> "  " + e.getKey() + "\n      " + e.getValue())
                        .reduce("", (a, b) -> a + b + "\n"));
    }

    /**
     * Returns the parsed ruleset, or null when the file is not one. Identified by
     * content ({@code behaviorGroups}) rather than by filename, so a ruleset that
     * does not follow the {@code .behavior.json} convention is still covered.
     */
    private RuleSetConfiguration readRuleSet(Path p) throws IOException {
        String body = Files.readString(p, StandardCharsets.UTF_8);
        if (!body.contains("\"behaviorGroups\"")) {
            return null;
        }
        try {
            return objectMapper.readValue(body, RuleSetConfiguration.class);
        } catch (IOException e) {
            // Not a ruleset document after all, or malformed — a different defect, and
            // StrictBoundaryShippedConfigsTest is what reports parse-level problems.
            return null;
        }
    }
}
