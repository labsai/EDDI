package io.sls.core.parser.model;

/**
 * User: jarisch
 * Date: 25.01.13
 * Time: 17:22
 */
public class FoundPhrase extends FoundDictionaryEntry implements IDictionary.IFoundPhrase {
    private final IDictionary.IPhrase phrase;

    public FoundPhrase(IDictionary.IPhrase phrase, boolean corrected, double matchingAccuracy) {
        super(phrase, corrected, matchingAccuracy);
        this.phrase = phrase;
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

        return value != null ? value.equals(that.value) : that.value == null;

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
