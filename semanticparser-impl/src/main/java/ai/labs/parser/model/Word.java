package ai.labs.parser.model;

import ai.labs.expressions.Expression;

import java.util.List;

/**
 * @author ginccc
 */
public class Word extends DictionaryEntry implements IDictionary.IWord {
    private boolean isPartOfPhrase;

    public Word(String value, List<Expression> expressions, String identifier) {
        this(value, expressions, identifier, 0, false);
    }

    public Word(String value, List<Expression> expression, String identifier, int frequency, boolean partOfPhrase) {
        super(value, expression, identifier, frequency);
        isPartOfPhrase = partOfPhrase;

        isWord = true;
        isPhrase = false;
    }

    @Override
    public boolean isPartOfPhrase() {
        return isPartOfPhrase;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IDictionary.IWord)) return false;

        IDictionary.IWord word = (IDictionary.IWord) o;

        return value.equals(word.getValue());

    }

    @Override
    public int hashCode() {
        return value != null ? value.hashCode() : 0;
    }

    @Override
    public int compareTo(IDictionary.IDictionaryEntry o) {
        return frequency < o.getFrequency() ? -1 : frequency == o.getFrequency() ? 0 : 1;
    }
}
