package ai.labs.parser;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.output.model.QuickReply;
import ai.labs.parser.dictionaries.RegularDictionary;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.rest.model.Solution;
import ai.labs.utilities.StringUtilities;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
public class DictionaryUtilities {
    private static List<Expression> convertDictionaryEntriesToExpressions(List<IDictionary.IFoundWord>
                                                                                  dictionaryEntries) {
        List<Expression> expressions = new LinkedList<>();

        for (IDictionary.IDictionaryEntry dictionaryEntry : dictionaryEntries) {
            expressions.addAll(dictionaryEntry.getExpressions());
        }

        return expressions;
    }

    public static List<Solution> extractExpressions(List<RawSolution> rawSolutions,
                                                    boolean includeUnused,
                                                    boolean includeUnknown) {
        List<Solution> solutionExpressions = new ArrayList<>();

        for (RawSolution rawSolution : rawSolutions) {
            List<Expression> expressions = convertDictionaryEntriesToExpressions(rawSolution.getDictionaryEntries());
            expressions = expressions.stream().
                    filter(expression -> includeUnused || !expression.getExpressionName().equals("unused")).
                    filter(expression -> includeUnknown || !expression.getExpressionName().equals("unknown")).
                    collect(Collectors.toList());
            solutionExpressions.add(new Solution(StringUtilities.joinStrings(", ", expressions)));
        }

        return solutionExpressions;
    }

    static List<IDictionary> convertQuickReplies(List<QuickReply> quickReplies, IExpressionProvider expressionProvider) {
        List<IDictionary> ret = new LinkedList<>();

        RegularDictionary dictionary = new RegularDictionary(null, false);
        quickReplies.forEach(quickReply -> {
            String quickReplyValue = quickReply.getValue();
            List<Expression> expressions = expressionProvider.parseExpressions(quickReply.getExpressions());
            if (quickReplyValue.contains(" ")) {
                dictionary.addPhrase(quickReplyValue, expressions);
            } else {
                dictionary.addWord(quickReplyValue, expressions, 0);
            }
        });
        ret.add(dictionary);

        return ret;
    }

}
