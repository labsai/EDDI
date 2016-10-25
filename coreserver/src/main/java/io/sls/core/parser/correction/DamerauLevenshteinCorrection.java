package io.sls.core.parser.correction;

import io.sls.core.parser.correction.similarities.DamerauLevenshteinDistance;
import io.sls.core.parser.correction.similarities.IDistanceCalculator;
import io.sls.core.parser.model.FoundWord;
import io.sls.core.parser.model.IDictionary;
import io.sls.core.parser.model.Word;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class DamerauLevenshteinCorrection implements ICorrection {
    private static final String ID = "DamerauLevenshteinDistanceCorrected";

    private int maxDistance;
    private boolean lookupIfKnown;
    private IDistanceCalculator distanceCalculator = new DamerauLevenshteinDistance();
    private List<IDictionary> dictionaries;

    public DamerauLevenshteinCorrection(boolean lookupIfKnown) {
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
    public IDictionary.IFoundWord[] correctWord(String lookup) {
        List<WordDistanceWrapper> foundWords = new LinkedList<WordDistanceWrapper>();
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

        List<IDictionary.IFoundWord> ret = new LinkedList<IDictionary.IFoundWord>();
        for (WordDistanceWrapper foundWord : foundWords) {
            double matchingAccuracy = 1.0 - foundWord.distance;
            ret.add(new FoundWord(foundWord.word, true, matchingAccuracy));
        }

        return ret.toArray(new IDictionary.IFoundWord[ret.size()]);
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }

    private int calculateDistance(String inputPart, String word) {
        int lengthWord = word.length();
        int lengthPart = inputPart.length();
        int distance;
        if (lengthWord >= (lengthPart - maxDistance) && lengthWord <= (lengthPart + maxDistance) &&
                (distance = distanceCalculator.calculate(word, inputPart)) <= maxDistance) {
        } else {
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
            return distance < o.distance ? -1 : distance == o.distance ? 0 : 1;
        }
    }
}
