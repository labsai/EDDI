package io.sls.core.parser.correction;

import io.sls.core.parser.model.IDictionary;

import java.util.List;

/**
 * @author ginccc
 */
public interface ICorrection {

    void init(List<IDictionary> dictionaries);

    IDictionary.IFoundWord[] correctWord(String word);

    boolean lookupIfKnown();
}
