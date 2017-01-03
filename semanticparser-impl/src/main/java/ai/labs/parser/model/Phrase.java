package ai.labs.parser.model;

import io.sls.expressions.Expression;

import java.util.Arrays;
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
    public IDictionary.IWord[] getWords() {
        String[] splittedPhrase = value.split(" ");
        List<IDictionary.IWord> words = new LinkedList<IDictionary.IWord>();
        for (final String word : splittedPhrase) {
            words.add(new Word(word, Arrays.asList(expressionUtilities.createExpression("unused", word)), null, 0, true));
        }

        return words.toArray(new IDictionary.IWord[words.size()]);
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
