/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the Claude Code plugin in {@code integrations/claude-plugin} true to
 * the server it describes.
 * <p>
 * The skills are instructions to a model, and a model follows them literally: a
 * skill that names {@code cronExpression} or a tool that was renamed sends
 * every user's assistant to a call EDDI refuses, and nothing else in the build
 * reads those files. So this test resolves every tool name the skills mention
 * against the {@code @Tool} methods EDDI registers, every camelCase argument
 * name against the {@code @ToolArg} parameters, and every
 * {@code read_docs("page")} against the pages the container image actually
 * serves — the image copies only top-level {@code docs/*.md} and then deletes a
 * few, so a page that exists in the repository can still be absent at runtime.
 */
@DisplayName("Claude plugin skills reference real MCP tools and served docs")
class SkillToolReferencesTest {

    private static final Path PLUGIN = Path.of("integrations", "claude-plugin");

    /** Fenced code blocks hold example JSON, whose identifiers are not tools. */
    private static final Pattern FENCE = Pattern.compile("(?ms)^```.*?^```");

    /** An inline code span that is a whole snake_case identifier. */
    private static final Pattern SNAKE_CASE_SPAN = Pattern.compile("`([a-z]+(?:_[a-z]+)+)`");

    /** A documentation page the skills tell the model to read. */
    private static final Pattern READ_DOCS = Pattern.compile("read_docs\\(\"([^\"]+)\"\\)");

    /** The page names the Dockerfile's docs stage deletes. */
    private static final Pattern REMOVED_DOC = Pattern.compile("/docs/([A-Za-z0-9_.-]+)\\.md");

    /**
     * Snake_case identifiers the skills mention that are deliberately not MCP
     * tools. Keep this list short: every entry is a word the guard cannot check.
     */
    private static final Set<String> NOT_TOOLS = Set.of(
            "get_started", // config-gotchas.md: an example quick-reply expression
            "partially_completed" // destructive-ops.md: a delete_user_data status value
    );

    /**
     * An inline code span that is a whole camelCase identifier — in a skill, almost
     * always a tool argument, which is where {@code endAllActiveConversations} (the
     * REST name; the MCP argument is {@code endConversations}) slipped through.
     */
    private static final Pattern CAMEL_CASE_SPAN = Pattern.compile("`([a-z]+[A-Z][A-Za-z]*)`");

    /**
     * CamelCase identifiers the skills mention that are config fields or result
     * fields, not tool arguments.
     */
    private static final Set<String> NOT_ARGUMENTS = Set.of(
            "defaultToolTimeoutMs", "toolTimeoutsMs", // LLM task config fields
            "executeUntilFirstSuccess", "lastStep", "valueString", // behavior-rule and template terms
            "failedSteps", "omittedCategories", // GDPR tool result fields
            "ratingConfig", // agent configuration field (SKILL.md, Feedback from users)
            "canOpenTranscript" // list_conversation_ratings result field
    );

    private static Path repoRoot() {
        // Surefire runs with the project basedir as the working directory.
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")), "expected to run from the repository root, got " + root);
        return root;
    }

    private static List<Path> pluginMarkdown() {
        try (Stream<Path> files = Files.walk(repoRoot().resolve(PLUGIN))) {
            List<Path> markdown = files.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            assertFalse(markdown.isEmpty(), "no Markdown found under " + PLUGIN);
            return markdown;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> registeredTools() {
        return McpToolContractTest.tools().stream().map(McpToolContractTest::toolName).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> registeredArguments() {
        return McpToolContractTest.args().stream().map(McpToolContractTest.Arg::name).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Every identifier the pattern finds in the skills' prose (outside code fences)
     * that is neither exempt nor known, by file; and how many it checked.
     */
    private static Map<String, Set<String>> unknownSpans(Pattern span, Set<String> known, Set<String> exempt, int[] checked) {
        Map<String, Set<String>> unknown = new TreeMap<>();
        for (Path file : pluginMarkdown()) {
            Matcher m = span.matcher(FENCE.matcher(read(file)).replaceAll(""));
            while (m.find()) {
                String name = m.group(1);
                if (exempt.contains(name)) {
                    continue;
                }
                checked[0]++;
                if (!known.contains(name)) {
                    unknown.computeIfAbsent(repoRoot().relativize(file).toString(), k -> new TreeSet<>()).add(name);
                }
            }
        }
        return unknown;
    }

    @Test
    @DisplayName("every snake_case identifier in a skill is a registered MCP tool")
    void everyMentionedToolExists() {
        Set<String> tools = registeredTools();
        int[] checked = {0};
        Map<String, Set<String>> unknown = unknownSpans(SNAKE_CASE_SPAN, tools, NOT_TOOLS, checked);
        assertTrue(checked[0] > 50, "the guard found only " + checked[0] + " tool mentions — has the pattern stopped matching?");
        assertEquals(Map.of(), unknown, "skills name MCP tools EDDI does not register (renamed or removed?). Registered: " + tools);
    }

    @Test
    @DisplayName("every camelCase identifier in a skill is an argument some MCP tool takes")
    void everyMentionedArgumentExists() {
        Set<String> arguments = registeredArguments();
        int[] checked = {0};
        Map<String, Set<String>> unknown = unknownSpans(CAMEL_CASE_SPAN, arguments, NOT_ARGUMENTS, checked);
        assertTrue(checked[0] > 10, "the guard found only " + checked[0] + " argument mentions — has the pattern stopped matching?");
        assertEquals(Map.of(), unknown,
                "skills name MCP tool arguments no tool takes (a REST parameter name, or a renamed argument?). Exempt config/result fields in NOT_ARGUMENTS. Registered: "
                        + arguments);
    }

    @Test
    @DisplayName("every exempted identifier is still mentioned and still not a tool or argument")
    void exemptionsAreCurrent() {
        String all = pluginMarkdown().stream().map(SkillToolReferencesTest::read).collect(Collectors.joining("\n"));
        Set<String> tools = registeredTools();
        for (String name : NOT_TOOLS) {
            assertTrue(all.contains("`" + name + "`"), "NOT_TOOLS lists '" + name + "', which no skill mentions any more — remove it");
            assertFalse(tools.contains(name), "NOT_TOOLS lists '" + name + "', which is now a real tool — remove the exemption");
        }
        Set<String> arguments = registeredArguments();
        for (String name : NOT_ARGUMENTS) {
            assertTrue(all.contains("`" + name + "`"), "NOT_ARGUMENTS lists '" + name + "', which no skill mentions any more — remove it");
            assertFalse(arguments.contains(name), "NOT_ARGUMENTS lists '" + name + "', which is now a real argument — remove the exemption");
        }
    }

    @Test
    @DisplayName("every read_docs page exists and ships in the container image")
    void everyReferencedDocIsServed() {
        String dockerfile = read(repoRoot().resolve("src/main/docker/Dockerfile"));
        Set<String> removed = new TreeSet<>();
        Matcher r = REMOVED_DOC.matcher(dockerfile);
        while (r.find()) {
            removed.add(r.group(1));
        }
        assertTrue(removed.contains("changelog"), "could not read the docs stage's deletions from the Dockerfile: " + removed);

        List<String> problems = new ArrayList<>();
        int referenced = 0;
        for (Path file : pluginMarkdown()) {
            Matcher m = READ_DOCS.matcher(read(file));
            while (m.find()) {
                referenced++;
                String page = m.group(1);
                String where = repoRoot().relativize(file) + ": read_docs(\"" + page + "\")";
                if (page.contains("/") || !Files.isRegularFile(repoRoot().resolve("docs").resolve(page + ".md"))) {
                    problems.add(where + " — no docs/" + page + ".md");
                } else if (removed.contains(page)) {
                    problems.add(where + " — deleted from the image by the Dockerfile");
                }
            }
        }
        assertTrue(referenced > 5, "the guard found only " + referenced + " read_docs references — has the pattern stopped matching?");
        assertEquals(List.of(), problems);
    }

    @Test
    @DisplayName("each skill's frontmatter names its directory and has a description")
    void skillFrontmatterIsComplete() {
        Pattern frontmatter = Pattern.compile("\\A---\\R(.*?)\\R---\\R", Pattern.DOTALL);
        for (Path file : pluginMarkdown()) {
            if (!file.getFileName().toString().equals("SKILL.md")) {
                continue;
            }
            Matcher m = frontmatter.matcher(read(file));
            assertTrue(m.find(), file + " has no YAML frontmatter");
            String yaml = m.group(1);
            String dir = file.getParent().getFileName().toString();
            assertTrue(Pattern.compile("(?m)^name: " + Pattern.quote(dir) + "$").matcher(yaml).find(), file + " must declare name: " + dir);
            Matcher d = Pattern.compile("(?m)^description: (.+)$").matcher(yaml);
            assertTrue(d.find(), file + " has no description");
            // The skill listing truncates description text past 1,536 characters.
            assertTrue(d.group(1).length() <= 1536, file + " description is " + d.group(1).length() + " characters");
        }
    }

    @Test
    @DisplayName("the marketplace entry points at the plugin and the names agree")
    void marketplacePointsAtThePlugin() throws IOException {
        ObjectMapper json = new ObjectMapper();
        JsonNode marketplace = json.readTree(repoRoot().resolve(".claude-plugin/marketplace.json").toFile());
        JsonNode plugin = json.readTree(repoRoot().resolve(PLUGIN).resolve(".claude-plugin/plugin.json").toFile());

        JsonNode entry = marketplace.path("plugins").path(0);
        assertEquals("./" + PLUGIN.toString().replace('\\', '/'), entry.path("source").asText());
        assertEquals(plugin.path("name").asText(), entry.path("name").asText());
        assertTrue(plugin.path("name").asText().matches("[a-z0-9]+(-[a-z0-9]+)*"), "plugin name must be kebab-case");
    }
}
