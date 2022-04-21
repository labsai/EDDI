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
    private final int maxDistance;
    private final IDistanceCalculator distanceCalculator = new DamerauLevenshteinDistance();
    private final boolean lookupIfKnown;
    private List<IDictionary> dictionaries;

    public DamerauLevenshteinCorrection() {
        this(2, false);
    }

    public DamerauLevenshteinCorrection(int maxDistance, boolean lookupIfKnown) {
        this.maxDistance = maxDistance;
        this.lookupIfKnown = lookupIfKnown;
    }

    @Override
    public void init(List<IDictionary> dictionaries) {
        this.dictionaries = dictionaries;
    }

    @Override
    public List<IDictionary.IFoundWord> correctWord(String lookup, String userLanguage, List<IDictionary> temporaryDictionaries) {
        List<WordDistanceWrapper> foundWords = new LinkedList<>();
        var lowerCaseLookup = lookup.toLowerCase();

        List<IDictionary> allDictionaries = new LinkedList<>();
        allDictionaries.addAll(temporaryDictionaries);
        allDictionaries.addAll(dictionaries);

        for (IDictionary dictionary : allDictionaries) {
            for (IDictionary.IWord word : dictionary.getWords()) {
                final int distance = calculateDistance(lowerCaseLookup, word.getValue().toLowerCase());

                if (distance > -1) {
                    Word entry = new Word(word.getValue(),
                            word.getExpressions(),
                            word.getLanguageCode(),
                            word.getFrequency(),
                            word.isPartOfPhrase());

                    foundWords.add(new WordDistanceWrapper(distance, entry));
                }
            }
        }

        Collections.sort(foundWords);

        return foundWords.stream().map(foundWord -> {
            double matchingAccuracy = 1.0 - foundWord.distance;
            return new FoundWord(foundWord.word, true, matchingAccuracy);
        }).collect(Collectors.toList());
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }

    private int calculateDistance(String inputPart, String word) {
        int lengthWord = word.length();
        int lengthPart = inputPart.length();
        int distance;
        if (lengthWord < (lengthPart - maxDistance) || lengthWord > (lengthPart + maxDistance) ||
                (distance = distanceCalculator.calculate(word, inputPart)) > maxDistance) {
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
