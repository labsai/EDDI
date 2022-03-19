package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.Objects;

/**
 * @author ginccc
 */
public class FoundWord extends FoundDictionaryEntry implements IDictionary.IFoundWord {
    private final IDictionary.IWord word;

    public FoundWord(IDictionary.IWord word, boolean corrected, double matchingAccuracy) {
        super(word, corrected, matchingAccuracy);
        this.word = word;
        isWord = true;
    }

    public FoundWord(String unknownValue, Expressions unknownExp) {
        super(unknownValue, unknownExp);
        word = null;
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

        FoundWord foundWord = (FoundWord) o;

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
}
