package ai.labs.parser;

import ai.labs.parser.internal.matches.Solution;

import java.util.List;

/**
 * @author ginccc
 */
public interface IInputParser {
    List<Solution> parse(String sentence);
}
