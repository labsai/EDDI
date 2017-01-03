package ai.labs.parser.model;

import io.sls.expressions.Expression;

import java.util.List;

/**
 * @author ginccc
 */
public interface IDictionary {
    IFoundWord[] NO_WORDS_FOUND = new IFoundWord[0];

    IFoundPhrase[] NO_PHRASES_FOUND = new IFoundPhrase[0];

    List<IWord> getWords();

    List<IPhrase> getPhrases();

    IFoundWord[] lookupTerm(String value);

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
        IWord[] getWords();
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
