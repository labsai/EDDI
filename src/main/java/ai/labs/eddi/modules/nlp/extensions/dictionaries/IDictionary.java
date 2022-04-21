package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.modules.nlp.expressions.Expressions;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public interface IDictionary {
    List<IFoundWord> NO_WORDS_FOUND = Collections.emptyList();

    default List<IWord> getWords() {
        return Collections.emptyList();
    }

    default List<IPhrase> getPhrases() {
        return Collections.emptyList();
    }

    default String getLanguageCode() {
        return null;
    }

    List<IFoundWord> lookupTerm(String value);

    boolean lookupIfKnown();

    default void setConfig(Map<String, Object> config) throws IllegalExtensionConfigurationException {
        //to be overridden if needed
    }

    interface IDictionaryEntry extends Comparable<IDictionaryEntry> {
        String getValue();

        Expressions getExpressions();

        String getLanguageCode();

        boolean isWord();

        boolean isPhrase();

        int getFrequency();
    }

    interface IWord extends IDictionaryEntry {
        boolean isPartOfPhrase();

        int getFrequency();
    }

    interface IRegEx extends IWord {
        boolean match(String lookup);
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

    interface IFoundRegEx extends IFoundWord {
        IRegEx getMatchingRegEx();
    }
}
