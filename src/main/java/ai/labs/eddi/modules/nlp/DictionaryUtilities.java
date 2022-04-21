package ai.labs.eddi.modules.nlp;


import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.RegularDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.output.model.QuickReply;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
public class DictionaryUtilities {
    private static Expressions convertDictionaryEntriesToExpressions(List<IDictionary.IFoundWord>
                                                                             dictionaryEntries) {
        Expressions expressions = new Expressions();

        for (IDictionary.IDictionaryEntry dictionaryEntry : dictionaryEntries) {
            expressions.addAll(dictionaryEntry.getExpressions());
        }

        return expressions;
    }

    public static List<Solution> extractExpressions(List<RawSolution> rawSolutions,
                                                    boolean includeUnused,
                                                    boolean includeUnknown) {
        List<Solution> solutionExpressions = new ArrayList<>();

        Expressions expressions, filteredExpressions;
        for (RawSolution rawSolution : rawSolutions) {
            expressions = convertDictionaryEntriesToExpressions(rawSolution.getDictionaryEntries());
            filteredExpressions = new Expressions();
            filteredExpressions.addAll(expressions.stream().
                    filter(expression -> includeUnused || !expression.getExpressionName().equals("unused")).
                    filter(expression -> includeUnknown || !expression.getExpressionName().equals("unknown")).
                    collect(Collectors.toList()));
            solutionExpressions.add(new Solution(filteredExpressions));
        }

        return solutionExpressions;
    }

    static List<IDictionary> convertQuickReplies(List<QuickReply> quickReplies, IExpressionProvider expressionProvider) {
        List<IDictionary> ret = new LinkedList<>();

        RegularDictionary dictionary = new RegularDictionary();
        quickReplies.forEach(quickReply -> {
            String quickReplyValue = quickReply.getValue();
            Expressions expressions = expressionProvider.parseExpressions(quickReply.getExpressions());
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
