package ai.labs.parser.correction;

import ai.labs.parser.correction.similarities.DamerauLevenshteinDistance;
import ai.labs.parser.correction.similarities.IDistanceCalculator;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.model.Word;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
public class DamerauLevenshteinCorrection implements ICorrection {
    private int maxDistance;
    private boolean lookupIfKnown;
    private IDistanceCalculator distanceCalculator = new DamerauLevenshteinDistance();
    private List<IDictionary> dictionaries;

    DamerauLevenshteinCorrection(boolean lookupIfKnown) {
        this(2, lookupIfKnown);
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
    public List<IDictionary.IFoundWord> correctWord(String lookup) {
        List<WordDistanceWrapper> foundWords = new LinkedList<>();
        lookup = lookup.toLowerCase();

        for (IDictionary dictionary : dictionaries) {
            for (IDictionary.IWord word : dictionary.getWords()) {
                final int distance = calculateDistance(lookup, word.getValue().toLowerCase());

                if (distance > -1) {
                    Word entry = new Word(word.getValue(),
                            word.getExpressions(),
                            word.getIdentifier(),
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

    private class WordDistanceWrapper implements Comparable<WordDistanceWrapper> {
        private WordDistanceWrapper(int distance, IDictionary.IWord word) {
            this.distance = distance;
            this.word = word;
        }

        private int distance;
        private IDictionary.IWord word;

        @Override
        public int compareTo(WordDistanceWrapper o) {
            return Integer.compare(distance, o.distance);
        }
    }
}
