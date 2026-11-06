/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal;

import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;
import ai.labs.eddi.modules.nlp.internal.matches.MatchingResult;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.nlp.internal.matches.Suggestion;
import ai.labs.eddi.modules.nlp.model.FoundPhrase;
import ai.labs.eddi.modules.nlp.model.FoundUnknown;
import ai.labs.eddi.modules.nlp.model.MatchQuality;
import ai.labs.eddi.modules.nlp.model.Unknown;
import ai.labs.eddi.modules.nlp.semantic.SemanticFallback;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
public class InputParser implements IInputParser {
    private static final Pattern REGEX_MATCHER_MULTIPLE_SPACES = Pattern.compile(" +");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String BLANK_CHAR = " ";
    private static final String DEFAULT_USER_LANGUAGE = "en";

    private static final Logger log = Logger.getLogger(InputParser.class);

    private final List<INormalizer> normalizers;
    private final List<IDictionary> dictionaries;
    private final List<ICorrection> corrections;
    /**
     * Phrase index per conversation language. A phrase may only be matched in a
     * language its dictionary applies to: phrases are matched on word
     * <em>values</em>, so a phrase of a German dictionary used to be a candidate in
     * an English conversation too, and matched as soon as the English dictionaries
     * happened to know all its words. Bounded by the number of languages the agent
     * is spoken in.
     */
    private final Map<String, Map<IDictionary.IWord, List<IDictionary.IPhrase>>> phrasesByLanguage = new ConcurrentHashMap<>();
    private final Config config;
    private final Limits limits;
    private final SemanticFallback semanticFallback;

    public InputParser(List<IDictionary> dictionaries) {
        this(dictionaries, Collections.emptyList());
    }

    public InputParser(List<IDictionary> dictionaries, List<ICorrection> corrections) {
        this(Collections.emptyList(), dictionaries, corrections, new Config());
    }

    public InputParser(List<INormalizer> normalizers, List<IDictionary> dictionaries, List<ICorrection> corrections, Config config) {
        this(normalizers, dictionaries, corrections, config, Limits.DEFAULT);
    }

    public InputParser(List<INormalizer> normalizers, List<IDictionary> dictionaries, List<ICorrection> corrections, Config config,
            Limits limits) {
        this(normalizers, dictionaries, corrections, config, limits, null);
    }

    public InputParser(List<INormalizer> normalizers, List<IDictionary> dictionaries, List<ICorrection> corrections, Config config,
            Limits limits, SemanticFallback semanticFallback) {
        this.normalizers = normalizers;
        this.dictionaries = dictionaries;
        this.corrections = corrections;
        this.config = config == null ? new Config() : config;
        this.limits = limits == null ? Limits.DEFAULT : limits;
        this.semanticFallback = semanticFallback;
    }

    @Override
    public Limits getLimits() {
        return limits;
    }

    @Override
    public SemanticFallback getSemanticFallback() {
        return semanticFallback;
    }

    /** The configured dictionaries, in lookup order. */
    public List<IDictionary> getDictionaries() {
        return Collections.unmodifiableList(dictionaries);
    }

    @Override
    public List<RawSolution> parse(String sentence) throws InterruptedException {
        return parse(sentence, DEFAULT_USER_LANGUAGE, Collections.emptyList());
    }

    @Override
    public String normalize(final String sentence, String userLanguage) throws InterruptedException {
        userLanguage = getLanguageOrDefault(userLanguage);
        String normalizedSentence = iterateNormalizers(sentence, userLanguage);
        normalizedSentence = normalizeWhitespaces(normalizedSentence);
        return normalizedSentence;
    }

    private String normalizeWhitespaces(String normalizedSentence) {
        return REGEX_MATCHER_MULTIPLE_SPACES.matcher(normalizedSentence.trim()).replaceAll(BLANK_CHAR);
    }

