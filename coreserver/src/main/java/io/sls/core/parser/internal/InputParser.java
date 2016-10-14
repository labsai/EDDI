package io.sls.core.parser.internal;

import io.sls.core.parser.IInputParser;
import io.sls.core.parser.correction.ICorrection;
import io.sls.core.parser.internal.matches.MatchingResult;
import io.sls.core.parser.internal.matches.Solution;
import io.sls.core.parser.internal.matches.Suggestion;
import io.sls.core.parser.model.FoundPhrase;
import io.sls.core.parser.model.FoundUnknown;
import io.sls.core.parser.model.IDictionary;
import io.sls.core.parser.model.Unknown;
import io.sls.core.utilities.LanguageUtilities;

import java.util.*;

import static io.sls.core.parser.model.IDictionary.*;

/**
 * @author jarisch
 * @date 01.07.2009
 * @time 12:41:40
 */
public class InputParser implements IInputParser {
    private List<IDictionary> dictionaries;
    private List<ICorrection> corrections;
    private Map<IDictionary.IWord, List<IDictionary.IPhrase>> phrasesMap;

    public InputParser(List<IDictionary> dictionaries) {
        this(dictionaries, new LinkedList<>());
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
            for (IPhrase phrase : dictionaryPhrases) {
                for (IWord wordOfPhrase : phrase.getWords()) {
                    List<IDictionary.IPhrase> phrases = phrasesMap.get(wordOfPhrase);
                    if (phrases == null) {
                        phrases = new LinkedList<>();
                        phrasesMap.put(wordOfPhrase, phrases);
                    }

                    phrases.add(phrase);
                }
            }
        }

        for (IWord word : phrasesMap.keySet()) {
            List<IDictionary.IPhrase> phrases = phrasesMap.get(word);
            if (phrases.size() > 1) {
                orderPhrasesByLength(phrases);
            }
        }

        return phrasesMap;
    }

    private void orderPhrasesByLength(List<IDictionary.IPhrase> phrases) {
        Collections.sort(phrases, Collections.reverseOrder((o1, o2) -> {
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
    public List<Solution> parse(String sentence) {
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
                IFoundWord[] dictionaryEntries = dictionary.lookupTerm(currentInputPart);
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

                IFoundWord[] correctedWords = correction.correctWord(currentInputPart);
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

    private void addDictionaryEntriesTo(InputHolder holder, String matchedInputValue, IFoundWord... foundWords) {
        if (!holder.equalsMatchingTerm(matchedInputValue, foundWords)) {
            for (IFoundWord foundWord : foundWords) {
                MatchingResult matchingResult = new MatchingResult();
                matchingResult.addResult(foundWord);
                holder.addMatch(matchedInputValue, matchingResult);
            }
        }
    }

    private List<Solution> lookupPhrases(InputHolder holder) {
        List<Solution> possibleSolutions = new LinkedList<>();
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

            Solution solution = null;
            boolean matchingCompleted = false;

            //first try: look for full matches (one/more phrases)
            for (IPhrase phrase : phrasesContainingFoundWords) {
                if (phrase.getWords().length <= foundWords.size()) {
                    foundWords = lookForMatch(foundWords, phrase);
                    if (foundWords.contains(new FoundPhrase(phrase, false, 1.0))) {
                        solution = new Solution(Solution.Match.FULLY);
                    }

                    if (!anyWordsLeft(foundWords)) {
                        matchingCompleted = true;
                        break;
                    }
                }
            }

            //if we could match ALL the foundWords to phrase(s) we return
            if (solution != null && matchingCompleted) {
                solution.setDictionaryEntries(foundWords);
                possibleSolutions.add(solution);
                return possibleSolutions;
            }

            //second try: look for incomplete matches
            for (IPhrase phrase : phrasesContainingFoundWords) {
                if (phrase.getWords().length > foundWords.size()) {
                    foundWords = lookForPartlyMatch(foundWords, phrase);
                    if (foundWords.contains(new FoundPhrase(phrase, false, 0.5))) {
                        if (solution == null) {
                            solution = new Solution(Solution.Match.PARTLY);
                        }
                    }

                    if (!anyWordsLeft(foundWords)) {
                        matchingCompleted = true;
                        break;
                    }
                }
            }

            if (solution != null) {
                solution.setDictionaryEntries(foundWords);
                if (solution.getMatch() == Solution.Match.FULLY) {
                    possibleSolutions.add(0, solution);
                } else {
                    possibleSolutions.add(solution);
                }

                if (matchingCompleted) {
                    return possibleSolutions;
                }
            }

            if (possibleSolutions.isEmpty()) {
                solution = new Solution(Solution.Match.NOTHING);
                solution.setDictionaryEntries(foundWords);
                possibleSolutions.add(solution);
            }

            if (currentIteration > maxIterations) {
                break;
            }
        }

        return possibleSolutions;
    }

    private boolean anyWordsLeft(List<IDictionary.IFoundWord> foundWords) {
        for (IFoundWord foundWord : foundWords) {
            if (foundWord.getFoundWord().isPartOfPhrase()) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param foundWords all inputEntries
     * @param phrase
     * @return the list of IDictionaryEntry which does NOT FULLY match the phrase. Will be returned for further lookup.
     *         In case a phrase has been found, it will be substituted with the range of matching foundWords
     */
    private List<IDictionary.IFoundWord> lookForMatch(List<IDictionary.IFoundWord> foundWords, IPhrase phrase) {
        IWord[] words = convert(foundWords);
        int startOfMatch = LanguageUtilities.containsArray(phrase.getWords(), words);
        if (startOfMatch > -1) {
            //does match
            List<IDictionary.IFoundWord> ret = new LinkedList<>();
            if (startOfMatch > 0) {
                ret.addAll(foundWords.subList(0, startOfMatch));
            }
            ret.add(new FoundPhrase(phrase, false, 1.0));
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

    private IWord[] convert(List<IDictionary.IFoundWord> foundWords) {
        IWord[] ret = new IWord[foundWords.size()];

        for (int i = 0; i < foundWords.size(); i++) {
            ret[i] = foundWords.get(i).getFoundWord();
        }

        return ret;
    }

    private List<IDictionary.IFoundWord> lookForPartlyMatch(List<IDictionary.IFoundWord> dictionaryEntries, IPhrase phrase) {
        IWord[] phraseWords = phrase.getWords();
        int startOfMatch = LanguageUtilities.containsArray(dictionaryEntries.toArray(), phraseWords);
        if (startOfMatch > -1) {
            //does match
            List<IDictionary.IFoundWord> ret = new LinkedList<>();
            if (startOfMatch > 0) {
                ret.addAll(dictionaryEntries.subList(0, startOfMatch - 1));
            }
            ret.add(new FoundPhrase(phrase, false, 1.0));
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
        for (IFoundWord foundWord : foundWords) {
            if (foundWord.isPhrase()) continue;
            List<IDictionary.IPhrase> phrases = phrasesMap.get(foundWord.getFoundWord());
            if (phrases != null) {
                for (IPhrase phrase : phrases) {
                    if (!ret.contains(phrase)) {
                        ret.add(phrase);
                    }
                }
            }
        }

        return ret;
    }
}
