/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Manager holds a copy of
 * {@link AgentSetupService#MAX_SYSTEM_PROMPT_LENGTH}
 * ({@code SETUP_MAX_SYSTEM_PROMPT_LENGTH} in
 * {@code ui/manager/src/lib/api/agent-setup.ts}), and its
 * {@code operator-prompt-size.test.ts} holds the Platform Operator's prompt to
 * 80% of that copy. The guard is only as good as the copy: 6.7.0 shipped an
 * Operator prompt of ~110,000 characters against a backend cap of 100,000, so
 * no deployment could activate or upgrade its Operator, and no test compared
 * the two sides. This one does.
 */
class SetupPromptCapMirrorTest {

    private static final Path MANAGER_SETUP_API = Path.of("ui", "manager", "src", "lib", "api", "agent-setup.ts");
    private static final Pattern MIRROR = Pattern.compile("export const SETUP_MAX_SYSTEM_PROMPT_LENGTH\\s*=\\s*([0-9][0-9_]*)\\s*;");

    @Test
    void managerMirrorEqualsTheBackendCap() throws IOException {
        assertTrue(Files.isRegularFile(MANAGER_SETUP_API), "missing " + MANAGER_SETUP_API.toAbsolutePath());
        String source = Files.readString(MANAGER_SETUP_API, StandardCharsets.UTF_8);

        Matcher matcher = MIRROR.matcher(source);
        assertTrue(matcher.find(), MANAGER_SETUP_API + " no longer declares SETUP_MAX_SYSTEM_PROMPT_LENGTH; "
                + "the Operator prompt-size guard depends on it");

        String literal = matcher.group(1);
        int mirror;
        try {
            mirror = Integer.parseInt(literal.replace("_", ""));
        } catch (NumberFormatException e) {
            fail("SETUP_MAX_SYSTEM_PROMPT_LENGTH in " + MANAGER_SETUP_API + " is not an int literal: " + literal, e);
            return;
        }
        assertEquals(AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH, mirror,
                "SETUP_MAX_SYSTEM_PROMPT_LENGTH in " + MANAGER_SETUP_API
                        + " must equal AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH; change both together");
    }
}
