/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.dictionary.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */

public class DictionaryConfiguration {
    private String lang;
    private List<WordConfiguration> words;
    private List<RegExConfiguration> regExs;
    private List<PhraseConfiguration> phrases;

    public DictionaryConfiguration() {
        this.words = new ArrayList<>();
        this.regExs = new ArrayList<>();
        this.phrases = new ArrayList<>();
    }

    @JsonClassDescription("A word definition of the dictionary.")
    public static class WordConfiguration implements Comparable<WordConfiguration> {
        @JsonPropertyDescription("A word of a natural language such as German or English" + " that you want the parser to recognize (e.g. hello).")

        private String word;
        @JsonPropertyDescription("Prolog like expressions describing the meaning of this word " + "(e.g. greeting(hello) or property(car(BMW(X5))) )")
        @JsonProperty(defaultValue = "unused")
        @JsonAlias({"exp", "exps"})
        private String expressions;
        private int frequency;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

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

        public String getWord() {
            return word;
        }

        public void setWord(String word) {
            this.word = word;
        }

        public String getExpressions() {
            return expressions;
        }

        public void setExpressions(String expressions) {
            this.expressions = expressions;
        }

        public int getFrequency() {
            return frequency;
        }

        public void setFrequency(int frequency) {
            this.frequency = frequency;
        }
    }

    @JsonClassDescription("A RegEx definition of the dictionary.")
    public static class RegExConfiguration implements Comparable<RegExConfiguration> {
        @JsonPropertyDescription("A regular expression (regEx) e.g. \"(\\\\w)(\\\\s+)([\\\\.,])\"")

        private String regEx;
        @JsonPropertyDescription("Prolog like expressions describing the meaning of this word " + "(e.g. greeting(hello) or property(car(BMW(X5))) )")
        @JsonProperty(defaultValue = "unused")
        @JsonAlias({"exp", "exps"})
        private String expressions;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

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

        public String getRegEx() {
            return regEx;
        }

        public void setRegEx(String regEx) {
            this.regEx = regEx;
        }

        public String getExpressions() {
            return expressions;
        }

        public void setExpressions(String expressions) {
            this.expressions = expressions;
        }
    }

    @JsonClassDescription("A phrase definition of the dictionary.")
    public static class PhraseConfiguration {
        @JsonPropertyDescription("A phrase of a natural language such as German or English"
                + " that you want the parser to recognize (e.g. good morning).")
        protected String phrase;

        @JsonPropertyDescription("A prolog like expressions describing the meaning of this word " + "(e.g. greeting(good_morning) )")
        @JsonProperty(defaultValue = "unused")
        @JsonAlias({"exp", "exps"})
        protected String expressions;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            PhraseConfiguration that = (PhraseConfiguration) o;

            return phrase.equals(that.phrase);
        }

        @Override
        public int hashCode() {
            return phrase.hashCode();
        }

        public String getPhrase() {
            return phrase;
        }

        public void setPhrase(String phrase) {
            this.phrase = phrase;
        }

        public String getExpressions() {
            return expressions;
        }

        public void setExpressions(String expressions) {
            this.expressions = expressions;
        }

    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public List<WordConfiguration> getWords() {
        return words;
    }

    public void setWords(List<WordConfiguration> words) {
        this.words = words;
    }

    public List<RegExConfiguration> getRegExs() {
        return regExs;
    }

    public void setRegExs(List<RegExConfiguration> regExs) {
        this.regExs = regExs;
    }

    public List<PhraseConfiguration> getPhrases() {
        return phrases;
    }

    public void setPhrases(List<PhraseConfiguration> phrases) {
        this.phrases = phrases;
    }
}
