package ai.labs.eddi.engine.compat;

import ai.labs.eddi.modules.llm.impl.LlmTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code CompatibilityRules.withoutConfigReferences} replaces a regex that went
 * quadratic on unclosed {@code {vault:} openers; it must keep the regex's
 * matches.
 */
class CompatibilityRulesReferenceScanTest {

    private static final List<String> SAMPLES = List.of("", "plain", "{", "{vault:", "{vault:}", "{vault:a}", "a{vault:a}b",
            "{vault:a}{vars:b}{caller:c}",
            "{vault:a{vars:b}", "{vault:{vault:a}", "{vault:a}}", "{{vault:a}}", "x{eddivault:k}y{connection:z}", "{vars:a", "{vars:a}{vars:b",
            "{vault:a}{vault:", "{vaultx:a}", "{ vault:a}", "${vault:a} and ${vars:b}", "{vault:$1\\}", "{unknown:a}{vault:b}",
            "{vault:a}\n{vars:b}");

    @Test
    @SuppressWarnings("java:S5841")
    void sameOutputAsTheRegex() {
        for (String sample : SAMPLES) {
            assertEquals(LlmTask.CONFIG_REF_MENTION.matcher(sample).replaceAll(""), CompatibilityRules.withoutConfigReferences(sample),
                    "differs for: " + sample);
        }
    }

    @Test
    void containsTemplateIgnoresReferencesButSeesTemplates() {
        assertFalse(CompatibilityRules.containsTemplate("${vault:key}"));
        assertTrue(CompatibilityRules.containsTemplate("{vault:key}{#if x}y{/if}"));
    }

    @Test
    void unclosedOpenersAreLinear() {
        String hostile = "{vault:".repeat(200_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertTrue(CompatibilityRules.containsTemplate(hostile), "unclosed openers are not references, so Qute sees them"));
    }
}
