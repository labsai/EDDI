/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins documentation claims to the code that makes them true.
 * <p>
 * {@link DocumentationLinksTest} keeps the links honest; this keeps the
 * <em>content</em> honest. Every assertion below corresponds to a claim a
 * repository review found to be false — a field reference that omitted a
 * required field, a table that named a property the application never reads, a
 * warning describing a fallback that was deliberately removed. Markdown
 * compiles to nothing, so none of those were visible to any other check in the
 * build, and several had been wrong for more than one release.
 * <p>
 * The assertions are deliberately shaped as "the doc must mention what the code
 * declares" rather than as fixed expected strings. A new condition type, a new
 * schedule field or a new backup extension is then a failing test with the
 * missing name in the message, not a silent divergence discovered later by a
 * reader who followed the reference and got a 400.
 */
@DisplayName("documentation accuracy")
class DocumentationAccuracyTest {

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
                "expected the working directory to be the project root, was " + root);
        return root;
    }

    private static String read(String relativePath) {
        Path p = repoRoot().resolve(relativePath);
        assertTrue(Files.isRegularFile(p), relativePath + " is missing");
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Directories with no documentation to check (or not ours to check). */
    private static final Set<String> SKIPPED_DIRS = Set.of("target", ".git", "node_modules", ".claude", ".mvn");

    private static List<Path> markdownFiles() {
        Path root = repoRoot();
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()
                            && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    /**
     * Every documentation page except the changelog and its archives, which record
     * what was true at the time and must not be rewritten to match today's code.
     */
    private static List<Path> currentDocs() {
        List<Path> docs = new ArrayList<>();
        for (Path p : markdownFiles()) {
            String rel = repoRoot().relativize(p).toString().replace('\\', '/');
            if (rel.equals("docs/changelog.md") || rel.startsWith("docs/changelog/")) {
                continue;
            }
            docs.add(p);
        }
        return docs;
    }

    private static String contentOf(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The text of one Markdown section: from the heading that starts with
     * {@code headingPrefix} up to the next heading at the same level.
     * <p>
     * Scoping matters more than it looks. Searching a whole file lets an unrelated
     * paragraph — an example, a changelog line, a prose mention — satisfy an
     * assertion after the reference table it is really about has lost the field.
     * The test would then pass while the reader following the table still cannot
     * find what they need, which is the exact failure mode this class exists to
     * catch.
     */
    private static String section(String markdown, String headingPrefix, String nextHeadingPrefix) {
        int start = markdown.indexOf(headingPrefix);
        assertTrue(start >= 0, "expected a section starting with \"" + headingPrefix + "\"; the document has been restructured");
        int end = markdown.indexOf(nextHeadingPrefix, start + headingPrefix.length());
        return end > 0 ? markdown.substring(start, end) : markdown.substring(start);
    }

    /**
     * Collects the {@code ID = "..."} string constants declared in a source
     * directory.
     */
    private static Set<String> declaredIds(String sourceDir) {
        Pattern id = Pattern.compile("String\\s+ID\\s*=\\s*\"([^\"]+)\"");
        Set<String> ids = new TreeSet<>();
        Path dir = repoRoot().resolve(sourceDir);
        assertTrue(Files.isDirectory(dir), sourceDir + " is missing");
        try (var files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".java")).forEach(f -> {
                Matcher m = id.matcher(contentOf(f));
                while (m.find()) {
                    ids.add(m.group(1));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertFalse(ids.isEmpty(), "found no ID constants under " + sourceDir);
        return ids;
    }

    // ---------------------------------------------------------------- secrets

    /**
     * {@code PropertySetterTask.autoVaultSecret} fails closed: with no master key
     * it scrubs the plaintext and throws, aborting the turn. An earlier release did
     * fall back to storing plaintext, and three pages still described that fallback
     * — including AGENTS.md, which every contributor and AI session auto-loads, so
     * the wrong mental model was re-seeded rather than read once.
     */
    @Test
    @DisplayName("no page claims a secret-scoped property falls back to plaintext")
    void secretScopeIsDocumentedAsFailingClosed() {
        String task = read("src/main/java/ai/labs/eddi/modules/properties/impl/PropertySetterTask.java");
        assertTrue(task.contains("Refusing to persist the value in plaintext"),
                "autoVaultSecret no longer fails closed — this test's premise is gone, revisit the docs it guards");

        List<String> offenders = new ArrayList<>();
        for (Path p : currentDocs()) {
            String text = contentOf(p).toLowerCase(Locale.ROOT);
            if (!text.contains("scope: \"secret\"") && !text.contains("scope=\"secret\"")) {
                continue;
            }
            if (text.contains("fall back to plaintext") || text.contains("falls back to plaintext")
                    || text.contains("fall back to storing plaintext")) {
                offenders.add(repoRoot().relativize(p).toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "these pages still describe the removed plaintext fallback for scope: \"secret\": " + offenders);
    }

    // ------------------------------------------------------------- conditions

    /**
     * behavior-rules.md presents its list as exhaustive ("List of available
     * conditions"), and it was missing four of the twelve registered types. One of
     * them, {@code deploymentContext}, was documented nowhere at all, so a designer
     * needing an environment check hand-rolled one from a client-supplied context
     * variable instead.
     */
    @Test
    @DisplayName("behavior-rules.md names every registered condition type")
    void everyConditionTypeIsDocumented() {
        String doc = read("docs/behavior-rules.md");
        Set<String> missing = new TreeSet<>();
        for (String id : declaredIds("src/main/java/ai/labs/eddi/modules/rules/impl/conditions")) {
            if (!doc.contains(id)) {
                missing.add(id);
            }
        }
        assertTrue(missing.isEmpty(), "behavior-rules.md does not mention these condition types: " + missing);
    }

    // ----------------------------------------------------------------- backup

    /**
     * AGENTS.md §5.5 is the stated reference for hand-building an import ZIP. It
     * listed seven of the twelve file extensions the importer handles, so the most
     * common case — an agent with a regular dictionary — could not be built from
     * the file that tells you how to build one.
     */
    @Test
    @DisplayName("AGENTS.md's ZIP section names every backup file extension")
    void everyBackupExtensionIsDocumented() {
        // §5.5 only: the URI table further down names some of the same types, and would
        // otherwise satisfy this check for an extension the ZIP block no longer lists.
        String agents = section(read("AGENTS.md"), "### 5.5 ZIP Structure for Agent Import", "### 5.6 ");
        String backup = read("src/main/java/ai/labs/eddi/backup/impl/AbstractBackupService.java");
        Matcher m = Pattern.compile("String\\s+\\w+_EXT\\s*=\\s*\"([^\"]+)\"").matcher(backup);
        Set<String> missing = new TreeSet<>();
        int found = 0;
        while (m.find()) {
            found++;
            String ext = m.group(1);
            if (!agents.contains("." + ext + ".json")) {
                missing.add(ext);
            }
        }
        assertTrue(found >= 12, "expected at least 12 *_EXT constants, found " + found);
        assertTrue(missing.isEmpty(), "AGENTS.md's ZIP structure omits these file extensions: " + missing);
    }

    /**
     * The same section tells the reader a templating step is mandatory whenever
     * output contains {@code {properties.x}}, and then gave a step-type table that
     * did not contain it — so the mandatory step was undiscoverable from the file
     * that mandates it. Omitting it deploys READY and emits the literal
     * {@code {properties.agentName}} to end users.
     */
    @Test
    @DisplayName("AGENTS.md's workflow step table names the templating and mcpcalls steps")
    void workflowStepTableIsComplete() {
        String agents = read("AGENTS.md");
        int table = agents.indexOf("#### Workflow step types");
        assertTrue(table > 0, "AGENTS.md no longer has a 'Workflow step types' section");
        String section = agents.substring(table, Math.min(agents.length(), table + 2000));
        for (String step : List.of("ai.labs.parser", "ai.labs.behavior", "ai.labs.property",
                "ai.labs.httpcalls", "ai.labs.output", "ai.labs.llm",
                "ai.labs.mcpcalls", "ai.labs.templating")) {
            assertTrue(section.contains(step), "the workflow step table omits " + step);
        }
    }

    // --------------------------------------------------------------- schedules

    /**
     * scheduling.md's Schedule Fields table omitted {@code oneTimeAt} — a validated
     * one-time trigger mode mentioned on no page at all — and {@code metadata}, the
     * field a Dream schedule needs to be dispatched.
     */
    @Test
    @DisplayName("scheduling.md documents oneTimeAt and metadata")
    void scheduleFieldsTableCoversTheValidatedFields() {
        String doc = read("docs/scheduling.md");
        String model = read("src/main/java/ai/labs/eddi/engine/schedule/model/ScheduleConfiguration.java");
        // Anchor on the reference table, not on the page. A field named in passing in
        // the prose is not discoverable by someone reading the field reference, which
        // is exactly how oneTimeAt stayed undocumented while being validated.
        int table = doc.indexOf("### Schedule Fields");
        assertTrue(table > 0, "scheduling.md no longer has a 'Schedule Fields' section");
        int end = doc.indexOf("\n### ", table + 1);
        String fields = end > 0 ? doc.substring(table, end) : doc.substring(table);
        for (String field : List.of("oneTimeAt", "metadata")) {
            assertTrue(model.contains(field), "ScheduleConfiguration no longer declares " + field);
            assertTrue(fields.contains("`" + field + "`"),
                    "the Schedule Fields table in scheduling.md has no row for " + field);
        }
    }

    /**
     * A background dream cycle has no parent LLM task to inherit credentials from,
     * so {@code dream.parameters} is required for {@code summarizeInteractions}.
     * The field appeared in no reference table, and its absence fails every
     * summarization step with a provider 401 while stale pruning keeps working — so
     * the failure looks intermittent rather than like a missing credential.
     */
    @Test
    @DisplayName("the Dream reference tables document the parameters field")
    void dreamParametersAreDocumented() {
        String agentConfig = read("src/main/java/ai/labs/eddi/configs/agents/model/AgentConfiguration.java");
        assertTrue(agentConfig.contains("private Map<String, String> parameters"),
                "DreamConfig no longer declares parameters");
        // The reference table only. The example config above it also sets parameters,
        // and a reader working from the table — the normal way to discover a field —
        // would not see it there.
        String dreamTable = section(read("docs/user-memory.md"), "### Dream Configuration", "\\n## ");
        assertTrue(dreamTable.contains("`parameters`"), "the Dream Configuration table in user-memory.md has no row for parameters");
        assertTrue(read("docs/scheduling.md").contains("\"parameters\""),
                "scheduling.md's Dream example does not set parameters");
    }

    // -------------------------------------------------------------- llm fields

    /**
     * Six shipped fields on the LLM task appeared in no page under {@code docs/}.
     * The costly one is {@code conversationSummary}: a non-empty
     * {@code builtInToolsWhitelist} enables only the tools it names, so a rolling
     * summary configured without {@code conversationRecall} in the whitelist leaves
     * the model answering from a truncated view with no error.
     */
    @Test
    @DisplayName("langchain.md documents every shipped LLM task field the review found missing")
    void llmTaskFieldsAreDocumented() {
        String doc = read("docs/langchain.md");
        String model = read("src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java");
        for (String field : List.of("toolLoadingStrategy", "maxToolsInContext", "conversationSummary",
                "maxSystemPromptChars", "responseValidation")) {
            assertTrue(model.contains(field), "LlmConfiguration no longer declares " + field);
            assertTrue(doc.contains("`" + field + "`"), "langchain.md does not document " + field);
        }
        assertTrue(doc.contains("`conversationRecall`"),
                "langchain.md's built-in tool table does not list conversationRecall");
        assertTrue(read("docs/rag.md").contains("`maxRagContextChars`"),
                "rag.md does not document maxRagContextChars");
    }

    // ------------------------------------------------------------- properties

    /**
     * The README's system-property table named
     * {@code quarkus.mongodb.connection-string}. That key is real — the Quarkus
     * extension's health check reads it — but the application does not, so pointing
     * it at another host reported the new host as UP while every read and write
     * still went to the old one.
     */
    @Test
    @DisplayName("no page presents quarkus.mongodb.connection-string as the connection knob")
    void mongoConnectionPropertyIsTheOneTheCodeReads() {
        String module = read("src/main/java/ai/labs/eddi/datastore/bootstrap/PersistenceModule.java");
        assertTrue(module.contains("mongodb.connectionString"),
                "PersistenceModule no longer reads mongodb.connectionString");

        List<String> offenders = new ArrayList<>();
        for (Path p : currentDocs()) {
            if (contentOf(p).contains("quarkus.mongodb.connection-string=")) {
                offenders.add(repoRoot().relativize(p).toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "these pages set quarkus.mongodb.connection-string, which the application never reads: " + offenders);
    }

    // ------------------------------------------------------------------ traces

    /**
     * The pipeline emits span attributes under an {@code eddi.} prefix; the
     * un-prefixed names are the Micrometer <em>metric</em> tags, a different
     * surface. A TraceQL filter on {@code task.id} matches zero spans with no
     * error, which reads as "tracing is not emitting" rather than as a typo.
     */
    @Test
    @DisplayName("documented span attributes carry the eddi. prefix the code emits")
    void spanAttributesAreDocumentedWithTheirPrefix() {
        String manager = read("src/main/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManager.java");
        assertTrue(manager.contains("\"eddi.task.id\""), "LifecycleManager no longer sets eddi.task.id");

        Pattern unprefixed = Pattern.compile("eddi\\.pipeline\\.task\\s*\\[\\s*task\\.");
        List<String> offenders = new ArrayList<>();
        for (Path p : currentDocs()) {
            if (unprefixed.matcher(contentOf(p)).find()) {
                offenders.add(repoRoot().relativize(p).toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "these pages show eddi.pipeline.task with un-prefixed attribute names: " + offenders);
    }

    // ------------------------------------------------------------- tutorials

    /**
     * Both first-agent tutorials named the workflow field
     * {@code packageextensions}. Configuration writes go through a strict parser
     * that rejects an unknown top-level key, so a reader working from the reference
     * table — the only way to discover field names for a client library — got a 400
     * with nothing in the table to correct it from.
     */
    @Test
    @DisplayName("no page names a workflow field the strict parser rejects")
    void tutorialsNameTheRealWorkflowField() {
        String model = read("src/main/java/ai/labs/eddi/configs/workflows/model/WorkflowConfiguration.java");
        assertTrue(model.contains("workflowSteps"), "WorkflowConfiguration no longer declares workflowSteps");
        assertFalse(model.contains("packageextensions"), "packageextensions is a real field now — delete this test");

        List<String> offenders = new ArrayList<>();
        for (Path p : currentDocs()) {
            if (contentOf(p).contains("packageextensions")) {
                offenders.add(repoRoot().relativize(p).toString());
            }
        }
        assertTrue(offenders.isEmpty(), "these pages still name the non-existent field packageextensions: " + offenders);
    }

    /**
     * A tutorial documented a Facebook channel connector with three config keys.
     * None exists. The POST succeeds because the deprecated {@code channels} field
     * still deserialises, nothing is wired, and no log line points at the cause.
     */
    @Test
    @DisplayName("no page documents a Facebook channel connector")
    void noFacebookConnectorIsDocumented() {
        List<String> offenders = new ArrayList<>();
        for (Path p : currentDocs()) {
            String text = contentOf(p).toLowerCase(Locale.ROOT);
            if (text.contains("ai.labs.channel.facebook") || text.contains("pageaccesstoken")) {
                offenders.add(repoRoot().relativize(p).toString());
            }
        }
        assertTrue(offenders.isEmpty(), "these pages document a Facebook channel that does not exist: " + offenders);
    }

    /**
     * Compose v2 ships as the {@code docker compose} subcommand; the standalone
     * {@code docker-compose} binary is end-of-life and is not used here. It was
     * still the first command on the Docker page, which fails with "command not
     * found" on a current Docker Desktop or Engine.
     */
    @Test
    @DisplayName("no page invokes the end-of-life docker-compose binary")
    void composeInvocationsUseV2() {
        Pattern v1 = Pattern.compile("(?m)^\\s*(?:#\\s*)?docker-compose\\s+(up|down|build|run|logs|ps|pull|-f)\\b");
        List<String> offenders = new ArrayList<>();
        for (Path p : currentDocs()) {
            if (v1.matcher(contentOf(p)).find()) {
                offenders.add(repoRoot().relativize(p).toString());
            }
        }
        assertTrue(offenders.isEmpty(), "these pages invoke the end-of-life docker-compose binary: " + offenders);
    }

    // --------------------------------------------------------------- planning

    /**
     * {@code planning/} is where an engineer or an agent looks for available work.
     * Five files there described shipped subsystems as unbuilt, and two of them
     * went further and instructed an agent to implement them task-by-task. An agent
     * pointed at either began at "Step 1: Write the failing test" against a spec
     * whose class names all resolve to files that already exist.
     */
    @Test
    @DisplayName("shipped plans are marked and carry no execute-this directive")
    void shippedPlansAreMarkedAsImplemented() {
        List<String> shipped = List.of(
                "planning/multimodal-attachments-completion-plan.md",
                "planning/conversation-cancel-plan.md",
                "planning/observability-and-pipeline-plan.md",
                "planning/hitl-tool-approval-plan.md",
                "planning/mcp-hitl-surface-plan.md");
        for (String rel : shipped) {
            String text = read(rel);
            String head = text.substring(0, Math.min(text.length(), 1200));
            // "Status:" alone also accepts "Status: Planned", which is effectively what
            // these files said before. What has to hold is that a reader scanning
            // planning/ for available work can tell at a glance that this one is done.
            assertTrue(head.contains("Status: IMPLEMENTED") || head.contains("Status: ALL FOUR ITEMS SHIPPED"),
                    rel + " does not declare a shipped status in its opening block, so it still reads as available work");
            assertFalse(text.contains("implement this plan task-by-task"),
                    rel + " still instructs an agent to implement a shipped subsystem");
        }
    }

    /**
     * The unchecked-checkbox format is what makes a plan read as in-progress, and
     * it is the signal a triage pass acts on. The two HITL plans held 107 of them
     * for a subsystem AGENTS.md lists as complete.
     */
    @Test
    @DisplayName("no shipped plan carries an unchecked task list")
    void shippedPlansCarryNoOpenCheckboxes() {
        for (String rel : List.of("planning/hitl-tool-approval-plan.md", "planning/mcp-hitl-surface-plan.md")) {
            assertFalse(read(rel).contains("- [ ] "),
                    rel + " still carries unchecked task boxes for work that shipped");
        }
    }
}
