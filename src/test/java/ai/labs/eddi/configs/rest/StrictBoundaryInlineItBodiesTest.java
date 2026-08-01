/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The integration tests do not only POST fixture files — most of them build the
 * configuration JSON inline, as Java text blocks. Those bodies cross exactly
 * the same {@link StrictConfigurationBodyInterceptor} boundary, and an unknown
 * key in one is the same 400.
 * <p>
 * This sweep exists because fixing the fixture files was not enough, twice
 * over. The outer {@code type} on {@code outputs[]} and the dictionary
 * {@code language} key (the model declares {@code lang}) were each removed from
 * every {@code .json} fixture and then turned up again, hardcoded, in five IT
 * classes — invisible to a sweep that only reads files. Each round trip cost a
 * full CI run to discover a defect the unit suite could have named in seconds.
 * <p>
 * It works by pairing each {@code String NAME = """ … """} text block with the
 * {@code createResource(NAME, "/somestore/something")} call that posts it,
 * which is what tells us the model to validate against. Coverage is reported
 * rather than assumed: bodies that could not be paired or parsed are printed,
 * so a silently empty sweep cannot masquerade as a passing one.
 */
class StrictBoundaryInlineItBodiesTest {

    private static final Path IT_DIR = Path.of("src", "test", "java", "ai", "labs", "eddi", "integration");

    /** REST path fragment → the model the resource deserializes into. */
    private static final Map<String, Class<?>> BY_ENDPOINT = Map.of(
            "/dictionarystore/dictionaries", DictionaryConfiguration.class,
            "/rulestore/rulesets", RuleSetConfiguration.class,
            "/outputstore/outputsets", OutputConfigurationSet.class,
            "/propertysetterstore/propertysetters", PropertySetterConfiguration.class,
            "/workflowstore/workflows", WorkflowConfiguration.class,
            "/agentstore/agents", AgentConfiguration.class,
            "/apicallstore/apicalls", ApiCallsConfiguration.class,
            "/llmstore/llms", LlmConfiguration.class,
            "/mcpcallstore/mcpcalls", McpCallsConfiguration.class,
            "/ragstore/rags", RagConfiguration.class);

    /** Endpoints outside the 10-entry {@link Map#of} limit above. */
    private static final Map<String, Class<?>> BY_ENDPOINT_EXTRA = Map.of(
            "/parserstore/parsers", ParserConfiguration.class);

    /**
     * {@code String name = """ … """} and the {@code String.format("""…""", …)}
     * form.
     */
    private static final Pattern TEXT_BLOCK = Pattern.compile(
            "String\\s+(\\w+)\\s*=\\s*(?:String\\.format\\(\\s*)?\"\"\"(.*?)\"\"\"", Pattern.DOTALL);

    /** {@code createResource(body, "/somestore/something")}. */
    private static final Pattern CREATE_RESOURCE = Pattern.compile(
            "createResource\\(\\s*(\\w+)\\s*,\\s*\"([^\"]+)\"");

    private static ObjectMapper strictMapper() {
        return SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false)
                .copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static Class<?> modelForEndpoint(String endpoint) {
        for (var e : BY_ENDPOINT.entrySet()) {
            if (endpoint.contains(e.getKey())) {
                return e.getValue();
            }
        }
        for (var e : BY_ENDPOINT_EXTRA.entrySet()) {
            if (endpoint.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    @Test
    @DisplayName("no config built inline by an IT is rejected by the strict write boundary")
    void inlineItBodiesPassTheStrictBoundary() throws IOException {
        var mapper = strictMapper();
        var failures = new LinkedHashMap<String, String>();
        var checked = new ArrayList<String>();
        var unpaired = new ArrayList<String>();

        assertTrue(Files.isDirectory(IT_DIR), "integration test sources moved — this sweep guards nothing: " + IT_DIR);

        try (Stream<Path> walk = Files.walk(IT_DIR)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, String> bodies = textBlocks(source);
                if (bodies.isEmpty()) {
                    continue;
                }

                Matcher posts = CREATE_RESOURCE.matcher(source);
                while (posts.find()) {
                    String variable = posts.group(1);
                    String body = bodies.get(variable);
                    Class<?> model = modelForEndpoint(posts.group(2));
                    String label = file.getFileName() + " → " + variable + " → " + posts.group(2);

                    if (body == null || model == null) {
                        unpaired.add(label);
                        continue;
                    }
                    checked.add(label);
                    parseStrictly(mapper, label, body, model, failures);
                }
            }
        }

        assertFalse(checked.isEmpty(), "no inline body could be paired with a createResource call — the ITs' "
                + "shape must have changed, so this sweep is no longer guarding anything");
        System.out.printf("inline IT body sweep: %d bodies checked, %d unpaired%n%s%n",
                checked.size(), unpaired.size(), unpaired.isEmpty() ? "" : "  unpaired: " + unpaired);

        assertTrue(failures.isEmpty(), () -> "configs built inline by integration tests that the strict "
                + "boundary rejects — each is a 400 that fails the IT before it asserts anything:\n"
                + failures.entrySet().stream()
                        .map(e -> "  " + e.getKey() + "\n      " + e.getValue())
                        .reduce("", (a, b) -> a + b + "\n"));
    }

    /**
     * Substitutes the {@code %s} placeholders of a {@code String.format} body with
     * a syntactically valid resource URI, so the body parses as the JSON it becomes
     * at runtime rather than failing on the placeholder.
     */
    private static void parseStrictly(ObjectMapper mapper, String label, String body, Class<?> model,
                                      Map<String, String> failures) {
        String resolved = body.replace("%s", "eddi://ai.labs.parser/parserstore/parsers/aaa000000000000000000001?version=1");
        try {
            mapper.readValue(resolved, model);
        } catch (UnrecognizedPropertyException e) {
            failures.put(label, "'%s' is not a known field of %s — known: %s".formatted(
                    e.getPropertyName(),
                    e.getReferringClass() == null ? model.getSimpleName() : e.getReferringClass().getSimpleName(),
                    e.getKnownPropertyIds()));
        } catch (IOException e) {
            // Not valid JSON once the placeholders are filled in — a different concern
            // (and often an artefact of how the literal is assembled), not an unknown
            // key. The interceptor only rejects the latter.
        }
    }

    private static Map<String, String> textBlocks(String source) {
        var blocks = new LinkedHashMap<String, String>();
        Matcher m = TEXT_BLOCK.matcher(source);
        while (m.find()) {
            blocks.put(m.group(1), m.group(2));
        }
        return blocks;
    }
}
