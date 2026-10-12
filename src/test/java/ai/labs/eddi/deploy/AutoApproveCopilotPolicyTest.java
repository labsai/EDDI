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
 * only in the review BODY (a collapsed "Suppressed comments (2)" section)
 * counted as clean because only inline comments were counted, and the "never
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
        // The shapes below are the ones Copilot actually posts on this repository
        // (`gh api repos/labsai/EDDI/pulls/<n>/reviews`), shortened: the overview-v2
        // format since 2026-09 (#866 clean, #871 with findings) and the legacy
        // "Pull request overview" format before it (#672, #688).
        String tip = "\n\n---\n\n💡 <a href=\"/labsai/EDDI/new/main?filename=.github/skills/code-review/SKILL.md\">"
                + "Add a `code-review` agent skill</a> or configure MCP servers for context-aware, tailored reviews.";
        String perFile = "\n\n<details>\n<summary>Show a summary per file</summary>\n\n| File | Description |\n| ---- | ----------- |\n"
                + "| `README.md` | Documents the change. |\n</details>";
        String v2Head = "<!-- ccr-overview-v2 -->\n\n## Copilot review overview\n\n";
        String v2Changed = "\n\n<details>\n<summary><strong>What changed in this PR</strong></summary>\n\nStabilizes a test.\n\n"
                + "**Changes:**\n- Waits for toast removal.\n</details>";

        Map<String, Boolean> expected = new LinkedHashMap<>();
        // --- overview v2 ---
        expected.put(v2Head + "### 🟢 Approval recommended\n\nThe focused test-only change is correct.\n\n"
                + "*Get a fresh assessment by requesting another Copilot review.*\n\n**Review effort:** Balanced  \n"
                + "**Findings:** None" + v2Changed + tip, true);
        expected.put(v2Head + "### 🟡 Changes recommended\n\nVault cleanup can orphan secrets.\n\n**Review effort:** Balanced  \n"
                + "**Findings:** 3 <picture><img alt=\"High severity\"></picture>\n\n<details open>\n"
                + "<summary><strong>Open (3)</strong></summary>\n\n- <picture></picture> [Snapshot deletion prevents cleanup]"
                + "(#discussion_r1) · New\n</details>" + v2Changed + tip, false);
        // Findings: None, but earlier reviews' findings are still unresolved.
        expected.put(v2Head + "### 🟢 Approval recommended\n\nLooks good.\n\n**Findings:** None\n\n<details open>\n"
                + "<summary><strong>Open (1)</strong></summary>\n\n- [Null check inverted](#discussion_r2)\n</details>"
                + v2Changed + tip, false);
        expected.put(v2Head + "### 🟢 Approval recommended\n\n**Findings:** 1" + v2Changed + tip, false);
        expected.put(v2Head + "### 🟡 Changes recommended\n\n**Findings:** None" + v2Changed + tip, false);
        // A v2 body without a verdict or a findings line: unknown, fail closed.
        expected.put(v2Head + "The change looks fine." + v2Changed + tip, false);

        // --- legacy "Pull request overview" ---
        expected.put("## Pull request overview\n\nRemoves the obsolete bootstrap flow.\n\n**Changes:**\n- Removes the endpoint.\n\n"
                + "### Reviewed changes\n\nCopilot reviewed 47 out of 53 changed files in this pull request and generated no "
                + "comments." + perFile + tip, true);
        expected.put("## Pull request overview\n\nCopilot reviewed 127 out of 276 changed files in this pull request and generated "
                + "no new comments.\n\n\n\n", true);
        expected.put("## Pull request overview\n\n### Reviewed changes\n\nCopilot reviewed 3 out of 4 changed files in this pull "
                + "request and generated no comments." + perFile + "\n\n<details>\n<summary>Files not reviewed (1)</summary>\n\n"
                + "* **ui/manager/package-lock.json**: Language not supported\n</details>" + tip, true);
        // Findings folded into the body — no inline comment and no review thread exists
        // for them. This is what #672 received three times; each read as clean.
        expected.put("## Pull request overview\n\n### Reviewed changes\n\nCopilot reviewed 47 out of 53 changed files in this pull "
                + "request and generated no comments." + perFile + "\n\n<details>\n<summary>Suppressed comments (2)</summary>\n\n"
                + "**src/main/java/Foo.java:101**\n* The documented 400 contract is not met.\n</details>" + tip, false);
        expected.put("## Pull request overview\n\nCopilot reviewed 127 out of 276 changed files in this pull request and generated "
                + "no new comments.\n\n<details>\n<summary>Suppressed comments (1)</summary>\n\n**Foo.java:513**\n* x\n</details>",
                false);
        expected.put("Copilot reviewed 4 out of 4 changed files in this pull request and generated no comments.\n\n"
                + "<details>\n<summary>Comments suppressed due to low confidence (2)</summary>\n\n"
                + "**src/main/java/Foo.java:12**\n* The null check is inverted.\n</details>", false);
        expected.put("## Pull request overview\n\nCopilot reviewed 131 out of 254 changed files in this pull request and generated 2 "
                + "comments.", false);
        expected.put("Copilot wasn't able to review this pull request because it exceeds the maximum number of files (300). "
                + "Try reducing the number of changed files and requesting a review from Copilot again.", false);
        expected.put("Copilot wasn’t able to review any files in this pull request.", false);
        expected.put("Copilot was not able to review this change. It generated no comments.", false);

        // --- neither format: fail closed ---
        expected.put("", false);
        expected.put("   \n ", false);
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
                "ui/chat/package-lock.json", "ui/manager/scripts/check-i18n.mjs",
                "ui/manager/vitest.config.ts", "ui/chat/vite.config.ts", "ui/manager/playwright.config.ts",
                "ui/chat/eslint.config.js", "ui/manager/tsconfig.app.json", "ui/manager/stryker.config.json",
                ".dockerignore", "src/main/docker/Dockerfile.demo.dockerignore");
        List<String> ordinaryPaths = List.of(
                "src/main/java/ai/labs/eddi/engine/Foo.java", "src/main/resources/application.properties",
                "docs/changelog.d/2026-10-02-x.md", "docs/docker-compose-guide.md", "README.md",
                "ui/manager/src/App.tsx", "ui/manager/src/lib/package.json", "ui/chat/src/main.tsx",
                "scriptsx/run.sh", "src/test/java/ai/labs/eddi/FooTest.java",
                "ui/manager/src/vite-env.d.ts", "ui/manager/src/lib/vitest.config.ts");

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
