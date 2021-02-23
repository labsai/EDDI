package ai.labs.parser;

import ai.labs.parser.extensions.dictionaries.IDictionary;
import ai.labs.parser.internal.matches.RawSolution;

import java.util.List;

/**
 * @author ginccc
 */
public interface IInputParser {
    String normalize(String sentence, String userLanguage) throws InterruptedException;

    List<RawSolution> parse(String sentence) throws InterruptedException;

    List<RawSolution> parse(String sentence, String userLanguage, List<IDictionary> temporaryDictionaries) throws InterruptedException;
}
