/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.extensions.corrections.similarities.DamerauLevenshteinDistance;
import ai.labs.eddi.modules.nlp.extensions.corrections.similarities.IDistanceCalculator;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import ai.labs.eddi.modules.nlp.model.Word;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
public class DamerauLevenshteinCorrection implements ICorrection {
    /**
     * Upper bound on the number of correction candidates handed back for a single
     * input token. Every candidate becomes an additional branch in the parser's
     * match matrix, so returning the whole dictionary (which is what an unbounded
     * result set amounts to) makes solution enumeration explode.
     */
    public static final int DEFAULT_MAX_CANDIDATES = 5;

    private final int maxDistance;
    private final int maxCandidates;
    private final IDistanceCalculator distanceCalculator = new DamerauLevenshteinDistance();
    private final boolean lookupIfKnown;
    private List<IDictionary> dictionaries;

    public DamerauLevenshteinCorrection() {
        this(2, false);
    }

    public DamerauLevenshteinCorrection(int maxDistance, boolean lookupIfKnown) {
        this(maxDistance, lookupIfKnown, DEFAULT_MAX_CANDIDATES);
    }

    public DamerauLevenshteinCorrection(int maxDistance, boolean lookupIfKnown, int maxCandidates) {
        this.maxDistance = maxDistance;
        this.lookupIfKnown = lookupIfKnown;
        this.maxCandidates = maxCandidates < 1 ? DEFAULT_MAX_CANDIDATES : maxCandidates;
    }

    @Override
    public void init(List<IDictionary> dictionaries) {
        this.dictionaries = dictionaries;
    }

    @Override
    public List<IDictionary.IFoundWord> correctWord(String lookup, String userLanguage, List<IDictionary> temporaryDictionaries) {
        List<WordDistanceWrapper> foundWords = new LinkedList<>();
        var lowerCaseLookup = lookup.toLowerCase();

        collectCandidates(lowerCaseLookup, temporaryDictionaries, foundWords);
        collectCandidates(lowerCaseLookup, dictionaries, foundWords);

        // Best (i.e. lowest) distance first, then keep only the top candidates —
        // everything beyond is noise that would multiply the parser's search space.
        Collections.sort(foundWords);

        return foundWords.stream().limit(maxCandidates).map(foundWord -> new FoundWord(foundWord.word, true, matchingAccuracy(foundWord.distance)))
                .collect(Collectors.toList());
    }

    private void collectCandidates(String lowerCaseLookup, List<IDictionary> dictionariesToScan, List<WordDistanceWrapper> foundWords) {
        if (dictionariesToScan == null) {
            return;
        }

        for (IDictionary dictionary : dictionariesToScan) {
            for (IDictionary.IWord word : dictionary.getWords()) {
                final int distance = calculateDistance(lowerCaseLookup, word.getValue().toLowerCase());

                if (distance > -1) {
                    Word entry = new Word(word.getValue(), word.getExpressions(), word.getLanguageCode(), word.getFrequency(), word.isPartOfPhrase());

                    foundWords.add(new WordDistanceWrapper(distance, entry));
                }
            }
        }
    }

    /**
     * Maps an edit distance onto the documented 0..1 matching-accuracy scale. The
     * previous {@code 1.0 - distance} produced 0.0 and even -1.0 for distances of 1
     * and 2, which is outside the contract of
     * {@link ai.labs.eddi.modules.nlp.model.FoundDictionaryEntry#getMatchingAccuracy()}.
     */
    private double matchingAccuracy(int distance) {
        int worstAcceptableDistance = Math.max(1, maxDistance + 1);
        return 1.0 - ((double) distance / worstAcceptableDistance);
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }

    private int calculateDistance(String inputPart, String word) {
        int lengthWord = word.length();
        int lengthPart = inputPart.length();
        int distance;
        if (lengthWord < (lengthPart - maxDistance) || lengthWord > (lengthPart + maxDistance)
                || (distance = distanceCalculator.calculate(word, inputPart)) > maxDistance) {
            distance = -1;
        }

        return distance;
    }

    private static class WordDistanceWrapper implements Comparable<WordDistanceWrapper> {
        private final int distance;
        private final IDictionary.IWord word;

        private WordDistanceWrapper(int distance, IDictionary.IWord word) {
            this.distance = distance;
            this.word = word;
        }

        @Override
        public int compareTo(WordDistanceWrapper o) {
            return Integer.compare(distance, o.distance);
        }
    }
}