    /**
     * Whether {@code sentence} has more tokens than {@link Limits#maxInputTokens()}
     * — i.e. whether {@link #parse} will ignore part of it.
     */
    public static boolean exceedsTokenLimit(String sentence, Limits limits) {
        return tokenize(sentence).length > limits.maxInputTokens();
    }

    private static String[] tokenize(String sentence) {
        String trimmed = sentence == null ? "" : sentence.trim();
        // An empty input is one empty token, as it always was: it is reported as
        // unknown rather than producing no solution at all.
        return trimmed.isEmpty() ? new String[]{""} : WHITESPACE.split(trimmed);
    }

    @Override
    public List<RawSolution> parse(final String sentence, String userLanguage, final List<IDictionary> temporaryDictionaries)
            throws InterruptedException {
        return parse(sentence, userLanguage, temporaryDictionaries, false);
    }

    @Override
    public List<RawSolution> parseAllReadings(String sentence, String userLanguage) throws InterruptedException {
        return parse(sentence, userLanguage, Collections.emptyList(), true);
    }

    /**
     * @param allReadings
     *            collect readings up to {@link Limits#maxSolutions()} even when no
     *            phrase is involved; otherwise stop at the chosen reading plus
     *            {@link Config#getMaxAlternatives()}
     */
    private List<RawSolution> parse(final String sentence, String userLanguage, final List<IDictionary> temporaryDictionaries,
                                    boolean allReadings)
            throws InterruptedException {

        userLanguage = getLanguageOrDefault(userLanguage);
        List<IDictionary> tmpDictionaries = temporaryDictionaries == null ? Collections.emptyList() : temporaryDictionaries;

        InputHolder holder = new InputHolder();
        holder.input = limitTokens(tokenize(sentence));

        for (; holder.index < holder.input.length; holder.index++) {
            final String currentInputPart = holder.input[holder.index];
            holder.startToken();

            iterateDictionaries(holder, currentInputPart, userLanguage, tmpDictionaries);
            iterateDictionaries(holder, currentInputPart, userLanguage, dictionaries);

            iterateCorrections(holder, currentInputPart, userLanguage, tmpDictionaries);
        }

        return lookupPhrases(holder, phrasesFor(userLanguage), preparePhrases(tmpDictionaries, userLanguage), allReadings);
    }

    /**
     * The parser enumerates the cartesian product of all dictionary matches per
     * token, so the number of tokens is what drives the combinatorial blow-up.
     * Anything beyond the configured limit is dropped rather than parsed.
     */
    private String[] limitTokens(String[] tokens) {
        if (tokens.length <= limits.maxInputTokens()) {
            return tokens;
        }

        log.warnf("Input has %s tokens which exceeds the configured parser limit of %s. Parsing the first %s tokens only.", tokens.length,
                limits.maxInputTokens(), limits.maxInputTokens());

        return Arrays.copyOf(tokens, limits.maxInputTokens());
    }

    private String iterateNormalizers(String sentence, String userLanguage) throws InterruptedException {
        for (INormalizer normalizer : normalizers) {
            throwExceptionIfInterrupted("normalizers");
            sentence = normalizer.normalize(sentence, userLanguage);
        }

        return sentence;
    }

    private void iterateDictionaries(InputHolder holder, String currentInputPart, String userLanguage, List<IDictionary> dictionaries)
            throws InterruptedException {
        for (IDictionary dictionary : dictionaries) {
            throwExceptionIfInterrupted("dictionaries");

            if (!IDictionary.appliesToLanguage(dictionary.getLanguageCode(), userLanguage)) {
                continue;
            }

            List<IDictionary.IFoundWord> dictionaryEntries = dictionary.lookupTerm(currentInputPart);
            if (!dictionaryEntries.isEmpty()) {
                addDictionaryEntriesTo(holder, currentInputPart, dictionaryEntries);
            }
        }
    }

