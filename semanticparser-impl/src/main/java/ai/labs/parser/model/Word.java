package ai.labs.parser.model;

import ai.labs.expressions.Expressions;
import ai.labs.parser.extensions.dictionaries.IDictionary;

/**
 * @author ginccc
 */
public class Word extends DictionaryEntry implements IDictionary.IWord {
    private boolean isPartOfPhrase;

    public Word(String value, Expressions expressions, String identifier) {
        this(value, expressions, identifier, 0, false);
    }

    public Word(String value, Expressions expression, String identifier, int frequency, boolean partOfPhrase) {
        super(value, expression, identifier, frequency);
        isPartOfPhrase = partOfPhrase;

        isWord = !isPartOfPhrase;
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
        return Integer.compare(frequency, o.getFrequency());
    }
}
