/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The list of EDDI's own tables that a knowledge base may not be pointed at is
 * derived from the code, so a table added later cannot be missed.
 */
@DisplayName("StoreParameterChecks.EDDI_TABLES follows the CREATE TABLE statements")
class StoreParameterChecksTablesTest {

    private static final Pattern CREATE = Pattern.compile("CREATE TABLE IF NOT EXISTS\s+([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("every table EDDI creates by a literal statement is on the list")
    void everyCreatedTableIsListed() throws IOException {
        Set<String> created = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = CREATE.matcher(Files.readString(file));
                while (matcher.find()) {
                    created.add(matcher.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        assertFalse(created.isEmpty(), "the scan found no statements: it is looking in the wrong place");

        Set<String> missing = new TreeSet<>(created);
        missing.removeAll(StoreParameterChecks.EDDI_TABLES);
        assertEquals(Set.of(), missing, "tables EDDI creates that a knowledge base could be pointed at");
    }
}
