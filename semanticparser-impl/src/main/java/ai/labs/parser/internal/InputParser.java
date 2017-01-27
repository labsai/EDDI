package ai.labs.parser.internal;

import ai.labs.parser.IInputParser;
import ai.labs.parser.correction.ICorrection;
import ai.labs.parser.internal.matches.MatchingResult;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.internal.matches.Suggestion;
import ai.labs.parser.model.FoundPhrase;
import ai.labs.parser.model.FoundUnknown;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.model.Unknown;
import ai.labs.utilities.LanguageUtilities;

import java.util.*;

/**
 * @author ginccc
 */
public class InputParser implements IInputParser {
    private List<IDictionary> dictionaries;
    private List<ICorrection> corrections;
    private Map<IDictionary.IWord, List<IDictionary.IPhrase>> phrasesMap;

    public InputParser(List<IDictionary> dictionaries) {
        this(dictionaries, Collections.emptyList());
    }

    public InputParser(List<IDictionary> dictionaries, List<ICorrection> corrections) {
        this.dictionaries = dictionaries;
        this.corrections = corrections;
        phrasesMap = preparePhrases(dictionaries);
    }

    private Map<IDictionary.IWord, List<IDictionary.IPhrase>> preparePhrases(List<IDictionary> dictionaries) {
        Map<IDictionary.IWord, List<IDictionary.IPhrase>> phrasesMap = new HashMap<>();
        for (IDictionary dictionary : dictionaries) {
            List<IDictionary.IPhrase> dictionaryPhrases = dictionary.getPhrases();
            for (IDictionary.IPhrase phrase : dictionaryPhrases) {
                for (IDictionary.IWord wordOfPhrase : phrase.getWords()) {
                    List<IDictionary.IPhrase> phrases = phrasesMap.computeIfAbsent(wordOfPhrase, k -> new LinkedList<>());
                    phrases.add(phrase);
                }
            }
        }

        for (IDictionary.IWord word : phrasesMap.keySet()) {
            List<IDictionary.IPhrase> phrases = phrasesMap.get(word);
            if (phrases.size() > 1) {
                orderPhrasesByLength(phrases);
            }
        }

        return phrasesMap;
    }

    private void orderPhrasesByLength(List<IDictionary.IPhrase> phrases) {
        phrases.sort(Collections.reverseOrder((o1, o2) -> {
            int lengthWord1 = o1.getWords().length;
            int lengthWord2 = o2.getWords().length;
            if (lengthWord1 != lengthWord2) {
                return lengthWord1 < lengthWord2 ? -1 : lengthWord1 > lengthWord2 ? 1 : 0;
            } else {
                return o1.getValue().compareTo(o2.getValue());
            }
        }));
    }

    @Override
    public List<RawSolution> parse(String sentence) {
        InputHolder holder = new InputHolder();
        holder.input = sentence.split(" ");

        for (; holder.index < holder.input.length; holder.index++) {
            final String currentInputPart = holder.input[holder.index];
            if (currentInputPart.isEmpty()) {
                continue;
            }

            for (IDictionary dictionary : dictionaries) {
                if (!dictionary.lookupIfKnown() && holder.getMatchingResultSize(holder.index) != 0) {
                    //skipped lookup because input part is already known.
                    continue;
                }

                //lookup input part in dictionary
                IDictionary.IFoundWord[] dictionaryEntries = dictionary.lookupTerm(currentInputPart);
                if (dictionaryEntries.length > 0) {
                    //add dictionary entries to final result list
                    addDictionaryEntriesTo(holder, currentInputPart, dictionaryEntries);
                }
            }

            for (ICorrection correction : corrections) {
                if (!correction.lookupIfKnown() && holder.getMatchingResultSize(holder.index) != 0) {
                    //skipped correction because input part is already known.
                    continue;
                }

                IDictionary.IFoundWord[] correctedWords = correction.correctWord(currentInputPart);
                if (correctedWords.length > 0) {
                    addDictionaryEntriesTo(holder, currentInputPart, correctedWords);
                }
            }

            if (holder.getMatchingResultSize(holder.index) == 0) {
                FoundUnknown foundUnknown = new FoundUnknown(new Unknown(currentInputPart));
                addDictionaryEntriesTo(holder, currentInputPart, foundUnknown);
            }
        }

        return lookupPhrases(holder);
    }

    private void addDictionaryEntriesTo(InputHolder holder, String matchedInputValue, IDictionary.IFoundWord... foundWords) {
        if (!holder.equalsMatchingTerm(matchedInputValue, foundWords)) {
            for (IDictionary.IFoundWord foundWord : foundWords) {
                MatchingResult matchingResult = new MatchingResult();
                matchingResult.addResult(foundWord);
                holder.addMatch(matchedInputValue, matchingResult);
            }
        }
    }

