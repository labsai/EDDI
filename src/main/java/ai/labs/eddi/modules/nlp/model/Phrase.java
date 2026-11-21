/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author ginccc
 */
public class Phrase extends DictionaryEntry implements IDictionary.IPhrase {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * The phrase's words, built once. {@link #getWords()} is called for every
     * phrase on every input token (dictionary lookups, phrase matching, the
     * corrections' word lists), and it used to split the value and allocate a new
     * {@link Word} per word on each call. {@code volatile}: a phrase belongs to a
     * dictionary that every conversation of the workflow reads concurrently.
     */
    private volatile List<IDictionary.IWord> words;

    public Phrase(String value, Expressions expressions, String identifier) {
        super(value, expressions, identifier, 0);
    }

    /**
     * The words of the phrase, split on any run of whitespace. Splitting on single
     * blanks gave a phrase written with two spaces an empty word, which no input
     * can ever supply (the parser collapses whitespace), so such a phrase could
     * never match.
     */
    @Override
    public List<IDictionary.IWord> getWords() {
        List<IDictionary.IWord> cached = words;
        if (cached == null) {
            List<IDictionary.IWord> built = new ArrayList<>();
            String trimmed = value == null ? "" : value.trim();
            if (!trimmed.isEmpty()) {
                for (String word : WHITESPACE.split(trimmed)) {
                    Expression unusedExp = new Expression("unused", new Expression(word));
                    built.add(new Word(word, new Expressions(unusedExp), null, 0, true));
                }
            }
            cached = Collections.unmodifiableList(built);
            words = cached;
        }
        return cached;
    }

    @Override
    public void setValue(String value) {
        super.setValue(value);
        words = null;
    }

    @Override
    public Expressions getExpressions() {
        return expressions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof IDictionary.IPhrase))
            return false;

        IDictionary.IPhrase that = (IDictionary.IPhrase) o;

        return value != null ? value.equals(that.getValue()) : that.getValue() == null;

    }

    @Override
    public int hashCode() {
        return value != null ? value.hashCode() : 0;
    }

    @Override
    public boolean isPartOfPhrase() {
        return false;
    }
}
