package ai.labs.eddi.configs.regulardictionary.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaDefault;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaDescription;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaInject;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaString;
import lombok.Getter;
import lombok.Setter;

import jakarta.validation.constraints.NotNull;
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
    @JsonSchemaDescription(value = "A word definition of the dictionary.")
    public static class WordConfiguration implements Comparable<WordConfiguration> {
        @JsonSchemaDescription(value =
                "A word of a natural language such as German or English" +
                        " that you want the parser to recognize (e.g. hello).")

        @NotNull
        private String word;
        @JsonSchemaDescription(value =
                "Prolog like expressions describing the meaning of this word " +
                        "(e.g. greeting(hello) or property(car(BMW(X5))) )")
        @JsonSchemaDefault(value = "unused")
        @JsonSchemaInject(strings = {@JsonSchemaString(path = "patternProperties/^[^ ]{2,}(\\\\([^ ]{2,}\\\\))$/type", value = "string")})
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
    @JsonSchemaDescription(value = "A RegEx definition of the dictionary.")
    public static class RegExConfiguration implements Comparable<RegExConfiguration> {
        @JsonSchemaDescription(value =
                "A regular expression (regEx) e.g. \"(\\\\w)(\\\\s+)([\\\\.,])\"")

        @NotNull
        private String regEx;
        @JsonSchemaDescription(value =
                "Prolog like expressions describing the meaning of this word " +
                        "(e.g. greeting(hello) or property(car(BMW(X5))) )")
        @JsonSchemaDefault(value = "unused")
        @JsonSchemaInject(strings = {@JsonSchemaString(path = "patternProperties/^[^ ]{2,}(\\\\([^ ]{2,}\\\\))$/type", value = "string")})
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
    @JsonSchemaDescription(value = "A phrase definition of the dictionary.")
    public static class PhraseConfiguration {
        @JsonSchemaDescription(value =
                "A phrase of a natural language such as German or English" +
                        " that you want the parser to recognize (e.g. good morning).")
        @NotNull
        protected String phrase;

        @JsonSchemaDescription(value =
                "A prolog like expressions describing the meaning of this word " +
                        "(e.g. greeting(good_morning) )")
        @JsonSchemaDefault(value = "unused")
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
