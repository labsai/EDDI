/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DictionaryEntry Tests")
class DictionaryEntryTest {

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    /**
     * Helper that creates a DictionaryEntry directly (package-private constructor).
     * Using 2-arg constructor which defaults to languageCode="en", rating=0.
     */
    private DictionaryEntry entry(String value, Expressions expressions) {
        return new DictionaryEntry(value, expressions) {
        };
    }

    /**
     * Helper that creates a DictionaryEntry with all fields. Using 4-arg
     * constructor.
     */
    private DictionaryEntry entry(String value, Expressions expressions, String languageCode, int rating) {
        return new DictionaryEntry(value, expressions, languageCode, rating) {
        };
    }

    // ==================== Constructors ====================

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("two-arg constructor sets default language and rating")
        void twoArgConstructor() {
            var e = entry("hello", new Expressions());
            assertEquals("hello", e.getValue());
            assertEquals("en", e.getLanguageCode());
        }

        @Test
        @DisplayName("four-arg constructor sets all fields")
        void fourArgConstructor() {
            var expressions = new Expressions();
            var e = entry("hello", expressions, "de", 5);
            assertEquals("hello", e.getValue());
            assertEquals("de", e.getLanguageCode());
            assertSame(expressions, e.getExpressions());
        }
    }

    // ==================== Getters and Setters ====================

    @Nested
    @DisplayName("Getter/Setter Tests")
    class GetterSetterTests {

        @Test
        @DisplayName("getValue returns the value")
        void getValue() {
            assertEquals("hello", entry("hello", new Expressions()).getValue());
        }

        @Test
        @DisplayName("setValue changes the value")
        void setValue() {
            var e = entry("hello", new Expressions());
            e.setValue("world");
            assertEquals("world", e.getValue());
        }

        @Test
        @DisplayName("isWord returns false by default")
        void isWordDefaultFalse() {
            assertFalse(entry("hello", new Expressions()).isWord());
        }

        @Test
        @DisplayName("isPhrase returns false by default")
        void isPhraseDefaultFalse() {
            assertFalse(entry("hello", new Expressions()).isPhrase());
        }

        @Test
        @DisplayName("getFrequency returns 0 by default")
        void getFrequency() {
            assertEquals(0, entry("hello", new Expressions()).getFrequency());
        }

        @Test
        @DisplayName("getLanguageCode returns the set language")
        void getLanguageCode() {
            assertEquals("fr", entry("hello", new Expressions(), "fr", 0).getLanguageCode());
        }
    }

    // ==================== equals ====================

    @Nested
    @DisplayName("equals Tests")
    class EqualsTests {

        @Test
        @DisplayName("same instance returns true")
        void sameInstance() {
            var e = entry("hello", new Expressions());
            assertEquals(e, e);
        }

        @Test
        @DisplayName("null returns false")
        void nullReturns() {
            var e = entry("hello", new Expressions());
            assertNotEquals(null, e);
        }

        @Test
        @DisplayName("different class returns false")
        void differentClass() {
            var e = entry("hello", new Expressions());
            assertNotEquals("hello", e);
        }

        @Test
        @DisplayName("equal entries return true")
        void equalEntries() {
            var e1 = entry("hello", new Expressions());
            var e2 = entry("hello", new Expressions());
            assertEquals(e1, e2);
        }

        @Test
        @DisplayName("different value returns false")
        void differentValue() {
            var e1 = entry("hello", new Expressions());
            var e2 = entry("world", new Expressions());
            assertNotEquals(e1, e2);
        }

        @Test
        @DisplayName("different frequency returns false")
        void differentFrequency() {
            var e1 = entry("hello", new Expressions());
            var e2 = entry("hello", new Expressions());
            e2.frequency = 5;
            assertNotEquals(e1, e2);
        }

        @Test
        @DisplayName("different rating returns false")
        void differentRating() {
            var e1 = entry("hello", new Expressions(), "en", 0);
            var e2 = entry("hello", new Expressions(), "en", 5);
            assertNotEquals(e1, e2);
        }

        @Test
        @DisplayName("different languageCode returns false")
        void differentLanguageCode() {
            var e1 = entry("hello", new Expressions(), "en", 0);
            var e2 = entry("hello", new Expressions(), "de", 0);
            assertNotEquals(e1, e2);
        }

        @Test
        @DisplayName("null value in one entry, non-null in other returns false")
        void nullValueVsNonNull() {
            var e1 = entry("hello", new Expressions());
            var e2 = entry(null, new Expressions());
            assertNotEquals(e1, e2);
        }

        @Test
        @DisplayName("both null values returns true")
        void bothNullValues() {
            var e1 = entry(null, new Expressions());
            var e2 = entry(null, new Expressions());
            assertEquals(e1, e2);
        }

        @Test
        @DisplayName("null languageCode in one, non-null in other returns false")
        void nullLanguageCode() {
            var e1 = entry("hello", new Expressions(), null, 0);
            var e2 = entry("hello", new Expressions(), "en", 0);
            assertNotEquals(e1, e2);
        }

        @Test
        @DisplayName("null expressions in one, non-null in other returns false")
        void nullExpressions() {
            var e1 = entry("hello", null, "en", 0);
            var e2 = entry("hello", new Expressions(), "en", 0);
            assertNotEquals(e1, e2);
        }

        @Test
        @DisplayName("both null expressions returns true")
        void bothNullExpressions() {
            var e1 = entry("hello", null, "en", 0);
            var e2 = entry("hello", null, "en", 0);
            assertEquals(e1, e2);
        }

        @Test
        @DisplayName("both null language codes returns true")
        void bothNullLanguageCodes() {
            var e1 = entry("hello", new Expressions(), null, 0);
            var e2 = entry("hello", new Expressions(), null, 0);
            assertEquals(e1, e2);
        }

        @Test
        @DisplayName("Word subclass equals different from DictionaryEntry equals")
        void wordSubclassDifferent() {
            // Word overrides equals - compares only value
            var w1 = new Word("hello", new Expressions(), "en", 0, false);
            var w2 = new Word("hello", new Expressions(), "de", 5, false);
            assertEquals(w1, w2); // Word.equals only compares value
        }
    }

    // ==================== hashCode ====================

    @Nested
    @DisplayName("hashCode Tests")
    class HashCodeTests {

        @Test
        @DisplayName("equal entries have same hashCode")
        void equalEntriesSameHashCode() {
            var e1 = entry("hello", new Expressions());
            var e2 = entry("hello", new Expressions());
            assertEquals(e1.hashCode(), e2.hashCode());
        }

        @Test
        @DisplayName("null value hashCode is computed without NPE")
        void nullValueHashCode() {
            var e = entry(null, null, null, 0);
            // Should not throw
            int hash = e.hashCode();
            assertEquals(0, hash);
        }

        @Test
        @DisplayName("non-null fields contribute to hashCode")
        void nonNullFieldsContribute() {
            var e1 = entry("hello", new Expressions(), "en", 0);
            var e2 = entry("world", new Expressions(), "en", 0);
            assertNotEquals(e1.hashCode(), e2.hashCode());
        }
    }

    // ==================== toString ====================

    @Nested
    @DisplayName("toString Tests")
    class ToStringTests {

        @Test
        @DisplayName("toString returns the value")
        void toStringReturnsValue() {
            assertEquals("hello", entry("hello", new Expressions()).toString());
        }

        @Test
        @DisplayName("toString returns null when value is null")
        void toStringNullValue() {
            assertNull(entry(null, new Expressions()).toString());
        }
    }

    // ==================== compareTo ====================

    @Nested
    @DisplayName("compareTo Tests")
    class CompareToTests {

        @Test
        @DisplayName("same frequency compares by value")
        void sameFrequencyComparesValue() {
            var e1 = entry("apple", new Expressions());
            var e2 = entry("banana", new Expressions());
            // Both have frequency=0, so compareTo should compare by value
            assertTrue(e1.compareTo(e2) < 0);
        }

        @Test
        @DisplayName("different frequency compares by frequency")
        void differentFrequencyComparesFrequency() {
            var e1 = entry("apple", new Expressions());
            e1.frequency = 1;
            var e2 = entry("banana", new Expressions());
            e2.frequency = 5;
            assertTrue(e1.compareTo(e2) < 0);
        }

        @Test
        @DisplayName("same frequency and value returns 0")
        void sameFrequencyAndValue() {
            var e1 = entry("hello", new Expressions());
            var e2 = entry("hello", new Expressions());
            assertEquals(0, e1.compareTo(e2));
        }

        @Test
        @DisplayName("higher frequency compares greater")
        void higherFrequencyComparesGreater() {
            var e1 = entry("apple", new Expressions());
            e1.frequency = 10;
            var e2 = entry("banana", new Expressions());
            e2.frequency = 5;
            assertTrue(e1.compareTo(e2) > 0);
        }

        @Test
        @DisplayName("compareTo with mock IDictionaryEntry")
        void compareToWithMock() {
            var e1 = entry("apple", new Expressions());
            e1.frequency = 3;
            var mockEntry = mock(IDictionary.IDictionaryEntry.class);
            when(mockEntry.getFrequency()).thenReturn(3);
            when(mockEntry.getValue()).thenReturn("banana");
            assertTrue(e1.compareTo(mockEntry) < 0);
        }
    }

    // ==================== Word-specific tests ====================

    @Nested
    @DisplayName("Word subclass tests")
    class WordTests {

        @Test
        @DisplayName("Word.isWord returns true when not part of phrase")
        void wordIsWord() {
            var w = new Word("hello", new Expressions(), "en", 0, false);
            assertTrue(w.isWord());
        }

        @Test
        @DisplayName("Word.isWord returns false when part of phrase")
        void wordIsNotWordWhenPartOfPhrase() {
            var w = new Word("hello", new Expressions(), "en", 0, true);
            assertFalse(w.isWord());
        }

        @Test
        @DisplayName("Word.compareTo compares by frequency only")
        void wordCompareTo() {
            var w1 = new Word("apple", new Expressions(), "en", 0, false);
            w1.frequency = 1;
            var w2 = new Word("banana", new Expressions(), "en", 0, false);
            w2.frequency = 5;
            assertTrue(w1.compareTo(w2) < 0);
        }
    }

    // ==================== Phrase tests ====================

    @Nested
    @DisplayName("Phrase tests")
    class PhraseTests {

        @Test
        @DisplayName("Phrase.getWords splits by space")
        void phraseGetWords() {
            var p = new Phrase("hello world", new Expressions(), "en");
            var words = p.getWords();
            assertEquals(2, words.size());
            assertEquals("hello", words.get(0).getValue());
            assertEquals("world", words.get(1).getValue());
        }

        @Test
        @DisplayName("Phrase.equals compares by value")
        void phraseEquals() {
            var p1 = new Phrase("hello world", new Expressions(), "en");
            var p2 = new Phrase("hello world", new Expressions(), "de");
            assertEquals(p1, p2);
        }

        @Test
        @DisplayName("Phrase.isPartOfPhrase returns false")
        void phraseIsNotPartOfPhrase() {
            var p = new Phrase("hello world", new Expressions(), "en");
            assertFalse(p.isPartOfPhrase());
        }
    }
}
