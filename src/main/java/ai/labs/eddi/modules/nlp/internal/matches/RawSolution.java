/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */
public class RawSolution {
    public enum Match {
        FULLY, PARTLY, NOTHING
    }

    private Match match;

    private List<IDictionary.IFoundWord> dictionaryEntries;

    public RawSolution(Match match) {
        this.match = match;
        this.dictionaryEntries = new ArrayList<>();
    }

    public Match getMatch() {
        return match;
    }

    public List<IDictionary.IFoundWord> getDictionaryEntries() {
        return dictionaryEntries;
    }

    public void setDictionaryEntries(List<IDictionary.IFoundWord> dictionaryEntries) {
        this.dictionaryEntries = dictionaryEntries;
    }
}
