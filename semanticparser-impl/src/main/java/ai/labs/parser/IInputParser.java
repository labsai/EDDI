package ai.labs.parser;

import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.extensions.dictionaries.IDictionary;

import java.util.List;

/**
 * @author ginccc
 */
public interface IInputParser {
    String normalize(String sentence) throws InterruptedException;

    List<RawSolution> parse(String sentence) throws InterruptedException;

    List<RawSolution> parse(String sentence, List<IDictionary> temporaryDictionaries) throws InterruptedException;
}
