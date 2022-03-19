package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.Objects;

/**
 * @author ginccc
 */
public class FoundPhrase extends FoundDictionaryEntry implements IDictionary.IFoundPhrase {
    private final IDictionary.IPhrase phrase;

    public FoundPhrase(IDictionary.IPhrase phrase, boolean corrected, double matchingAccuracy) {
        super(phrase, corrected, matchingAccuracy);
        this.phrase = phrase;
        isPhrase = true;
    }

    @Override
    public IDictionary.IPhrase getFoundPhrase() {
        return phrase;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        FoundPhrase that = (FoundPhrase) o;

        return Objects.equals(value, that.value);

    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public IDictionary.IWord getFoundWord() {
        return getFoundPhrase();
    }
}
