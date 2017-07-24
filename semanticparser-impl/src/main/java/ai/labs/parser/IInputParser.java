package ai.labs.parser;

import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.model.IDictionary;

import java.util.List;

/**
 * @author ginccc
 */
public interface IInputParser {
    List<RawSolution> parse(String sentence);

    List<RawSolution> parse(String sentence, List<IDictionary> temporaryDictionaries);
}
