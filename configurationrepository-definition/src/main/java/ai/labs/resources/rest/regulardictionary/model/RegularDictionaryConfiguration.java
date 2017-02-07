package ai.labs.resources.rest.regulardictionary.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class RegularDictionaryConfiguration {
    private String language;
    private List<WordConfiguration> words;
    private List<PhraseConfiguration> phrases;

    public RegularDictionaryConfiguration() {
        this.words = new ArrayList<>();
        this.phrases = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class WordConfiguration {
        private String word;
        private String exp;
        private int frequency;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            WordConfiguration that = (WordConfiguration) o;

            return word.equals(that.word);
        }

        @Override
        public int hashCode() {
            return word.hashCode();
        }
    }

    @Getter
    @Setter
    public static class PhraseConfiguration {
        protected String phrase;
        protected String exp;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PhraseConfiguration that = (PhraseConfiguration) o;

            return phrase.equals(that.phrase);
        }

        @Override
        public int hashCode() {
            return phrase.hashCode();
        }
    }
}
