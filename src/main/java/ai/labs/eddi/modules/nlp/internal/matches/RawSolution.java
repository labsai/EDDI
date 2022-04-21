package ai.labs.eddi.modules.nlp.internal.matches;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */
public class RawSolution {
    public enum Match {
        FULLY,
        PARTLY,
        NOTHING
    }

    @Getter
    private Match match;

    @Getter
    @Setter
    private List<IDictionary.IFoundWord> dictionaryEntries;

    public RawSolution(Match match) {
        this.match = match;
        this.dictionaryEntries = new ArrayList<>();
    }
}
