/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal.matches;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class MatchingResult {
    private List<IDictionary.IFoundWord> result;
    private boolean corrected;

    public MatchingResult() {
        this(false);
    }

    private MatchingResult(boolean corrected) {
        this.corrected = corrected;
        result = new LinkedList<>();
    }

    public void addResult(IDictionary.IFoundWord dictionaryEntries) {
        result.add(dictionaryEntries);
    }

    public List<IDictionary.IFoundWord> getResult() {
        return result;
    }

    public boolean isCorrected() {
        return corrected;
    }
}
