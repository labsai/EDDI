/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySearchTermsTest {

    @Test
    @DisplayName("'dog name', 'dog_name' and 'Dog-Name!' are the same search — the E2E miss")
    void separatorsDoNotMatter() {
        assertEquals(List.of("dog", "name"), MemorySearchTerms.tokenize("dog name"));
        assertEquals(List.of("dog", "name"), MemorySearchTerms.tokenize("dog_name"));
        assertEquals(List.of("dog", "name"), MemorySearchTerms.tokenize("  Dog-Name! "));
    }

    @Test
    @DisplayName("terms are distinct, lower-cased and capped")
    void distinctLowerCasedAndCapped() {
        assertEquals(List.of("a", "b"), MemorySearchTerms.tokenize("a A b a"));
        assertEquals(MemorySearchTerms.MAX_TERMS, MemorySearchTerms.tokenize("1 2 3 4 5 6 7 8 9 10 11").size());
    }

    @Test
    @DisplayName("non-ASCII letters are part of a term, and LIKE/regex metacharacters never are")
    void unicodeLettersAndMetacharacters() {
        assertEquals(List.of("straße", "café"), MemorySearchTerms.tokenize("Straße café"));
        assertEquals(List.of("50", "off"), MemorySearchTerms.tokenize("50% _off_ .*"));
    }

    @Test
    @DisplayName("a blank or punctuation-only query has no terms")
    void blankHasNoTerms() {
        assertTrue(MemorySearchTerms.tokenize(null).isEmpty());
        assertTrue(MemorySearchTerms.tokenize("   ").isEmpty());
        assertTrue(MemorySearchTerms.tokenize("%_!?").isEmpty());
    }
}
