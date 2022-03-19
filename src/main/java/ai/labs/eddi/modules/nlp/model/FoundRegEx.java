package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.Objects;

/**
 * @author ginccc
 */
public class FoundRegEx extends FoundWord implements IDictionary.IFoundRegEx {
    private final IDictionary.IWord word;
    private final IDictionary.IRegEx regEx;

    public FoundRegEx(IDictionary.IWord word, IDictionary.IRegEx regEx) {
        super(word, false, 1.0);
        this.word = word;
        this.regEx = regEx;
        isWord = true;
    }

    @Override
    public IDictionary.IWord getFoundWord() {
        return word;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        FoundRegEx foundWord = (FoundRegEx) o;

        return Objects.equals(value, foundWord.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public boolean isWord() {
        return word.isWord();
    }

    @Override
    public boolean isPhrase() {
        return word.isPhrase();
    }

    @Override
    public IDictionary.IRegEx getMatchingRegEx() {
        return regEx;
    }
}
