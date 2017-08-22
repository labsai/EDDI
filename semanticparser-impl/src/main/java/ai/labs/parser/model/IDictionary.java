package ai.labs.parser.model;

import ai.labs.expressions.Expression;

import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
public interface IDictionary {
    List<IFoundWord> NO_WORDS_FOUND = Collections.emptyList();

    List<IWord> getWords();

    List<IPhrase> getPhrases();

    List<IFoundWord> lookupTerm(String value);

    String getLanguage();

    boolean lookupIfKnown();

    interface IDictionaryEntry extends Comparable<IDictionary.IDictionaryEntry> {
        String getValue();

        List<Expression> getExpressions();

        String getIdentifier();

        boolean isWord();

        boolean isPhrase();

        int getFrequency();
    }

    interface IWord extends IDictionaryEntry {
        boolean isPartOfPhrase();

        int getFrequency();
    }

    interface IPhrase extends IWord {
        List<IWord> getWords();
    }

    interface IFoundDictionaryEntry extends IDictionaryEntry {
    }

    interface IFoundWord extends IFoundDictionaryEntry {
        IWord getFoundWord();
    }

    interface IFoundPhrase extends IFoundWord {
        IPhrase getFoundPhrase();
    }
}
