package ai.labs.parser.correction;

import ai.labs.parser.model.IDictionary;

import java.util.List;

/**
 * @author ginccc
 */
public interface ICorrection {

    void init(List<IDictionary> dictionaries);

    List<IDictionary.IFoundWord> correctWord(String word);

    boolean lookupIfKnown();
}
