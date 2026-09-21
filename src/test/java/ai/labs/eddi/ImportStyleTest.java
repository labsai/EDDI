/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces AGENTS.md §4.7: reference types by their simple name with a
 * top-level import, never by an inline fully-qualified name.
 * <p>
 * A repository review found 366 of these across 168 files. None were the
 * permitted disambiguation case — {@code PendingApprovalSummary},
 * {@code HitlDecision}, {@code ToolApprovalsConfig},
 * {@code ConversationMemorySnapshot} and {@code ControlSignal} each resolve to
 * exactly one class, and {@code IConversationService} imported
 * {@code java.util.List} on one line while writing {@code java.util.List<…>}
 * fully-qualified on another. They accumulate precisely because nothing fails
 * when one is added: the code compiles either way, so the rule was advice that
 * only a reviewer's eye enforced.
 * <p>
 * The genuine exception AGENTS.md allows — two classes sharing a simple name,
 * both needed in one file — is listed explicitly in {@link #ALLOWED}, so an
 * addition to it is a deliberate, reviewable act rather than a silent drift.
 */
@DisplayName("import style (AGENTS.md 4.7)")
class ImportStyleTest {

    /**
     * An inline FQN: a package path in EDDI's own namespace, a common JDK one, or
     * one of the third-party roots this project actually depends on, followed by a
     * capitalised type name.
     * <p>
     * The root list is explicit rather than a general lowercase-dotted-path shape:
     * a generic pattern also matches method chains and builder idioms on a
     * lowercase receiver, which are not FQNs at all.
     * <p>
     * It used to cover only {@code ai.labs.eddi}, {@code java.util},
     * {@code java.time} and {@code java.nio.file}, while AGENTS.md 4.7 states the
     * rule without any package restriction. That blind spot hid 381 inline
     * third-party FQNs across 116 files - {@code jakarta.ws.rs.NotFoundException},
     * {@code io.micrometer.core.instrument.Counter},
     * {@code org.eclipse.microprofile.openapi.models.tags.Tag} and the like - none
     * of which were disambiguation cases; they were simply missing imports.
     */
    // The trailing package segments are optional (*, not +). With '+' this missed
    // `java.util.List` entirely — there is no further lowercase segment after
    // `util` — which is exactly how the original audit under-counted.
    private static final Pattern INLINE_FQN = Pattern.compile(
            "\\b((?:ai\\.labs\\.eddi|java\\.util|java\\.time|java\\.io|java\\.net|java\\.lang|java\\.security|java\\.nio\\.file|jakarta|javax|org\\.eclipse|org\\.jboss|org\\.bson|org\\.postgresql|com\\.fasterxml|com\\.mongodb|io\\.quarkus|io\\.smallrye|io\\.micrometer|io\\.nats|dev\\.langchain4j)(?:\\.[a-z][A-Za-z0-9_]*)*\\.[A-Z][A-Za-z0-9_]*)");

    /**
     * The only permitted inline FQNs, each a genuine collision where one of two
     * same-named types can only be written in full.
     * <ul>
     * <li>The two {@code HistorizedResourceStore} files declare a class whose
     * simple name collides with the superclass it extends.</li>
     * <li>{@code NatsConversationCoordinator} imports {@code io.nats.client.api.*},
     * which brings in {@code io.nats.client.api.Error}; its
     * {@code catch (RuntimeException |
     * java.lang.Error e)} clauses mean the JDK type. An explicit
     * {@code import java.lang.Error} would resolve the ambiguity but is a redundant
     * import (java.lang is implicit), which Checkstyle flags - so the inline FQN is
     * the only clean spelling.</li>
     * </ul>
     */
    private static final Set<String> ALLOWED = Set.of(
            "src/main/java/ai/labs/eddi/datastore/mongo/HistorizedResourceStore.java",
            "src/main/java/ai/labs/eddi/datastore/mongo/ModifiableHistorizedResourceStore.java",
            "src/main/java/ai/labs/eddi/engine/runtime/internal/NatsConversationCoordinator.java");

    /** Blanks out comments and string literals so neither is ever matched. */
    private static String stripNonCode(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n && !(source.charAt(j) == '"' && source.charAt(j - 1) != '\\')) {
                    j++;
                }
                out.append(" ".repeat(Math.min(j - i + 1, n - i)));
                i = j + 1;
            } else if (source.startsWith("//", i)) {
                int j = source.indexOf('\n', i);
                j = j < 0 ? n : j;
                out.append(" ".repeat(j - i));
                i = j;
            } else if (source.startsWith("/*", i)) {
                int j = source.indexOf("*/", i);
                j = j < 0 ? n : j + 2;
                out.append(" ".repeat(j - i));
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
                "expected the working directory to be the project root, was " + root);
        return root;
    }

    @Test
    @DisplayName("no inline fully-qualified names outside the declared disambiguation cases")
    void noInlineFullyQualifiedNames() {
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();

        for (Path dir : List.of(root.resolve("src/main/java"), root.resolve("src/test/java"))) {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path file : paths.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .toList()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (ALLOWED.contains(relative)) {
                        continue;
                    }
                    String masked;
                    try {
                        masked = stripNonCode(Files.readString(file, StandardCharsets.UTF_8));
                    } catch (IOException | RuntimeException e) {
                        continue;
                    }
                    int lineNo = 0;
                    for (String line : masked.split("\n", -1)) {
                        lineNo++;
                        String trimmed = line.stripLeading();
                        if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                            continue;
                        }
                        Matcher m = INLINE_FQN.matcher(line);
                        while (m.find()) {
                            offenders.add(relative + ":" + lineNo + "  " + m.group(1));
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        assertEquals(List.of(), offenders,
                "AGENTS.md 4.7: use a top-level import and the simple name. If two classes genuinely share a "
                        + "simple name in one file, add that file to ALLOWED with a note.\n  "
                        + String.join("\n  ", offenders));
    }
}
