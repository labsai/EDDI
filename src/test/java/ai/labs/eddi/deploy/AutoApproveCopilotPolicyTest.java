/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the two decisions {@code .github/workflows/auto-approve-copilot.yml}
 * makes on its own — "is Copilot's review clean?" and "does this PR touch CI,
 * build or deploy config?" — through {@code node}, lifted verbatim out of the
 * workflow's {@code github-script} block between its
 * {@code // --- policy:<name>} markers.
 * <p>
 * Both decisions were once too permissive: a Copilot review whose findings sat
 * only in the review BODY ("Comments suppressed due to low confidence") counted
 * as clean because only inline comments were counted, and the "never
 * auto-approve CI changes" rule protected {@code .github/**} alone, so a PR
 * that edited {@code pom.xml}, a release script, the Dockerfile or the
 * installer users pipe into bash was approved on a green CI run it could itself
 * have weakened.
 */
class AutoApproveCopilotPolicyTest {

    private static final Path WORKFLOW = Path.of(".github", "workflows", "auto-approve-copilot.yml");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String workflow;

    @BeforeAll
    static void readWorkflow() throws IOException {
        workflow = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the workflow consults both policies where it decides")
    void workflowUsesBothPolicies() {
        int requireClean = workflow.indexOf("if (REQUIRE_CLEAN_COPILOT) {");
        assertTrue(requireClean >= 0, WORKFLOW + " no longer gates on REQUIRE_CLEAN_COPILOT");
        int nextGate = workflow.indexOf("const checkRuns", requireClean);
        String cleanGate = workflow.substring(requireClean, nextGate);
        assertTrue(cleanGate.contains("listCommentsForReview") && cleanGate.contains("copilotBodyIsClean(latestCopilot.body)"),
                WORKFLOW + ": the Copilot-clean gate must check BOTH the inline comments and the review body");
        assertTrue(workflow.contains("protectedGlobFor(p)") && workflow.contains("f.previous_filename"),
                WORKFLOW + ": the protected-path gate must run on every changed file, renames' old paths included");
        assertTrue(!workflow.contains("startsWith('.github/')"),
                WORKFLOW + " still special-cases .github/ instead of using PROTECTED_GLOBS");
    }

    @Test
    @DisplayName("a Copilot review body is clean only when it says so and carries no findings")
    void copilotBodyPolicy() throws Exception {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("", true);
        expected.put("   \n ", true);
        expected.put("## Pull request overview\n\nThis PR bumps a dependency.\n\n### Reviewed changes\n\n"
                + "Copilot reviewed 3 out of 3 changed files in this pull request and generated no comments.\n\n"
                + "<details><summary>Show a summary per file</summary>|File|Description|</details>\n\n---\n\n"
                + "Tip: Customize your code reviews with copilot-instructions.md.", true);
        expected.put("Copilot reviewed 2 out of 2 changed files in this pull request and generated no new comments.", true);
        // Findings folded into the body: no inline comment and no review thread exists.
        expected.put("Copilot reviewed 4 out of 4 changed files in this pull request and generated no comments.\n\n"
                + "<details>\n<summary>Comments suppressed due to low confidence (2)</summary>\n\n"
                + "**src/main/java/Foo.java:12**\n* The null check is inverted.\n</details>", false);
        expected.put("Copilot reviewed 4 out of 4 changed files in this pull request and generated 2 comments.", false);
        expected.put("Copilot wasn't able to review any files in this pull request.", false);
        expected.put("Copilot wasn’t able to review any files in this pull request.", false);
        expected.put("Copilot was not able to review this change. It generated no comments.", false);
        // An overview with no explicit "no comments" statement: unknown format, fail
        // closed.
        expected.put("## Pull request overview\n\nThis PR refactors the parser.", false);

        List<String> inputs = new ArrayList<>(expected.keySet());
        JsonNode results = runPolicy("copilot-body", "copilotBodyIsClean", inputs);
        for (int i = 0; i < inputs.size(); i++) {
            assertEquals(expected.get(inputs.get(i)), results.get(i).asBoolean(),
                    "copilotBodyIsClean(" + JSON.writeValueAsString(inputs.get(i)) + ")");
        }
        assertTrue(runPolicy("copilot-body", "copilotBodyIsClean", List.of()).isEmpty());
    }

    @Test
    @DisplayName("every CI, build and deploy path is protected, and ordinary source is not")
    void protectedPathPolicy() throws Exception {
        List<String> protectedPaths = List.of(
                ".github/workflows/ci.yml", ".github/dependabot.yml", ".githooks/pre-push",
                "scripts/bump-version.py", "scripts/collate-changelog.py",
                "pom.xml", ".mvn/wrapper/maven-wrapper.properties", "mvnw", "mvnw.cmd",
                "checkstyle.xml", "eclipse-formatter.xml",
                "src/main/docker/Dockerfile", "mcp-sidecar/Dockerfile", ".clusterfuzzlite/build.sh",
                "helm/eddi/values.yaml", "helm/eddi/templates/deployment.yaml", "k8s/base/eddi-deployment.yaml",
                "gcp/provision-vm.sh", "keycloak/eddi-realm.json",
                "docker-compose.yml", "docker-compose.auth.yml", "ui/manager/docker-compose.integration-keycloak.yml",
                "install.sh", "install.ps1", "mise.toml", "ui/manager/mise.toml", ".gitleaksignore", ".trivyignore",
                "ui/manager/package.json", "ui/manager/package-lock.json", "ui/chat/package.json",
                "ui/chat/package-lock.json", "ui/manager/scripts/check-i18n.mjs");
        List<String> ordinaryPaths = List.of(
                "src/main/java/ai/labs/eddi/engine/Foo.java", "src/main/resources/application.properties",
                "docs/changelog.d/2026-10-02-x.md", "docs/docker-compose-guide.md", "README.md",
                "ui/manager/src/App.tsx", "ui/manager/src/lib/package.json", "ui/chat/src/main.tsx",
                "scriptsx/run.sh", "src/test/java/ai/labs/eddi/FooTest.java");

        List<String> inputs = new ArrayList<>(protectedPaths);
        inputs.addAll(ordinaryPaths);
        JsonNode results = runPolicy("protected-paths", "protectedGlobFor", inputs);
        for (int i = 0; i < inputs.size(); i++) {
            boolean shouldBeProtected = i < protectedPaths.size();
            assertEquals(shouldBeProtected, !results.get(i).isNull(),
                    "protectedGlobFor(" + inputs.get(i) + ") returned " + results.get(i)
                            + (shouldBeProtected ? ", so a PR changing it could be auto-approved" : ", so ordinary source needs a human"));
        }
    }

    /**
     * Evaluates {@code fn} over {@code inputs} with the policy block {@code name}
     * exactly as the workflow ships it, and returns the results as a JSON array.
     */
    private static JsonNode runPolicy(String name, String fn, List<String> inputs) throws IOException, InterruptedException {
        Path node = locateOnPath(WINDOWS ? "node.exe" : "node");
        assumeTrue(node != null, "node is not on PATH; CI's ubuntu-latest runner has it");

        String begin = "// --- policy:" + name;
        String end = "// --- end policy:" + name + " ---";
        int from = workflow.indexOf(begin);
        int to = workflow.indexOf(end, from);
        assertTrue(from >= 0 && to > from, WORKFLOW + " no longer marks its `" + name + "` policy block");
        String block = workflow.substring(workflow.indexOf('\n', from) + 1, to);

        Path directory = Files.createDirectories(Path.of("target", "auto-approve-policy"));
        Path inputFile = directory.resolve(name + "-inputs.json");
        Files.writeString(inputFile, JSON.writeValueAsString(inputs), StandardCharsets.UTF_8);
        Path script = directory.resolve(name + ".js");
        Files.writeString(script, block + "\nconst inputs = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));\n"
                + "process.stdout.write(JSON.stringify(inputs.map(v => " + fn + "(v))));\n", StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder(node.toString(), script.toString(), inputFile.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(process.waitFor(1, TimeUnit.MINUTES), "node did not finish");
        assertEquals(0, process.exitValue(), "the `" + name + "` policy block does not run on its own:\n" + output);
        return JSON.readTree(output);
    }

    private static Path locateOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(entry, executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            } catch (InvalidPathException ignored) {
                // A PATH entry that is not a path cannot hold the executable.
            }
        }
        return null;
    }
}
