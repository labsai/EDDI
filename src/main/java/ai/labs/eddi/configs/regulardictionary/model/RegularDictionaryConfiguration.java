package ai.labs.eddi.configs.regulardictionary.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class RegularDictionaryConfiguration {
    private String lang;
    private List<WordConfiguration> words;
    private List<RegExConfiguration> regExs;
    private List<PhraseConfiguration> phrases;

    public RegularDictionaryConfiguration() {
        this.words = new ArrayList<>();
        this.regExs = new ArrayList<>();
        this.phrases = new ArrayList<>();
    }

    @Getter
    @Setter
    @JsonClassDescription("A word definition of the dictionary.")
    public static class WordConfiguration implements Comparable<WordConfiguration> {
        @JsonPropertyDescription(
                "A word of a natural language such as German or English" +
                        " that you want the parser to recognize (e.g. hello).")

        @NotNull
        private String word;
        @JsonPropertyDescription(
                "Prolog like expressions describing the meaning of this word " +
                        "(e.g. greeting(hello) or property(car(BMW(X5))) )")
        @JsonProperty(defaultValue = "unused")
        @JsonAlias({"exp", "exps"})
        private String expressions;
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

        @Override
        public int compareTo(WordConfiguration o) {
            return word.compareTo(o.getWord());
        }
    }

    @Getter
    @Setter
    @JsonClassDescription("A RegEx definition of the dictionary.")
    public static class RegExConfiguration implements Comparable<RegExConfiguration> {
        @JsonPropertyDescription(
                "A regular expression (regEx) e.g. \"(\\\\w)(\\\\s+)([\\\\.,])\"")

        @NotNull
        private String regEx;
        @JsonPropertyDescription(
                "Prolog like expressions describing the meaning of this word " +
                        "(e.g. greeting(hello) or property(car(BMW(X5))) )")
        @JsonProperty(defaultValue = "unused")
        @JsonAlias({"exp", "exps"})
        private String expressions;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RegExConfiguration that = (RegExConfiguration) o;

            return regEx.equals(that.regEx);
        }

        @Override
        public int hashCode() {
            return regEx.hashCode();
        }

        @Override
        public int compareTo(RegExConfiguration o) {
            return regEx.compareTo(o.getRegEx());
        }
    }

    @Getter
    @Setter
    @JsonClassDescription("A phrase definition of the dictionary.")
    public static class PhraseConfiguration {
        @JsonPropertyDescription(
                "A phrase of a natural language such as German or English" +
                        " that you want the parser to recognize (e.g. good morning).")
        @NotNull
        protected String phrase;

        @JsonPropertyDescription(
                "A prolog like expressions describing the meaning of this word " +
                        "(e.g. greeting(good_morning) )")
        @JsonProperty(defaultValue = "unused")
        @JsonAlias({"exp", "exps"})
        protected String expressions;

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
