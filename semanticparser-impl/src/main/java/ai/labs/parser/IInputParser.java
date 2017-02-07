package ai.labs.parser;

import ai.labs.parser.internal.matches.RawSolution;

import java.util.List;

/**
 * @author ginccc
 */
public interface IInputParser {
    List<RawSolution> parse(String sentence);
}
