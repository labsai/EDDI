package ai.labs.resources.rest.config.regulardictionary.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.imifou.jsonschema.module.addon.annotation.JsonSchema;
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
    @JsonClassDescription(value = "A word definition of the dictionary.")
    public static class WordConfiguration implements Comparable<WordConfiguration> {
        @JsonPropertyDescription(value =
                "A word of a natural language such as German or English" +
                        " that you want the parser to recognize (e.g. hello).")

        @NotNull
        private String word;
        @JsonPropertyDescription(value =
                "Prolog like expressions describing the meaning of this word " +
                        "(e.g. greeting(hello) or property(car(BMW(X5))) )")
        @JsonSchema(defaultValue = "unused", pattern = "^[^ ]{2,}(\\([^ ]{2,}\\))$")
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
    @JsonClassDescription(value = "A RegEx definition of the dictionary.")
    public static class RegExConfiguration implements Comparable<RegExConfiguration> {
        @JsonPropertyDescription(value =
                "A regular expression (regEx) e.g. \"(\\\\w)(\\\\s+)([\\\\.,])\"")

        @NotNull
        private String regEx;
        @JsonPropertyDescription(value =
                "Prolog like expressions describing the meaning of this word " +
                        "(e.g. greeting(hello) or property(car(BMW(X5))) )")
        @JsonSchema(defaultValue = "unused", pattern = "^[^ ]{2,}(\\([^ ]{2,}\\))$")
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
    @JsonClassDescription(value = "A phrase definition of the dictionary.")
    public static class PhraseConfiguration {
        @JsonPropertyDescription(value =
                "A phrase of a natural language such as German or English" +
                        " that you want the parser to recognize (e.g. good morning).")
        @NotNull
        protected String phrase;

        @JsonPropertyDescription(value =
                "A prolog like expressions describing the meaning of this word " +
                        "(e.g. greeting(good_morning) )")
        @JsonSchema(defaultValue = "unused", pattern = "^[^ ]{2,}(\\([^ ]{2,}\\))$")
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
