/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl.extensions;

import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.TemplateLimitExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code str.replace} and {@code str.concat} allocated their result before the
 * engine's output bound was checked, so a template could make them build a
 * string far past {@code eddi.templating.max-output-chars}. They now refuse,
 * with the size they would have produced, before allocating.
 */
@DisplayName("StringTemplateExtensions — replace and concat pre-check their result size")
class StringTemplateExtensionsOutputLimitTest {

    private static final String SIXTY = "ab".repeat(30);

    @BeforeEach
    void limitTo100() {
        TemplateOutputLimit.configure(100);
    }

    @AfterEach
    void restoreDefault() {
        TemplateOutputLimit.configure(TemplateOutputLimit.DEFAULT_MAX_CHARS);
    }

    @Test
    @DisplayName("replace: a growing replacement that would pass the bound is refused with the size it would produce")
    void replaceOverTheBound() {
        // 60 chars, 30 occurrences of "a", each growing by 2 → 120 > 100
        var e = assertThrows(TemplateLimitExceededException.class, () -> StringTemplateExtensions.replace(SIXTY, "a", "aaa"));

        assertTrue(e.getMessage().contains("120"), e.getMessage());
        assertTrue(e.getMessage().contains(TemplateOutputLimit.PROPERTY), e.getMessage());
    }

    @Test
    @DisplayName("replace: within the bound it replaces as before")
    void replaceWithinTheBound() {
        // 60 + 30 × 1 = 90 ≤ 100
        assertEquals("aab".repeat(30), StringTemplateExtensions.replace(SIXTY, "a", "aa"));
    }

    @Test
    @DisplayName("replace: a shrinking or same-size replacement on a source within the bound is never refused")
    void shrinkingReplacementWithinTheBound() {
        assertEquals("b".repeat(30), StringTemplateExtensions.replace(SIXTY, "a", ""));
        assertEquals("xb".repeat(30), StringTemplateExtensions.replace(SIXTY, "a", "x"));
    }

    @Test
    @DisplayName("replace: a source already past the bound is checked even when the replacement shrinks it")
    void shrinkingReplacementOnAnOversizedSource() {
        TemplateOutputLimit.configure(10);
        // 60 → 30 characters: still over 10, refused before allocating.
        assertThrows(TemplateLimitExceededException.class, () -> StringTemplateExtensions.replace(SIXTY, "a", ""));
        // 60 → 0 characters: within the bound, allowed.
        assertEquals("", StringTemplateExtensions.replace(SIXTY, "ab", ""));
    }

    @Test
    @DisplayName("replace: an empty target counts a match between every character and at both ends")
    void emptyTarget() {
        TemplateOutputLimit.configure(10);
        // 3 + 4 × 2 = 11 > 10
        assertThrows(TemplateLimitExceededException.class, () -> StringTemplateExtensions.replace("abc", "", "xx"));
        TemplateOutputLimit.configure(11);
        assertEquals("xxaxxbxxcxx", StringTemplateExtensions.replace("abc", "", "xx"));
    }

    @Test
    @DisplayName("concat: over the bound is refused, within it concatenates")
    void concat() {
        assertThrows(TemplateLimitExceededException.class, () -> StringTemplateExtensions.concat(SIXTY, "c".repeat(41)));
        assertEquals(SIXTY + "c".repeat(40), StringTemplateExtensions.concat(SIXTY, "c".repeat(40)));
    }

    @Test
    @DisplayName("null receivers behave as before")
    void nullReceiver() {
        assertNull(StringTemplateExtensions.replace(null, "a", "b"));
        assertEquals("suffix", StringTemplateExtensions.concat(null, "suffix"));
    }

    @Test
    @DisplayName("zero disables the pre-check")
    void zeroDisables() {
        TemplateOutputLimit.configure(0);
        assertEquals(180, StringTemplateExtensions.replace(SIXTY, "a", "aaaaa").length());
    }
}
