/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
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
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every configuration document EDDI itself ships must survive
 * {@link StrictConfigurationBodyInterceptor}.
 * <p>
 * The interceptor rejects unknown keys in inbound configuration bodies, which
 * is the right call for a config-driven engine — a typo'd key is a behavioural
 * bug, not something to discard silently. But it turns "field the model does
 * not declare" from a harmless no-op into a hard 400 at the HTTP boundary, and
 * that boundary is crossed by more than hand-written client requests:
 * <ul>
 * <li>the integration tests POST these very files;</li>
 * <li>ZIP import replays configuration documents through the same REST
 * resources;</li>
 * <li>EDDI-Manager does GET → edit → PUT round-trips.</li>
 * </ul>
 * Those paths only fail in CI (ITs need Docker) or in production, which is how
 * two long-standing fixture typos — {@code language} where the model declares
 * {@code lang}, and an outer {@code type} on {@code outputs[]} items that
 * {@code OutputConfiguration.Output} never declared — reached CI as eight
 * opaque "expected 201 but was 400" IT failures with no field named anywhere in
 * the log. This test runs in the plain unit suite and names the offending key
 * in seconds.
 * <p>
 * It sweeps the tree rather than listing files, so a fixture added later is
 * covered without anyone remembering to extend a list. Coverage is reported
 * explicitly rather than assumed: unmapped files are printed, so "the sweep
 * passed" can never quietly mean "the sweep looked at nothing".
 */
class StrictBoundaryShippedConfigsTest {

    /** Roots holding configuration documents that EDDI ships or tests against. */
    private static final List<Path> ROOTS = List.of(
            Path.of("src", "test", "resources", "tests"),
            Path.of("docs", "agent-configs"));

    /**
     * Filename convention → model, mirroring the extensions
     * {@code AbstractBackupService} uses for ZIP import. Legacy extension names are
     * deliberate: the files are named {@code behavior} / {@code httpcalls} /
     * {@code langchain} while the v6 URIs say rules / apicalls / llm.
     */
    private static final Map<String, Class<?>> BY_SUFFIX = Map.of(
            ".agent.json", AgentConfiguration.class,
            ".workflow.json", WorkflowConfiguration.class,
            ".behavior.json", RuleSetConfiguration.class,
            ".httpcalls.json", ApiCallsConfiguration.class,
            ".langchain.json", LlmConfiguration.class,
            ".output.json", OutputConfigurationSet.class,
            ".property.json", PropertySetterConfiguration.class,
            ".dictionary.json", DictionaryConfiguration.class);

    /**
     * Loose fixtures (no {@code .<type>.json} suffix), matched on the file name
     * stem. Ordered longest-first at match time so {@code simpleDictionary} does
     * not lose to a shorter, more generic key.
     */
    private static final Map<String, Class<?>> BY_STEM = Map.of(
            "dictionary", DictionaryConfiguration.class,
            "rules", RuleSetConfiguration.class,
            "output", OutputConfigurationSet.class);

    /**
     * JSON Patch documents are arrays of operations, not configuration bodies —
     * they reach the resource as a patch type, so the interceptor never
     * model-parses them.
     */
    private static boolean isPatchDocument(Path p) {
        return p.getFileName().toString().startsWith("patch");
    }

    private static ObjectMapper strictMapper() {
        // Built exactly as the interceptor builds it: the shared recipe, copied, plus
        // FAIL_ON_UNKNOWN_PROPERTIES. If the interceptor changes how it derives its
        // mapper, this must change with it or the test guards a boundary that moved.
        return SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false)
                .copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static Class<?> modelFor(Path path) {
        String name = path.getFileName().toString();
        if (isPatchDocument(path) || name.endsWith(".descriptor.json")) {
            return null;
        }
        for (var e : BY_SUFFIX.entrySet()) {
            if (name.endsWith(e.getKey())) {
                return e.getValue();
            }
        }
        String stem = name.substring(0, name.length() - ".json".length()).toLowerCase();
        // Longest key first: "dictionary" must win over a shorter accidental match.
        return BY_STEM.entrySet().stream()
                .filter(e -> stem.contains(e.getKey()))
                .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    @Test
    @DisplayName("No config EDDI ships is rejected by the strict write boundary")
    void shippedConfigsPassTheStrictBoundary() throws IOException {
        var mapper = strictMapper();
        var failures = new LinkedHashMap<String, String>();
        var checked = new ArrayList<String>();
        var skipped = new ArrayList<String>();

        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".json")) {
                        Class<?> model = modelFor(p);
                        if (model == null) {
                            skipped.add(root.relativize(p).toString());
                            continue;
                        }
                        checked.add(root.relativize(p).toString());
                        check(mapper, p.toString(), Files.readString(p, StandardCharsets.UTF_8), model, failures);
                    } else if (name.endsWith(".zip")) {
                        // ZIP fixtures are imported wholesale by RestImportService, so a
                        // single unknown key inside one aborts the entire agent import.
                        // Sweeping only loose .json files left that path unguarded.
                        checkZip(mapper, root, p, checked, skipped, failures);
                    }
                }
            }
        }

        // Honest coverage: assert the sweep found work, and say what it left alone.
        assertFalse(checked.isEmpty(), "sweep matched no configuration documents — the "
                + "filename conventions or the roots must have moved, so this test is "
                + "no longer guarding anything");
        System.out.printf("strict-boundary sweep: %d configs checked, %d skipped (patch/descriptor/unmapped)%n%s%n",
                checked.size(), skipped.size(), skipped.isEmpty() ? "" : "  skipped: " + skipped);

        assertTrue(failures.isEmpty(), () -> "configuration documents rejected by "
                + "StrictConfigurationBodyInterceptor — each would be a 400 at the REST boundary:\n"
                + failures.entrySet().stream()
                        .map(e -> "  " + e.getKey() + "\n      " + e.getValue())
                        .reduce("", (a, b) -> a + b + "\n"));
    }

    /** Strict-parses one document, recording the offending key on rejection. */
    private static void check(ObjectMapper mapper, String label, String body, Class<?> model,
                              Map<String, String> failures) {
        try {
            mapper.readValue(body, model);
        } catch (UnrecognizedPropertyException e) {
            failures.put(label, "'%s' is not a known field of %s — known: %s"
                    .formatted(e.getPropertyName(),
                            e.getReferringClass() == null ? model.getSimpleName() : e.getReferringClass().getSimpleName(),
                            e.getKnownPropertyIds()));
        } catch (IOException e) {
            // Malformed JSON or a wrong value type is a different defect and not what
            // the interceptor rejects; the regular reader still reports it.
        }
    }

    /**
     * Sweeps the configuration documents inside an agent ZIP, as import would read
     * them.
     */
    private static void checkZip(ObjectMapper mapper, Path root, Path zip, List<String> checked, List<String> skipped,
                                 Map<String, String> failures)
            throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            for (var entries = archive.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".json")) {
                    continue;
                }
                String label = root.relativize(zip) + "!" + entry.getName();
                Class<?> model = modelFor(Path.of(entry.getName()));
                if (model == null) {
                    skipped.add(label);
                    continue;
                }
                checked.add(label);
                try (var in = archive.getInputStream(entry)) {
                    check(mapper, label, new String(in.readAllBytes(), StandardCharsets.UTF_8), model, failures);
                }
            }
        }
    }
}
