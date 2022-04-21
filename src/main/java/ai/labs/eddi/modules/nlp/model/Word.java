package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

/**
 * @author ginccc
 */
public class Word extends DictionaryEntry implements IDictionary.IWord {
    private final boolean isPartOfPhrase;

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
