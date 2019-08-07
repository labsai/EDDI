package ai.labs.parser.model;

import ai.labs.expressions.Expressions;
import ai.labs.parser.extensions.dictionaries.IDictionary;

/**
 * @author ginccc
 */
public class FoundDictionaryEntry extends DictionaryEntry {
    protected boolean isCorrected = false;
    // 0.0 > and <= 1.0
    protected double matchingAccuracy;

    public FoundDictionaryEntry(String unknownValue, Expressions expressions, boolean corrected, double matchingAccuracy) {
        super(unknownValue, expressions);
    }

    public FoundDictionaryEntry(IDictionary.IDictionaryEntry dictionaryEntry, boolean corrected, double matchingAccuracy) {
        super(dictionaryEntry.getValue(), dictionaryEntry.getExpressions());
        isCorrected = corrected;
        this.matchingAccuracy = matchingAccuracy;
    }

    public void setCorrected(boolean corrected) {
        isCorrected = corrected;
    }

    public boolean isCorrected() {
        return isCorrected;
    }

    public double getMatchingAccuracy() {
        return matchingAccuracy;
    }

    public void setMatchingAccuracy(double matchingAccuracy) {
        this.matchingAccuracy = matchingAccuracy;
    }
}
