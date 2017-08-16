package ai.labs.parser;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.output.model.QuickReply;
import ai.labs.parser.dictionaries.RegularDictionary;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.rest.model.Solution;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class DictionaryUtilities {
    static List<Expression> convertDictionaryEntriesToExpressions(List<IDictionary.IFoundWord>
                                                                          dictionaryEntries) {
        List<Expression> expressions = new LinkedList<>();

        for (IDictionary.IDictionaryEntry dictionaryEntry : dictionaryEntries) {
            expressions.addAll(dictionaryEntry.getExpressions());
        }

        return expressions;
    }

    public static List<Solution> extractExpressions(List<RawSolution> rawSolutions, IExpressionProvider expressionProvider) {
        List<Solution> solutionExpressions = new ArrayList<>();

        for (RawSolution rawSolution : rawSolutions) {
            List<Expression> expressions = convertDictionaryEntriesToExpressions(rawSolution.getDictionaryEntries());
            solutionExpressions.add(new Solution(expressionProvider.toString(expressions)));
        }

        return solutionExpressions;
    }

    static List<IDictionary> convertQuickReplies(List<List<QuickReply>> quickRepliesList, IExpressionProvider expressionProvider) {
        List<IDictionary> ret = new LinkedList<>();

        quickRepliesList.forEach(quickReplies -> {
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
        });

        return ret;
    }

}
