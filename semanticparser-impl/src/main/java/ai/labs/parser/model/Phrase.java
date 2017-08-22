package ai.labs.parser.model;

import ai.labs.expressions.Expression;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class Phrase extends DictionaryEntry implements IDictionary.IPhrase {

    public Phrase(String value, List<Expression> expressions, String identifier) {
        super(value, expressions, identifier, 0);
    }

    @Override
    public List<IDictionary.IWord> getWords() {
        String[] splitPhrase = value.split(" ");
        List<IDictionary.IWord> words = new LinkedList<>();
        Arrays.stream(splitPhrase).forEach(word -> {
            Expression unusedExp = new Expression("unused", new Expression(word));
            words.add(new Word(word, Collections.singletonList(unusedExp), null, 0, true));
        });

        return words;
    }

    @Override
    public List<Expression> getExpressions() {
        return expressions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IDictionary.IPhrase)) return false;

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
