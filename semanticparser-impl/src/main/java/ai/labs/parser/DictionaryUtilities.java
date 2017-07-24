package ai.labs.parser;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.output.IQuickReply;
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

    static List<IDictionary> convertQuickReplies(List<IQuickReply> quickReplies, IExpressionProvider expressionProvider) {
        List<IDictionary> ret = new LinkedList<>();

        for (IQuickReply quickReply : quickReplies) {
            RegularDictionary dictionary = new RegularDictionary(null, false);
            String quickReplyValue = quickReply.getValue();
            if (quickReplyValue.contains(" ")) {
                dictionary.addPhrase(quickReplyValue, expressionProvider.parseExpressions(quickReply.getExpressions()));
            }

            ret.add(dictionary);
        }

        return ret;
    }

}
