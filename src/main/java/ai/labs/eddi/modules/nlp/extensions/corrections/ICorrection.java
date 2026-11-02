/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public interface ICorrection {

    /**
     * The longest token a correction is attempted for. Corrections cost time or
     * memory quadratic in the token length (substring copies, an edit-distance
     * matrix), and the parser bounds the number of tokens but not their length, so
     * one very long unbroken token would otherwise be a cheap way to burn CPU. No
     * real word comes near it; a longer token is simply left uncorrected.
     */
    int MAX_CORRECTABLE_LENGTH = 100;

    void init(List<IDictionary> dictionaries);

    default List<IDictionary.IFoundWord> correctWord(String word, String userLanguage) {
        return correctWord(word, userLanguage, new LinkedList<>());
    }

    List<IDictionary.IFoundWord> correctWord(String word, String userLanguage, List<IDictionary> temporaryDictionaries);

    boolean lookupIfKnown();
}
