package io.sls.core.parser.correction;

import io.sls.core.parser.model.IDictionary;

import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: jarisch
 * Date: 15.09.12
 * Time: 12:55
 * To change this template use File | Settings | File Templates.
 */
public interface ICorrection {

    void init(List<IDictionary> dictionaries);

    IDictionary.IFoundWord[] correctWord(String word);

    boolean lookupIfKnown();
}
