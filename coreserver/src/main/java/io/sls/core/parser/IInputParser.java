package io.sls.core.parser;

import io.sls.core.parser.internal.matches.Solution;

import java.util.List;

/**
 * @author ginccc
 */
public interface IInputParser {
    List<Solution> parse(String sentence);
}
