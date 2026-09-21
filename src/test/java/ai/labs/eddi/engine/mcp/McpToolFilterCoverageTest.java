/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import io.quarkiverse.mcp.server.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one failure mode {@link McpToolFilter} makes silent: a tool that
 * exists but is invisible.
 * <p>
 * {@code ToolFilter#test} sees only a tool's <em>name</em>, so a newly added
 * {@code @Tool} method that nobody adds to the whitelist still compiles, still
 * passes its own unit test — which calls the method directly, bypassing the
 * filter entirely — and simply never appears in {@code tools/list}. That is
 * exactly how {@code list_docs}/{@code read_docs} shipped invisible in the
 * first draft of this branch.
 * <p>
 * Both directions are pinned deliberately:
 * <ul>
 * <li><b>Every declared tool is whitelisted</b> — catches the invisible-tool
 * bug above.</li>
 * <li><b>Every whitelisted name is declared</b> — catches the opposite rot, an
 * entry left behind after a tool was renamed or removed, which reads as
 * coverage while protecting nothing.</li>
 * </ul>
 */
@DisplayName("McpToolFilter whitelist coverage")
class McpToolFilterCoverageTest {

    /**
     * Every class in this package that declares {@code @Tool} methods.
     * <p>
     * Listed explicitly rather than classpath-scanned: a scan needs an indexing
     * dependency in a plain unit test, and the set of tool classes changes rarely
     * and visibly. If you add a new {@code Mcp*Tools} class, add it here —
     * {@link #everyToolClassInThePackageIsListed()} fails until you do, so
     * forgetting is loud rather than silent.
     */
    private static final List<Class<?>> TOOL_CLASSES = List.of(
            McpAdminTools.class, McpConversationTools.class, McpSetupTools.class,
            McpGroupTools.class, McpHitlTools.class, McpMemoryTools.class,
            McpGdprTools.class, McpDocTools.class);

    private static Set<String> declaredToolNames() {
        var names = new TreeSet<String>();
        for (Class<?> toolClass : TOOL_CLASSES) {
            for (Method method : toolClass.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    // An omitted name defaults to the sentinel Tool.ELEMENT_NAME (the
                    // literal "<<element name>>"), NOT to blank — the SPI then uses the
                    // method name. McpGroupTools relies on this for all 18 of its tools,
                    // whose methods are already snake_case. Testing for blank instead
                    // silently collapses every such tool to one bogus name.
                    String declared = tool.name();
                    names.add(declared.isBlank() || Tool.ELEMENT_NAME.equals(declared) ? method.getName() : declared);
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("every declared @Tool is whitelisted — an unlisted tool is invisible, silently")
    void everyDeclaredToolIsWhitelisted() {
        var missing = new TreeSet<>(declaredToolNames());
        missing.removeAll(McpToolFilter.MCP_TOOLS);
        assertEquals(Set.of(), missing,
                "These @Tool methods exist but are NOT in McpToolFilter.MCP_TOOLS, so tools/list hides them: " + missing);
    }

    @Test
    @DisplayName("every whitelisted name is actually declared — no stale entries pretending to be coverage")
    void everyWhitelistedNameIsDeclared() {
        var declared = declaredToolNames();
        var stale = new TreeSet<>(McpToolFilter.MCP_TOOLS);
        stale.removeAll(declared);
        assertEquals(Set.of(), stale,
                "These names are whitelisted but no @Tool declares them (renamed or removed?): " + stale);
    }

    @Test
    @DisplayName("the docs tools specifically are exposed — the regression that prompted this test")
    void docsToolsAreExposed() {
        assertFalse(declaredToolNames().isEmpty(), "reflection found no tools at all — the class list is wrong");
        for (String name : List.of("list_docs", "read_docs")) {
            assertEquals(true, McpToolFilter.MCP_TOOLS.contains(name), name + " must be exposed to MCP clients");
        }
    }

    /**
     * Closes the one hole the tests above cannot see. Both of them start from
     * {@link #TOOL_CLASSES}, so a brand-new {@code Mcp*Tools} class that nobody
     * adds to that list has its tools invisible AND leaves every assertion green —
     * the same silent failure this file exists to prevent, one level up.
     * <p>
     * Counting the compiled classes in the package needs no indexing dependency:
     * they are already on disk next to the ones being tested.
     */
    @Test
    @DisplayName("every Mcp*Tools class in the package is in TOOL_CLASSES — a new one cannot go unnoticed")
    void everyToolClassInThePackageIsListed() throws Exception {
        var codeSource = McpAdminTools.class.getProtectionDomain().getCodeSource().getLocation();
        Path packageDir = Path.of(codeSource.toURI()).resolve("ai/labs/eddi/engine/mcp");
        assertTrue(Files.isDirectory(packageDir), "cannot locate compiled package at " + packageDir);

        Set<String> onDisk;
        try (var entries = Files.list(packageDir)) {
            onDisk = entries.map(p -> p.getFileName().toString())
                    // Nested/anonymous classes carry a '$'; only top-level tool
                    // holders can declare the @Tool methods the SPI scans.
                    .filter(n -> n.startsWith("Mcp") && n.endsWith("Tools.class") && !n.contains("$"))
                    .map(n -> n.substring(0, n.length() - ".class".length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        var listed = TOOL_CLASSES.stream().map(Class::getSimpleName).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(onDisk, listed,
                "TOOL_CLASSES is out of step with the package. A class present on disk but not listed has its "
                        + "@Tool methods checked by nothing, so forgetting the whitelist stays silent: " + onDisk);
    }
}
