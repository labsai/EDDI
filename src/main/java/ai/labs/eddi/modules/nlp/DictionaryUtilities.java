/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.RegularDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.nlp.model.FoundUnknown;
import ai.labs.eddi.modules.nlp.model.MatchQuality;
import ai.labs.eddi.modules.output.model.QuickReply;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * @author ginccc
 */
public class DictionaryUtilities {
    static final String UNUSED = "unused";
    static final String UNKNOWN = "unknown";

    /**
     * Anything but the {@code unused(...)} and {@code unknown(...)} placeholders.
     */
    static boolean isMeaningful(Expression expression) {
        String name = expression.getExpressionName();
        return !UNUSED.equals(name) && !UNKNOWN.equals(name);
    }

    private static boolean contributesMeaning(IDictionary.IFoundWord foundWord) {
        return !(foundWord instanceof FoundUnknown) && foundWord.getExpressions() != null
                && foundWord.getExpressions().stream().anyMatch(DictionaryUtilities::isMeaningful);
    }

    /**
     * Whether a match is accurate enough to contribute its expressions. Unknown
     * words are not matches and always pass — {@code includeUnknown} decides about
     * them.
     */
    private static boolean accurateEnough(IDictionary.IFoundWord foundWord, double minMatchingAccuracy) {
        return minMatchingAccuracy <= 0.0 || foundWord instanceof FoundUnknown || MatchQuality.accuracy(foundWord) >= minMatchingAccuracy;
    }

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
            if (MatchQuality.corrected(entry) || MatchQuality.accuracy(entry) < 1.0) {
                type += String.format(Locale.ROOT, " [accuracy %.2f%s]", MatchQuality.accuracy(entry),
                        MatchQuality.corrected(entry) ? ", corrected" : "");
            }
            for (Expression expression : entry.getExpressions()) {
                if (!isMeaningful(expression)) {
                    continue;
                }
                details.add("\"" + input + "\"" + type + " → " + expression);
            }
        }
        return details;
    }

    public static List<Solution> extractExpressions(List<RawSolution> rawSolutions, boolean includeUnused, boolean includeUnknown) {
        return extractExpressions(rawSolutions, includeUnused, includeUnknown, 0.0);
    }

    /**
     * @param minMatchingAccuracy
     *            matches less accurate than this contribute no expressions (and no
     *            match details); {@code 0} keeps every match
     */
    public static List<Solution> extractExpressions(List<RawSolution> rawSolutions, boolean includeUnused, boolean includeUnknown,
                                                    double minMatchingAccuracy) {
        List<Solution> solutionExpressions = new ArrayList<>();

        for (RawSolution rawSolution : rawSolutions) {
            List<IDictionary.IFoundWord> entries = rawSolution.getDictionaryEntries().stream()
                    .filter(entry -> accurateEnough(entry, minMatchingAccuracy)).toList();

            Expressions expressions = convertDictionaryEntriesToExpressions(entries);
            Expressions filteredExpressions = new Expressions();
            filteredExpressions.addAll(expressions.stream().filter(expression -> includeUnused || !expression.getExpressionName().equals(UNUSED))
                    .filter(expression -> includeUnknown || !expression.getExpressionName().equals(UNKNOWN)).toList());

            List<IDictionary.IFoundWord> contributing = entries.stream().filter(DictionaryUtilities::contributesMeaning).toList();
            Double confidence = contributing.isEmpty() ? null : MatchQuality.lowestAccuracy(contributing);

            List<String> matchDetails = buildMatchDetails(entries);
            solutionExpressions.add(new Solution(filteredExpressions, matchDetails, confidence, MatchQuality.anyCorrected(contributing)));
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
