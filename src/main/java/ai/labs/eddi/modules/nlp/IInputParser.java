package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import lombok.*;

import java.util.List;

/**
 * @author ginccc
 */
public interface IInputParser {
    Config getConfig();

    String normalize(String sentence, String userLanguage) throws InterruptedException;

    List<RawSolution> parse(String sentence) throws InterruptedException;

    List<RawSolution> parse(String sentence, String userLanguage, List<IDictionary> temporaryDictionaries) throws InterruptedException;

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    @EqualsAndHashCode
    @ToString
    class Config {
        private boolean appendExpressions = true;
        private boolean includeUnused = true;
        private boolean includeUnknown = true;
    }
}
