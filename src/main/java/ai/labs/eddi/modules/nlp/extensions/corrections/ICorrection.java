package ai.labs.eddi.modules.nlp.extensions.corrections;


import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public interface ICorrection {

    void init(List<IDictionary> dictionaries);

    default List<IDictionary.IFoundWord> correctWord(String word, String userLanguage) {
        return correctWord(word, userLanguage, new LinkedList<>());
    }

    List<IDictionary.IFoundWord> correctWord(String word, String userLanguage,
                                             List<IDictionary> temporaryDictionaries);

    boolean lookupIfKnown();
}