    private void iterateCorrections(InputHolder holder, String currentInputPart, String userLanguage, List<IDictionary> temporaryDictionaries)
            throws InterruptedException {

        for (ICorrection correction : corrections) {
            throwExceptionIfInterrupted("corrections");
            if (!correction.lookupIfKnown() && holder.getMatchingResultSize(holder.index) != 0) {
                // skipped corrections because input part is already known.
                continue;
            }

            var correctedWords = correction.correctWord(currentInputPart, userLanguage, temporaryDictionaries);
            if (!correctedWords.isEmpty()) {
                addDictionaryEntriesTo(holder, currentInputPart, correctedWords);
            }
        }

        if (holder.getMatchingResultSize(holder.index) == 0) {
            var foundUnknown = new FoundUnknown(new Unknown(currentInputPart));
            addDictionaryEntriesTo(holder, currentInputPart, Collections.singletonList(foundUnknown));
        }
    }

    private void throwExceptionIfInterrupted(String currentOperation) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            String message = String.format("Parser was interrupted while processing %s.", currentOperation);
            throw new InterruptedException(message);
        }
    }

    /**
     * Adds a token's matches, skipping any that mean the same as one already
     * recorded for the token — same word, same expressions, same phrase role.
     * Dictionaries and corrections report the same word more than once (a
     * correction at distance 0 of a word the dictionary already found, two
     * dictionaries sharing a word); each copy doubled the combinations the parser
     * enumerates while adding no reading. The first copy wins, so the better
     * (earlier) one is kept.
     */
    private void addDictionaryEntriesTo(InputHolder holder, String matchedInputValue, List<IDictionary.IFoundWord> foundWords) {
        for (IDictionary.IFoundWord foundWord : foundWords) {
            if (!holder.firstOfItsKind(foundWord)) {
                continue;
            }
            var matchingResult = new MatchingResult();
            matchingResult.addResult(foundWord);
            holder.addMatch(holder.index, matchedInputValue, matchingResult);
        }
    }

    private List<RawSolution> lookupPhrases(InputHolder holder, Map<IDictionary.IWord, List<IDictionary.IPhrase>> phrasesMap,
                                            Map<IDictionary.IWord, List<IDictionary.IPhrase>> tmpPhrasesMap, boolean allReadings)
            throws InterruptedException {

        List<RawSolution> possibleSolutions = new ArrayList<>();
        Iterator<Suggestion> suggestionIterator = holder.createSolutionIterator();
        List<Map<IDictionary.IWord, List<IDictionary.IPhrase>>> phraseMaps = Arrays.asList(phrasesMap, tmpPhrasesMap);

        // Without any phrase, every combination is just another reading of the words;
        // the combinations come best first, so the first one is the answer and only
        // as many more as can be reported as alternatives are worth building.
        boolean noPhrases = !allReadings && phrasesMap.isEmpty() && tmpPhrasesMap.isEmpty();
        int solutionsWanted = noPhrases ? Math.min(limits.maxSolutions(), 1 + Math.max(0, config.getMaxAlternatives())) : limits.maxSolutions();

        int evaluatedSuggestions = 0;
        while (suggestionIterator.hasNext()) {
            throwExceptionIfInterrupted("phrases");

            // Hard cap: the number of match combinations grows exponentially with the
            // number of tokens once dictionaries yield more than one match per token.
            if (evaluatedSuggestions >= limits.maxSuggestions()) {
                log.debugf("Stopped evaluating suggestions after reaching the configured limit of %s.", limits.maxSuggestions());
                break;
            }
            evaluatedSuggestions++;

            Suggestion suggestion = suggestionIterator.next();
            List<IDictionary.IFoundWord> foundWords = suggestion.build();
            Collection<IDictionary.IPhrase> candidatePhrases = getPhrasesContainingFoundWords(foundWords, phraseMaps);

            // first try: replace every phrase that occurs in full
            boolean matchedFullPhrase = false;
            for (IDictionary.IPhrase phrase : candidatePhrases) {
                if (isInterrupted()) {
                    break;
                }

                if (phrase.getWords().size() <= foundWords.size()) {
                    List<IDictionary.IFoundWord> replaced = lookForMatch(foundWords, phrase);
                    if (replaced != foundWords) {
                        matchedFullPhrase = true;
                        foundWords = replaced;
                    }

                    if (noWordsLeft(foundWords)) {
                        break;
                    }
                }
            }

            if (matchedFullPhrase && noWordsLeft(foundWords)) {
                // Every phrase word belongs to a phrase: nothing a later combination
                // finds can be better. It goes to the FRONT — it used to be appended
                // behind the worse solutions earlier combinations had produced, and
                // the caller takes the first solution.
                addIfAbsent(possibleSolutions, new RawSolution(RawSolution.Match.FULLY, foundWords), 0);
                return possibleSolutions;
            }

            // second try: fragments of phrases ("good morning" of "good morning sir")
            boolean matchedPartialPhrase = false;
            if (config.isMatchPartialPhrases() && !noWordsLeft(foundWords)) {
                List<IDictionary.IFoundWord> replaced = matchPartialPhrases(foundWords, candidatePhrases);
                if (replaced != foundWords) {
                    matchedPartialPhrase = true;
                    foundWords = replaced;
                }
            }

            RawSolution.Match match;
            if (matchedFullPhrase) {
                match = RawSolution.Match.FULLY;
            } else if (matchedPartialPhrase) {
                match = RawSolution.Match.PARTLY;
            } else if (foundWords.stream().anyMatch(word -> word.getFoundWord().isPartOfPhrase())) {
                // words of a known phrase without the rest of it
                match = RawSolution.Match.NOTHING;
            } else {
                // found no words which are part of a known phrase, but words of the
                // dictionaries, so it is treated more prominently
                match = RawSolution.Match.PARTLY;
            }
            insertByRank(possibleSolutions, new RawSolution(match, foundWords));

            // Hard cap: keeps memory bounded when many distinct partial solutions match.
            if (possibleSolutions.size() >= solutionsWanted) {
                if (!noPhrases) {
                    log.debugf("Stopped collecting solutions after reaching the configured limit of %s.", limits.maxSolutions());
                }
                break;
            }
        }

        return possibleSolutions;
    }

    /**
     * Keeps the list ordered by {@link RawSolution.Match} (FULLY, PARTLY, NOTHING)
     * and, within one, in the order the combinations were found — best first.
     */
    private static void insertByRank(List<RawSolution> possibleSolutions, RawSolution rawSolution) {
        int position = possibleSolutions.size();
        while (position > 0 && possibleSolutions.get(position - 1).getMatch().ordinal() > rawSolution.getMatch().ordinal()) {
            position--;
        }
        addIfAbsent(possibleSolutions, rawSolution, position);
    }

    private static void addIfAbsent(List<RawSolution> possibleSolutions, RawSolution rawSolution, int insertAtIndex) {
        if (!possibleSolutions.contains(rawSolution)) {
            possibleSolutions.add(Math.min(insertAtIndex, possibleSolutions.size()), rawSolution);
        }
    }

    private boolean isInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    private boolean noWordsLeft(List<IDictionary.IFoundWord> foundWords) {
        return foundWords.stream().noneMatch(foundWord -> foundWord.getFoundWord().isPartOfPhrase());
    }

    /**
     * @param foundWords
     *            all inputEntries
     * @param phrase
     *            to be checked for a match with foundWords
     * @return {@code foundWords} itself when the phrase does not occur in it;
     *         otherwise a new list in which the matching range is replaced by the
     *         phrase. The phrase is as accurate as its least accurate word and
     *         counts as corrected if any of its words was — a phrase assembled from
     *         typo corrections used to report itself as an exact match.
     */
    private List<IDictionary.IFoundWord> lookForMatch(List<IDictionary.IFoundWord> foundWords, IDictionary.IPhrase phrase) {
        var words = convert(foundWords);
        int startOfMatch = Collections.indexOfSubList(words, phrase.getWords());
        if (startOfMatch < 0) {
            return foundWords;
        }
        int endOfMatch = startOfMatch + phrase.getWords().size();
        List<IDictionary.IFoundWord> constituents = foundWords.subList(startOfMatch, endOfMatch);
        var foundPhrase = new FoundPhrase(phrase, MatchQuality.anyCorrected(constituents), MatchQuality.lowestAccuracy(constituents));
        return replaceRange(foundWords, startOfMatch, endOfMatch, foundPhrase);
    }

    private static List<IDictionary.IFoundWord> replaceRange(List<IDictionary.IFoundWord> foundWords, int start, int end,
                                                             IDictionary.IFoundWord replacement) {
        List<IDictionary.IFoundWord> ret = new ArrayList<>(foundWords.size() - (end - start) + 1);
        ret.addAll(foundWords.subList(0, start));
        ret.add(replacement);
        ret.addAll(foundWords.subList(end, foundWords.size()));
        return ret;
    }

    private record PartialMatch(IDictionary.IPhrase phrase, int start, int length, double coverage) {
    }

    /**
     * Replaces fragments of phrases by the phrase, best covered fragment first.
     * <p>
     * A fragment is a run of consecutive phrase-word matches that is also a run of
     * consecutive words of the phrase, covering at least
     * {@link Config#getMinPartialPhraseCoverage()} of it. Its accuracy is that
     * coverage times its least accurate word, so a rule-relevant threshold
     * ({@code minMatchingAccuracy}) applies to it too. When two phrases are covered
     * equally well by the same fragment — "good" of "good morning" and of "good
     * night" — the reading is ambiguous and neither is taken.
     * <p>
     * This was attempted before and never worked: it compared the phrase's words
     * against the <em>found</em> words (different classes, so never equal), and its
     * range arithmetic mixed up the two lists.
     *
     * @return {@code foundWords} itself when no fragment qualifies
     */
    private List<IDictionary.IFoundWord> matchPartialPhrases(List<IDictionary.IFoundWord> foundWords,
                                                             Collection<IDictionary.IPhrase> candidatePhrases) {
        List<IDictionary.IFoundWord> current = foundWords;
        // every replacement consumes at least one phrase word, so this terminates
        while (!noWordsLeft(current)) {
            PartialMatch best = null;
            boolean ambiguous = false;
            for (IDictionary.IPhrase phrase : candidatePhrases) {
                PartialMatch candidate = longestFragment(current, phrase);
                if (candidate == null || candidate.coverage() < config.getMinPartialPhraseCoverage()) {
                    continue;
                }
                if (best == null || candidate.coverage() > best.coverage()
                        || (candidate.coverage() == best.coverage() && candidate.length() > best.length())) {
                    best = candidate;
                    ambiguous = false;
                } else if (candidate.coverage() == best.coverage() && candidate.length() == best.length() && candidate.start() == best.start()) {
                    ambiguous = true;
                }
            }
            if (best == null || ambiguous) {
                return current;
            }
            int end = best.start() + best.length();
            List<IDictionary.IFoundWord> constituents = current.subList(best.start(), end);
            double accuracy = best.coverage() * MatchQuality.lowestAccuracy(constituents);
            current = replaceRange(current, best.start(), end, new FoundPhrase(best.phrase(), MatchQuality.anyCorrected(constituents), accuracy));
        }
        return current;
    }

    /**
     * The longest run of consecutive phrase-word matches in {@code foundWords} that
     * equals a run of consecutive words of {@code phrase}; {@code null} if none.
     */
    private static PartialMatch longestFragment(List<IDictionary.IFoundWord> foundWords, IDictionary.IPhrase phrase) {
        List<IDictionary.IWord> phraseWords = phrase.getWords();
        if (phraseWords.isEmpty()) {
            return null;
        }
        int bestStart = -1;
        int bestLength = 0;
        for (int i = 0; i < foundWords.size(); i++) {
            for (int j = 0; j < phraseWords.size(); j++) {
                int k = 0;
                while (i + k < foundWords.size() && j + k < phraseWords.size() && isPhraseWord(foundWords.get(i + k))
                        && phraseWords.get(j + k).equals(foundWords.get(i + k).getFoundWord())) {
                    k++;
                }
                if (k > bestLength) {
                    bestLength = k;
                    bestStart = i;
                }
            }
        }
        if (bestLength == 0) {
            return null;
        }
        return new PartialMatch(phrase, bestStart, bestLength, (double) bestLength / phraseWords.size());
    }

    private static boolean isPhraseWord(IDictionary.IFoundWord foundWord) {
        return !foundWord.isPhrase() && foundWord.getFoundWord() != null && foundWord.getFoundWord().isPartOfPhrase();
    }

    private List<IDictionary.IWord> convert(List<IDictionary.IFoundWord> foundWords) {
        return foundWords.stream().map(IDictionary.IFoundWord::getFoundWord).toList();
    }

    private Map<IDictionary.IWord, List<IDictionary.IPhrase>> phrasesFor(String userLanguage) {
        return phrasesByLanguage.computeIfAbsent(userLanguage, language -> preparePhrases(dictionaries, language));
    }

    private Map<IDictionary.IWord, List<IDictionary.IPhrase>> preparePhrases(List<IDictionary> dictionaries, String userLanguage) {
        Map<IDictionary.IWord, List<IDictionary.IPhrase>> phrasesMap = new HashMap<>();
        dictionaries.stream().filter(dictionary -> IDictionary.appliesToLanguage(dictionary.getLanguageCode(), userLanguage))
                .map(IDictionary::getPhrases).flatMap(Collection::stream)
                .forEach(phrase -> phrase.getWords().stream()
                        .map(wordOfPhrase -> phrasesMap.computeIfAbsent(wordOfPhrase, k -> new ArrayList<>()))
                        .filter(phrases -> !phrases.contains(phrase)).forEach(phrases -> phrases.add(phrase)));

        phrasesMap.values().stream().filter(phrases -> phrases.size() > 1).forEach(this::orderPhrasesByLength);

        return phrasesMap;
    }

    private void orderPhrasesByLength(List<IDictionary.IPhrase> phrases) {
        phrases.sort(Collections.reverseOrder((o1, o2) -> {
            int lengthWord1 = o1.getWords().size();
            int lengthWord2 = o2.getWords().size();
            if (lengthWord1 != lengthWord2) {
                return Integer.compare(lengthWord1, lengthWord2);
            } else {
                return o1.getValue().compareTo(o2.getValue());
            }
        }));
    }

    private Collection<IDictionary.IPhrase> getPhrasesContainingFoundWords(List<IDictionary.IFoundWord> foundWords,
                                                                           List<Map<IDictionary.IWord, List<IDictionary.IPhrase>>> phrasesMaps) {

        // insertion-ordered set: the order decides which phrase is tried first, the
        // set keeps the de-duplication linear (it was a list scanned per phrase)
        Set<IDictionary.IPhrase> ret = new LinkedHashSet<>();
        for (IDictionary.IFoundWord foundWord : foundWords) {
            if (foundWord.isPhrase()) {
                continue;
            }
            for (var phrasesMap : phrasesMaps) {
                List<IDictionary.IPhrase> phrases = phrasesMap.get(foundWord.getFoundWord());
                if (phrases != null) {
                    ret.addAll(phrases);
                }
            }
        }

        return ret;
    }

    private static String getLanguageOrDefault(String languageCode) {
        return isNullOrEmpty(languageCode) ? DEFAULT_USER_LANGUAGE : languageCode;
    }

    @Override
    public Config getConfig() {
        return config;
    }
}
