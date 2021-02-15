package ai.labs.parser.model;

import ai.labs.expressions.Expressions;
import ai.labs.parser.extensions.dictionaries.IDictionary;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * @author ginccc
 */
@Setter
@Getter
@EqualsAndHashCode
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
}
