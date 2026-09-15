/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.templates.GroupTemplateService.TemplateManifest;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;
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

    /**
     * Preset group templates shipped on the classpath. Not plain configuration
     * bodies — each wraps a {@code manifest} and a {@code config} — so they need
     * their own reader, but the {@code config} half is a complete
     * {@link AgentGroupConfiguration} that {@code GroupTemplateService.instantiate}
     * hands straight to the store, i.e. it crosses the same strict boundary as any
     * other saved configuration.
     */
    private static final Path GROUP_TEMPLATES = Path.of("src", "main", "resources", "group-templates");

    /** Roots holding configuration documents that EDDI ships or tests against. */
    private static final List<Path> ROOTS = List.of(
            Path.of("src", "test", "resources", "tests"),
            Path.of("docs", "agent-configs"),
            GROUP_TEMPLATES);

    /**
     * Filename convention → model, mirroring the extensions
     * {@code AbstractBackupService} uses for ZIP import. Legacy extension names are
     * deliberate: the files are named {@code behavior} / {@code httpcalls} /
     * {@code langchain} while the v6 URIs say rules / apicalls / llm.
     */
    private static final Map<String, Class<?>> BY_SUFFIX = Map.ofEntries(
            Map.entry(".agent.json", AgentConfiguration.class),
            Map.entry(".workflow.json", WorkflowConfiguration.class),
            Map.entry(".behavior.json", RuleSetConfiguration.class),
            Map.entry(".httpcalls.json", ApiCallsConfiguration.class),
            Map.entry(".langchain.json", LlmConfiguration.class),
            Map.entry(".output.json", OutputConfigurationSet.class),
            Map.entry(".property.json", PropertySetterConfiguration.class),
            Map.entry(".dictionary.json", DictionaryConfiguration.class),
            // Declared for export by AbstractBackupService, so they travel in ZIPs and
            // cross the same strict boundary on import.
            Map.entry(".mcpcalls.json", McpCallsConfiguration.class),
            Map.entry(".rag.json", RagConfiguration.class),
            Map.entry(".snippet.json", PromptSnippet.class),
            // v5 filenames. RestImportService still accepts them, so they are live
            // input, not museum pieces — and leaving them unmapped is precisely how
            // WorkflowConfiguration came to alias a key that shipped in no release
            // while `packageExtensions`, the one 5.x actually persisted, went
            // unaliased: a v5 workflow deserialized to ZERO steps and the agent
            // deployed and answered nothing, silently.
            Map.entry(".bot.json", AgentConfiguration.class),
            Map.entry(".package.json", WorkflowConfiguration.class));

    /**
     * Files under a {@code useCases} tree that are deliberately not configuration
     * documents. Named one by one so the coverage assertion below can insist that
     * everything else there IS mapped.
     */
    private static final Set<String> NON_CONFIG_FIXTURES = Set.of("AgentDeployment.json");

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

    /**
     * Not a configuration body: descriptors carry their own model and never reach
     * the interceptor as a config, patch documents arrive as JSON Patch arrays, and
     * {@link #NON_CONFIG_FIXTURES} names the rest one by one.
     */
    private static boolean isDeliberatelyNotAConfig(Path p) {
        String name = p.getFileName().toString();
        return isPatchDocument(p) || name.endsWith(".descriptor.json") || NON_CONFIG_FIXTURES.contains(name);
    }

    /**
     * The v5-shaped corpus: the {@code useCases} tree holds the only complete
     * pre-v6 agent this repo keeps, which is exactly the input the backward
     * compatibility rule is about.
     */
    private static boolean isLegacyCorpus(Path p) {
        for (Path segment : p) {
            if ("useCases".equals(segment.toString())) {
                return true;
            }
        }
        return false;
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
        var unmappedLegacy = new ArrayList<String>();

        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String name = p.getFileName().toString();
                    if (root.equals(GROUP_TEMPLATES)) {
                        if (name.endsWith(".json")) {
                            checkGroupTemplate(mapper, name, p, checked, failures);
                        }
                        continue;
                    }
                    if (name.endsWith(".json")) {
                        Class<?> model = modelFor(p);
                        if (model == null) {
                            skipped.add(root.relativize(p).toString());
                            if (isLegacyCorpus(p) && !isDeliberatelyNotAConfig(p)) {
                                unmappedLegacy.add(root.relativize(p).toString());
                            }
                            continue;
                        }
                        checked.add(root.relativize(p).toString());
                        check(mapper, p.toString(), Files.readString(p, StandardCharsets.UTF_8), model, failures);
                    } else if (name.endsWith(".zip")) {
                        // ZIP fixtures are imported wholesale by RestImportService, so a
                        // single unknown key inside one aborts the entire agent import.
                        // Sweeping only loose .json files left that path unguarded.
                        checkZip(mapper, root, p, checked, skipped, unmappedLegacy, failures);
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

        // "checked is non-empty" was too weak to see the one corpus that mattered:
        // the v5 fixtures sat unmapped next to the sweep for as long as it existed,
        // so every v5 -> v6 alias miss was invisible to the whole unit suite. Nothing
        // under useCases may be silently unmapped — add it to BY_SUFFIX/BY_STEM, or
        // name it in NON_CONFIG_FIXTURES and say why.
        assertTrue(unmappedLegacy.isEmpty(), () -> "legacy/use-case fixtures matched no model, so the sweep "
                + "never opened them: " + unmappedLegacy);

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
     * Strict-parses one shipped group template, as
     * {@code GroupTemplateService.instantiate} unwraps it: the {@code manifest}
     * half against the service's own record, the {@code config} half against
     * {@link AgentGroupConfiguration}.
     * <p>
     * Note what the hazard actually is. {@code instantiate} reads the config
     * <em>leniently</em> — {@code objectMapper.treeToValue(...)} on the CDI mapper,
     * which {@code SerializationCustomizer} configures with
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} — and hands the resulting POJO
     * straight to the store from service code, never back through
     * {@link StrictConfigurationBodyInterceptor}. So an undeclared key here is not
     * a template nobody can instantiate; it is a template that instantiates fine
     * with that key silently dropped, leaving its author believing a setting is
     * active when it never reaches the group. This sweep is the only place that
     * says so.
     */
    private static void checkGroupTemplate(ObjectMapper mapper, String label, Path p, List<String> checked,
                                           Map<String, String> failures)
            throws IOException {
        JsonNode root = mapper.readTree(Files.readString(p, StandardCharsets.UTF_8));
        checked.add(label);

        // Structure before content. An absent half is a MissingNode whose toString()
        // is the empty string, and readValue("") throws MismatchedInputException —
        // which is an IOException, which check() deliberately swallows. Left to
        // itself this guard could therefore only ever fail on an unknown key inside a
        // node that happens to exist: a template missing its manifest or its config
        // outright would pass silently while still inflating the checked count.
        // Both halves are reported, not just the first.
        boolean structureOk = requireObject(root, "manifest", label, failures);
        structureOk &= requireObject(root, "config", label, failures);
        if (!structureOk) {
            return;
        }

        check(mapper, label + "!manifest", root.path("manifest").toString(), TemplateManifest.class, failures);
        check(mapper, label + "!config", root.path("config").toString(), AgentGroupConfiguration.class, failures);
    }

    /**
     * Records a failure when a group template half is absent or is not an object.
     * {@code GroupTemplateService.instantiate} reads both, so either one missing is
     * a template nobody can instantiate — and it is invisible to the strict parse
     * itself for the reason given at the call site.
     *
     * @return true when the half is present and usable
     */
    private static boolean requireObject(JsonNode root, String field, String label, Map<String, String> failures) {
        JsonNode node = root.path(field);
        if (node.isObject()) {
            return true;
        }
        failures.put(label + "!" + field, "group template has no '%s' object (found %s) — GroupTemplateService"
                .formatted(field, node.getNodeType()) + ".instantiate reads both halves, so it cannot be instantiated");
        return false;
    }

    /**
     * Sweeps the configuration documents inside an agent ZIP, as import would read
     * them.
     */
    private static void checkZip(ObjectMapper mapper, Path root, Path zip, List<String> checked, List<String> skipped,
                                 List<String> unmappedLegacy, Map<String, String> failures)
            throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            for (var entries = archive.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".json")) {
                    continue;
                }
                String label = root.relativize(zip) + "!" + entry.getName();
                Path entryPath = Path.of(entry.getName());
                Class<?> model = modelFor(entryPath);
                if (model == null) {
                    skipped.add(label);
                    // A ZIP under useCases IS the v5 import path, not a copy of it: the
                    // same coverage rule has to reach inside the archive, or a legacy
                    // entry type could go unopened exactly the way the loose files did.
                    if (isLegacyCorpus(zip) && !isDeliberatelyNotAConfig(entryPath)) {
                        unmappedLegacy.add(label);
                    }
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
