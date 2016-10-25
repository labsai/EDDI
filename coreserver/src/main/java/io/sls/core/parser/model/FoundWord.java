package io.sls.core.parser.model;

import io.sls.expressions.Expression;

import java.util.List;

/**
 * @author ginccc
 */
public class FoundWord extends FoundDictionaryEntry implements IDictionary.IFoundWord {
    private final IDictionary.IWord word;

    public FoundWord(IDictionary.IWord word, boolean corrected, double matchingAccuracy) {
        super(word, corrected, matchingAccuracy);
        this.word = word;
    }

    public FoundWord(String unknownValue, List<Expression> unknownExp) {
        super(unknownValue, unknownExp, false, 0.0);
        word = null;
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

        return value != null ? value.equals(foundWord.value) : foundWord.value == null;

    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
