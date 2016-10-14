package io.sls.core.parser;

import io.sls.core.parser.internal.matches.Solution;

import java.util.List;

/**
 * User: jarisch
 * Date: 23.06.12
 * Time: 19:12
 */
public interface IInputParser {
    List<Solution> parse(String sentence);
}
