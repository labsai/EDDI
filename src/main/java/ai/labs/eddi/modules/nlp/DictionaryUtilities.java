/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.RegularDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.output.model.QuickReply;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class DictionaryUtilities {
    private static Expressions convertDictionaryEntriesToExpressions(List<IDictionary.IFoundWord> dictionaryEntries) {
        Expressions expressions = new Expressions();

        for (IDictionary.IFoundWord foundWord : dictionaryEntries) {
            expressions.addAll(foundWord.getExpressions());
        }

        return expressions;
    }

    private static List<String> buildMatchDetails(List<IDictionary.IFoundWord> dictionaryEntries) {
        List<String> details = new ArrayList<>();
        for (IDictionary.IFoundWord entry : dictionaryEntries) {
            if (entry.getFoundWord() == null) {
                continue;
            }
            String input = entry.getFoundWord().getValue();
            String type = entry.isPhrase() ? " [phrase]" : "";
            if (entry instanceof IDictionary.IFoundRegEx) {
                type = " [regex]";
            }
            for (Expression expression : entry.getExpressions()) {
                String expressionName = expression.getExpressionName();
                if ("unused".equals(expressionName) || "unknown".equals(expressionName)) {
                    continue;
                }
                details.add("\"" + input + "\"" + type + " → " + expression);
            }
        }
        return details;
    }

    public static List<Solution> extractExpressions(List<RawSolution> rawSolutions, boolean includeUnused, boolean includeUnknown) {
        List<Solution> solutionExpressions = new ArrayList<>();

        Expressions expressions;
        Expressions filteredExpressions;
        for (RawSolution rawSolution : rawSolutions) {
            expressions = convertDictionaryEntriesToExpressions(rawSolution.getDictionaryEntries());
            filteredExpressions = new Expressions();
            filteredExpressions.addAll(expressions.stream().filter(expression -> includeUnused || !expression.getExpressionName().equals("unused"))
                    .filter(expression -> includeUnknown || !expression.getExpressionName().equals("unknown")).toList());

            List<String> matchDetails = buildMatchDetails(rawSolution.getDictionaryEntries());
            solutionExpressions.add(new Solution(filteredExpressions, matchDetails));
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