    private List<RawSolution> lookupPhrases(InputHolder holder) {
        List<RawSolution> possibleSolutions = new LinkedList<>();
        Iterator<Suggestion> suggestionIterator = holder.createSolutionIterator();

        int maxIterations = 2;
        int currentIteration = 0;
        while (suggestionIterator.hasNext()) {
            currentIteration++;
            Suggestion suggestion = suggestionIterator.next();
            if (Thread.currentThread().isInterrupted()) {
                //probably it took too long, so we return what we have...
                return possibleSolutions;
            }
            List<IDictionary.IFoundWord> foundWords = suggestion.build();
            List<IDictionary.IPhrase> phrasesContainingFoundWords = getPhrasesContainingFoundWords(foundWords);

            RawSolution rawSolution = null;
            boolean matchingCompleted = false;

            //first try: look for full matches (one/more phrases)
            for (IDictionary.IPhrase phrase : phrasesContainingFoundWords) {
                if (phrase.getWords().length <= foundWords.size()) {
                    foundWords = lookForMatch(foundWords, phrase);
                    if (foundWords.contains(createPhrase(phrase, false, 1.0))) {
                        rawSolution = new RawSolution(RawSolution.Match.FULLY);
                    }

                    if (!anyWordsLeft(foundWords)) {
                        matchingCompleted = true;
                        break;
                    }
                }
            }

            //if we could match ALL the foundWords to phrase(s) we return
            if (rawSolution != null && matchingCompleted) {
                rawSolution.setDictionaryEntries(foundWords);
                possibleSolutions.add(rawSolution);
                return possibleSolutions;
            }

            //second try: look for incomplete matches
            for (IDictionary.IPhrase phrase : phrasesContainingFoundWords) {
                if (phrase.getWords().length > foundWords.size()) {
                    foundWords = lookForPartlyMatch(foundWords, phrase);
                    if (foundWords.contains(createPhrase(phrase, false, 0.5))) {
                        if (rawSolution == null) {
                            rawSolution = new RawSolution(RawSolution.Match.PARTLY);
                        }
                    }

                    if (!anyWordsLeft(foundWords)) {
                        matchingCompleted = true;
                        break;
                    }
                }
            }

            if (rawSolution != null) {
                rawSolution.setDictionaryEntries(foundWords);
                if (rawSolution.getMatch() == RawSolution.Match.FULLY) {
                    possibleSolutions.add(0, rawSolution);
                } else {
                    possibleSolutions.add(rawSolution);
                }

                if (matchingCompleted) {
                    return possibleSolutions;
                }
            }

            if (possibleSolutions.isEmpty()) {
                rawSolution = new RawSolution(RawSolution.Match.NOTHING);
                rawSolution.setDictionaryEntries(foundWords);
                possibleSolutions.add(rawSolution);
            }

            if (currentIteration > maxIterations) {
                break;
            }
        }

        return possibleSolutions;
    }

    private boolean anyWordsLeft(List<IDictionary.IFoundWord> foundWords) {
        for (IDictionary.IFoundWord foundWord : foundWords) {
            if (foundWord.getFoundWord().isPartOfPhrase()) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param foundWords all inputEntries
     * @param phrase     to be checked for a match with foundWords
     * @return the list of IDictionaryEntry which does NOT FULLY match the phrase, will be returned for further lookup.
     * In case a phrase has been found, it will be substituted with the range of matching foundWords
     */
    private List<IDictionary.IFoundWord> lookForMatch(List<IDictionary.IFoundWord> foundWords, IDictionary.IPhrase phrase) {
        IDictionary.IWord[] words = convert(foundWords);
        int startOfMatch = LanguageUtilities.containsArray(phrase.getWords(), words);
        if (startOfMatch > -1) {
            //does match
            List<IDictionary.IFoundWord> ret = new LinkedList<>();
            if (startOfMatch > 0) {
                ret.addAll(foundWords.subList(0, startOfMatch));
            }
            ret.add(createPhrase(phrase, false, 1.0));
            int rangeOfMatch = startOfMatch + phrase.getWords().length;
            if (rangeOfMatch < foundWords.size()) {
                ret.addAll(foundWords.subList(rangeOfMatch, foundWords.size()));
            }

            return ret;
        } else {
            // does not match
            return foundWords;
        }
    }

    private IDictionary.IFoundWord createPhrase(IDictionary.IPhrase phrase, boolean corrected, double matchingAccuracy) {
        return new FoundPhrase(phrase, corrected, matchingAccuracy);
    }

    private IDictionary.IWord[] convert(List<IDictionary.IFoundWord> foundWords) {
        IDictionary.IWord[] ret = new IDictionary.IWord[foundWords.size()];

        for (int i = 0; i < foundWords.size(); i++) {
            ret[i] = foundWords.get(i).getFoundWord();
        }

        return ret;
    }

    private List<IDictionary.IFoundWord> lookForPartlyMatch(List<IDictionary.IFoundWord> dictionaryEntries, IDictionary.IPhrase phrase) {
        IDictionary.IWord[] phraseWords = phrase.getWords();
        int startOfMatch = LanguageUtilities.containsArray(dictionaryEntries.toArray(), phraseWords);
        if (startOfMatch > -1) {
            //does match
            List<IDictionary.IFoundWord> ret = new LinkedList<>();
            if (startOfMatch > 0) {
                ret.addAll(dictionaryEntries.subList(0, startOfMatch - 1));
            }
            ret.add(createPhrase(phrase, false, 1.0));
            int rangeOfMatch = startOfMatch + phraseWords.length;
            if (rangeOfMatch < dictionaryEntries.size()) {
                ret.addAll(dictionaryEntries.subList(rangeOfMatch, dictionaryEntries.size()));
            }

            return ret;
        } else {
            // does not match
            return dictionaryEntries;
        }
    }

    private List<IDictionary.IPhrase> getPhrasesContainingFoundWords(List<IDictionary.IFoundWord> foundWords) {
        List<IDictionary.IPhrase> ret = new LinkedList<>();
        for (IDictionary.IFoundWord foundWord : foundWords) {
            if (foundWord.isPhrase()) continue;
            List<IDictionary.IPhrase> phrases = phrasesMap.get(foundWord.getFoundWord());
            if (phrases != null) {
                for (IDictionary.IPhrase phrase : phrases) {
                    if (!ret.contains(phrase)) {
                        ret.add(phrase);
                    }
                }
            }
        }

        return ret;
    }
}
