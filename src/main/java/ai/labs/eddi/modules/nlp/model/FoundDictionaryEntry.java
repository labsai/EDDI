/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

/**
 * @author ginccc
 */
public class FoundDictionaryEntry extends DictionaryEntry {
    protected boolean isCorrected;
    // 0.0 > and <= 1.0
    protected double matchingAccuracy;

    public FoundDictionaryEntry(String unknownValue, Expressions expressions) {
        super(unknownValue, expressions);
    }

    public FoundDictionaryEntry(IDictionary.IDictionaryEntry dictionaryEntry, boolean corrected, double matchingAccuracy) {
        super(dictionaryEntry.getValue(), dictionaryEntry.getExpressions());
        isCorrected = corrected;
        this.matchingAccuracy = matchingAccuracy;
    }

    public boolean isIsCorrected() {
        return isCorrected;
    }

    public void setIsCorrected(boolean isCorrected) {
        this.isCorrected = isCorrected;
    }

    public double getMatchingAccuracy() {
        return matchingAccuracy;
    }

    public void setMatchingAccuracy(double matchingAccuracy) {
        this.matchingAccuracy = matchingAccuracy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        if (!super.equals(o))
            return false;
        FoundDictionaryEntry that = (FoundDictionaryEntry) o;
        return isCorrected == that.isCorrected && Double.compare(that.matchingAccuracy, matchingAccuracy) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(super.hashCode(), isCorrected, matchingAccuracy);
    }
}
