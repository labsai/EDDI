package ai.labs.eddi.configs.dictionary.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DictionaryConfigurationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultConstructor() {
        var config = new DictionaryConfiguration();
        assertNull(config.getLang());
        assertNotNull(config.getWords());
        assertTrue(config.getWords().isEmpty());
        assertNotNull(config.getRegExs());
        assertTrue(config.getRegExs().isEmpty());
        assertNotNull(config.getPhrases());
        assertTrue(config.getPhrases().isEmpty());
    }

    @Test
    void setters() {
        var config = new DictionaryConfiguration();
        config.setLang("en");
        assertEquals("en", config.getLang());
    }

    // --- WordConfiguration ---

    @Nested
    class WordTests {

        @Test
        void settersAndGetters() {
            var w = new DictionaryConfiguration.WordConfiguration();
            w.setWord("hello");
            w.setExpressions("greeting(hello)");
            w.setFrequency(5);
            assertEquals("hello", w.getWord());
            assertEquals("greeting(hello)", w.getExpressions());
            assertEquals(5, w.getFrequency());
        }

        @Test
        void equality_sameWord() {
            var w1 = new DictionaryConfiguration.WordConfiguration();
            w1.setWord("hello");
            var w2 = new DictionaryConfiguration.WordConfiguration();
            w2.setWord("hello");
            assertEquals(w1, w2);
            assertEquals(w1.hashCode(), w2.hashCode());
        }

        @Test
        void equality_differentWord() {
            var w1 = new DictionaryConfiguration.WordConfiguration();
            w1.setWord("hello");
            var w2 = new DictionaryConfiguration.WordConfiguration();
            w2.setWord("goodbye");
            assertNotEquals(w1, w2);
        }

        @Test
        void compareTo() {
            var w1 = new DictionaryConfiguration.WordConfiguration();
            w1.setWord("apple");
            var w2 = new DictionaryConfiguration.WordConfiguration();
            w2.setWord("banana");
            assertTrue(w1.compareTo(w2) < 0);
            assertTrue(w2.compareTo(w1) > 0);
            assertEquals(0, w1.compareTo(w1));
        }

        @Test
        void jackson() throws Exception {
            var w = new DictionaryConfiguration.WordConfiguration();
            w.setWord("test");
            w.setExpressions("exp(test)");
            String json = mapper.writeValueAsString(w);
            var restored = mapper.readValue(json, DictionaryConfiguration.WordConfiguration.class);
            assertEquals("test", restored.getWord());
            assertEquals("exp(test)", restored.getExpressions());
        }
    }

    // --- RegExConfiguration ---

    @Nested
    class RegExTests {

        @Test
        void settersAndGetters() {
            var r = new DictionaryConfiguration.RegExConfiguration();
            r.setRegEx("\\d+");
            r.setExpressions("number(*)");
            assertEquals("\\d+", r.getRegEx());
            assertEquals("number(*)", r.getExpressions());
        }

        @Test
        void equality_sameRegex() {
            var r1 = new DictionaryConfiguration.RegExConfiguration();
            r1.setRegEx("\\d+");
            var r2 = new DictionaryConfiguration.RegExConfiguration();
            r2.setRegEx("\\d+");
            assertEquals(r1, r2);
            assertEquals(r1.hashCode(), r2.hashCode());
        }

        @Test
        void compareTo() {
            var r1 = new DictionaryConfiguration.RegExConfiguration();
            r1.setRegEx("aaa");
            var r2 = new DictionaryConfiguration.RegExConfiguration();
            r2.setRegEx("zzz");
            assertTrue(r1.compareTo(r2) < 0);
        }
    }

    // --- PhraseConfiguration ---

    @Nested
    class PhraseTests {

        @Test
        void settersAndGetters() {
            var p = new DictionaryConfiguration.PhraseConfiguration();
            p.setPhrase("good morning");
            p.setExpressions("greeting(good_morning)");
            assertEquals("good morning", p.getPhrase());
            assertEquals("greeting(good_morning)", p.getExpressions());
        }

        @Test
        void equality_samePhrase() {
            var p1 = new DictionaryConfiguration.PhraseConfiguration();
            p1.setPhrase("good morning");
            var p2 = new DictionaryConfiguration.PhraseConfiguration();
            p2.setPhrase("good morning");
            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
        }

        @Test
        void equality_differentPhrase() {
            var p1 = new DictionaryConfiguration.PhraseConfiguration();
            p1.setPhrase("good morning");
            var p2 = new DictionaryConfiguration.PhraseConfiguration();
            p2.setPhrase("good evening");
            assertNotEquals(p1, p2);
        }
    }

    // --- Full config with lists ---

    @Test
    void fullConfigWithLists() {
        var config = new DictionaryConfiguration();
        config.setLang("de");

        var word = new DictionaryConfiguration.WordConfiguration();
        word.setWord("hallo");
        word.setExpressions("greeting(hallo)");
        config.setWords(List.of(word));

        var regex = new DictionaryConfiguration.RegExConfiguration();
        regex.setRegEx("\\d+");
        config.setRegExs(List.of(regex));

        var phrase = new DictionaryConfiguration.PhraseConfiguration();
        phrase.setPhrase("guten morgen");
        config.setPhrases(List.of(phrase));

        assertEquals("de", config.getLang());
        assertEquals(1, config.getWords().size());
        assertEquals(1, config.getRegExs().size());
        assertEquals(1, config.getPhrases().size());
    }
}
