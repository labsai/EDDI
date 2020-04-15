package ai.labs.parser.extensions.corrections;

import ai.labs.parser.extensions.dictionaries.IDictionary;

import java.util.List;

/**
 * @author ginccc
 */
public interface ICorrection {

    void init(List<IDictionary> dictionaries);

    List<IDictionary.IFoundWord> correctWord(String word);

    default List<IDictionary.IFoundWord> correctWord(String word, List<IDictionary> temporaryDictionaries) {
        return correctWord(word);
    }

    boolean lookupIfKnown();
}
