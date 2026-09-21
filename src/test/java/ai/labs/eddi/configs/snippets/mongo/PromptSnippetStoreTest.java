/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.snippets.mongo;

import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PromptSnippetStore} name validation.
 * <p>
 * These tests verify the name validation logic only (the CRUD operations
 * delegate to AbstractResourceStore which is tested separately). We can't
 * instantiate the full store without a MongoDB connection, so we test the
 * validation indirectly via the PromptSnippet model validation pattern.
 */
class PromptSnippetStoreTest {

    /**
     * Regex pattern duplicated from PromptSnippetStore for validation testing. In
     * production, the store enforces this on create/update.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]+");

    // ==================== Store boundary ====================

    @Nested
    @DisplayName("store rejects an invalid name as a caller error")
    class StoreBoundary {

        private PromptSnippetStore store;
        private IResourceStorage<PromptSnippet> resourceStorage;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
            resourceStorage = mock(IResourceStorage.class);
            when(storageFactory.create(eq("promptsnippets"), any(), eq(PromptSnippet.class))).thenReturn(resourceStorage);
            store = new PromptSnippetStore(storageFactory, mock(IDocumentBuilder.class));
        }

        /**
         * IllegalArgumentException maps to 400. The ResourceStoreException it used to
         * be maps to 500, which is what the E2E run observed for "Bad Name With
         * Spaces!".
         */
        @Test
        void createWithInvalidNameIsIllegalArgument() {
            var snippet = new PromptSnippet("Bad Name With Spaces!", null, null, "content", null, true);

            var e = assertThrows(IllegalArgumentException.class, () -> store.create(snippet));

            assertTrue(e.getMessage().contains("Bad Name With Spaces!"), e.getMessage());
            verifyNoInteractions(resourceStorage);
        }

        @Test
        void updateWithInvalidNameIsIllegalArgument() {
            var snippet = new PromptSnippet(null, null, null, "content", null, true);

            assertThrows(IllegalArgumentException.class, () -> store.update("aabbccddeeff00112233", 1, snippet));
            verifyNoInteractions(resourceStorage);
        }
    }

    // ==================== Name Validation ====================

    @Nested
    class NameValidation {

        @ParameterizedTest
        @ValueSource(strings = {"cautious_mode", "safety_rules", "tone_formal", "a", "abc123", "rule_42"})
        void shouldAcceptValidNames(String name) {
            assertTrue(NAME_PATTERN.matcher(name).matches(),
                    "Expected valid name: " + name);
        }

        @ParameterizedTest
        @ValueSource(strings = {"CautiousMode", "UPPERCASE", "camelCase"})
        void shouldRejectUppercaseNames(String name) {
            assertFalse(NAME_PATTERN.matcher(name).matches(),
                    "Expected rejection for uppercase: " + name);
        }

        @ParameterizedTest
        @ValueSource(strings = {"with-dash", "with.dot", "with space", "with/slash"})
        void shouldRejectSpecialCharacters(String name) {
            assertFalse(NAME_PATTERN.matcher(name).matches(),
                    "Expected rejection for special chars: " + name);
        }

        @Test
        void shouldRejectEmptyName() {
            assertFalse(NAME_PATTERN.matcher("").matches());
        }
    }

    // ==================== Model ====================

    @Nested
    class PromptSnippetModel {

        @Test
        void shouldCreateWithAllFields() {
            var snippet = new PromptSnippet("test_name", "governance", "A test snippet",
                    "You must verify facts.", List.of("safety", "production"), true);

            assertEquals("test_name", snippet.getName());
            assertEquals("governance", snippet.getCategory());
            assertEquals("A test snippet", snippet.getDescription());
            assertEquals("You must verify facts.", snippet.getContent());
            assertEquals(List.of("safety", "production"), snippet.getTags());
            assertTrue(snippet.isTemplateEnabled());
        }

        @Test
        void shouldDefaultTemplateEnabledToTrue() {
            var snippet = new PromptSnippet();
            assertTrue(snippet.isTemplateEnabled(), "templateEnabled should default to true");
        }

        @Test
        void shouldAllowSettingTemplateEnabledToFalse() {
            var snippet = new PromptSnippet();
            snippet.setTemplateEnabled(false);
            assertFalse(snippet.isTemplateEnabled());
        }

        @Test
        void shouldAllowNullOptionalFields() {
            var snippet = new PromptSnippet("minimal", null, null, "content only", null, true);

            assertEquals("minimal", snippet.getName());
            assertNull(snippet.getCategory());
            assertNull(snippet.getDescription());
            assertEquals("content only", snippet.getContent());
            assertNull(snippet.getTags());
        }
    }
}
