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
    /** Position of the temporary (quick-reply) phrases in the phrase-map list. */
    private static final int TEMPORARY_PHRASES = 1;

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

    /**
     * The part of {@code sentence} that {@link #parse} reads: its first
     * {@link Limits#maxInputTokens()} tokens, joined by single blanks. Anything
     * that consults the input besides the dictionaries (the semantic fallback) is
     * handed this, so the configured limit holds for every path.
     */
    public static String withinTokenLimit(String sentence, Limits limits) {
        String[] tokens = tokenize(sentence);
        if (tokens.length <= limits.maxInputTokens()) {
            return sentence == null ? "" : sentence;
        }
        return String.join(BLANK_CHAR, Arrays.copyOf(tokens, limits.maxInputTokens()));
    }

    /**
     * The language a turn is parsed in: the conversation's, or {@code en} when it
     * has none — the same default {@link #parse} and {@link #normalize} apply.
     */
    public static String effectiveLanguage(String userLanguage) {
        return getLanguageOrDefault(userLanguage);
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
            holder.preferAccurateEnough(holder.index, config.getMinMatchingAccuracy());
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
     * recorded for the token — same word, same expressions, same phrase role, same
     * accuracy. Dictionaries and corrections report the same word more than once (a
     * correction at distance 0 of a word the dictionary already found, two
     * dictionaries sharing a word); each copy doubled the combinations the parser
     * enumerates while adding no reading. Copies of different accuracy are both
     * kept, so the accuracy threshold can choose between them.
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
        // index TEMPORARY_PHRASES must be the temporary map
        List<Map<IDictionary.IWord, List<IDictionary.IPhrase>>> phraseMaps = Arrays.asList(phrasesMap, tmpPhrasesMap);

        // Without any phrase, every combination is just another reading of the words;
        // the combinations come best first, so the first one is the answer and only
        // as many more as can be reported as alternatives are worth building.
        boolean noPhrases = !allReadings && phrasesMap.isEmpty() && tmpPhrasesMap.isEmpty();
        int solutionsWanted = noPhrases ? Math.min(limits.maxSolutions(), 1 + Math.max(0, config.getMaxAlternatives())) : limits.maxSolutions();

        // Once a complete reading is found, only the configured alternatives (or, for
        // parseAllReadings, every reading) are still worth enumerating.
        int readingsWantedAfterComplete = allReadings
                ? limits.maxSolutions()
                : Math.min(limits.maxSolutions(), 1 + Math.max(0, config.getMaxAlternatives()));
        boolean completeFound = false;

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
                // Every phrase word belongs to a phrase: nothing a later combination finds
                // can be better. The first such reading goes to the FRONT — it used to be
                // appended behind the worse solutions earlier combinations had produced,
                // and the caller takes the first solution. Enumeration stops right away
                // unless alternatives (or every reading) were asked for; it used to stop
                // regardless, so /parser and maxAlternatives missed the other readings.
                var complete = new RawSolution(RawSolution.Match.FULLY, foundWords);
                if (completeFound) {
                    insertByRank(possibleSolutions, complete);
                } else {
                    addIfAbsent(possibleSolutions, complete, 0);
                    completeFound = true;
                }
                if (possibleSolutions.size() >= readingsWantedAfterComplete) {
                    return possibleSolutions;
                }
                continue;
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

            if (completeFound && possibleSolutions.size() >= readingsWantedAfterComplete) {
                break;
            }

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
        List<IDictionary.IWord> phraseWords = phrase.getWords();
        // the first occurrence that meets the threshold: an occurrence below it
        // contributes no expressions, and taking it would rank an empty reading above
        // one that does — but a later occurrence of the phrase may still qualify
        for (int from = 0; from + phraseWords.size() <= words.size(); from++) {
            int found = Collections.indexOfSubList(words.subList(from, words.size()), phraseWords);
            if (found < 0) {
                break;
            }
            int startOfMatch = from + found;
            int endOfMatch = startOfMatch + phraseWords.size();
            List<IDictionary.IFoundWord> constituents = foundWords.subList(startOfMatch, endOfMatch);
            double accuracy = MatchQuality.lowestAccuracy(constituents);
            if (accurateEnough(accuracy)) {
                var foundPhrase = new FoundPhrase(phrase, MatchQuality.anyCorrected(constituents), accuracy);
                return replaceRange(foundWords, startOfMatch, endOfMatch, foundPhrase);
            }
            from = startOfMatch;
        }
        return foundWords;
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
        // Starting words of fragments found ambiguous: skipped, so that the other
        // positions are still searched. It used to give up on the whole input at the
        // first ambiguity, dropping an unambiguous fragment elsewhere in it.
        Set<IDictionary.IFoundWord> ambiguousStarts = Collections.newSetFromMap(new IdentityHashMap<>());
        // each round replaces a fragment (consuming phrase words) or excludes one more
        // starting word, so this terminates
        while (!noWordsLeft(current)) {
            List<PartialMatch> qualifying = new ArrayList<>();
            for (IDictionary.IPhrase phrase : candidatePhrases) {
                PartialMatch candidate = longestFragment(current, phrase, ambiguousStarts);
                if (candidate != null) {
                    qualifying.add(candidate);
                }
            }
            if (qualifying.isEmpty()) {
                return current;
            }
            PartialMatch best = qualifying.getFirst();
            for (PartialMatch candidate : qualifying) {
                if (candidate.coverage() > best.coverage() || (candidate.coverage() == best.coverage() && candidate.length() > best.length())) {
                    best = candidate;
                }
            }
            PartialMatch chosen = best;
            long equallyGood = qualifying.stream().filter(candidate -> candidate.start() == chosen.start() && candidate.length() == chosen.length()
                    && candidate.coverage() == chosen.coverage()).count();
            if (equallyGood > 1) {
                ambiguousStarts.add(current.get(chosen.start()));
                continue;
            }
            int end = chosen.start() + chosen.length();
            List<IDictionary.IFoundWord> constituents = current.subList(chosen.start(), end);
            current = replaceRange(current, chosen.start(), end,
                    new FoundPhrase(chosen.phrase(), MatchQuality.anyCorrected(constituents), fragmentAccuracy(current, chosen)));
        }
        return current;
    }

    private static double fragmentAccuracy(List<IDictionary.IFoundWord> foundWords, PartialMatch fragment) {
        return fragment.coverage() * MatchQuality.lowestAccuracy(foundWords.subList(fragment.start(), fragment.start() + fragment.length()));
    }

    private boolean accurateEnough(double accuracy) {
        return accuracy >= config.getMinMatchingAccuracy();
    }

    /**
     * The longest run of consecutive phrase-word matches in {@code foundWords} that
     * equals a run of consecutive words of {@code phrase}, does not start at one of
     * {@code excludedStarts} and qualifies — covers at least
     * {@link Config#getMinPartialPhraseCoverage()} of the phrase and, judged by its
     * final accuracy (coverage included), meets {@code minMatchingAccuracy}; of
     * equally long ones the most accurate. {@code null} if none qualifies.
     * <p>
     * Every occurrence is judged, not only the first longest one: a fragment below
     * the threshold yields no expressions (and as a PARTLY reading would still
     * outrank the token's ordinary word reading), but an exact occurrence later in
     * the input may qualify.
     */
    private PartialMatch longestFragment(List<IDictionary.IFoundWord> foundWords, IDictionary.IPhrase phrase,
                                         Set<IDictionary.IFoundWord> excludedStarts) {
        List<IDictionary.IWord> phraseWords = phrase.getWords();
        if (phraseWords.isEmpty()) {
            return null;
        }
        PartialMatch best = null;
        double bestAccuracy = -1;
        for (int i = 0; i < foundWords.size(); i++) {
            if (excludedStarts.contains(foundWords.get(i))) {
                continue;
            }
            for (int j = 0; j < phraseWords.size(); j++) {
                int k = 0;
                while (i + k < foundWords.size() && j + k < phraseWords.size() && isPhraseWord(foundWords.get(i + k))
                        && phraseWords.get(j + k).equals(foundWords.get(i + k).getFoundWord())) {
                    k++;
                    var candidate = new PartialMatch(phrase, i, k, (double) k / phraseWords.size());
                    if (candidate.coverage() < config.getMinPartialPhraseCoverage()) {
                        continue;
                    }
                    double accuracy = fragmentAccuracy(foundWords, candidate);
                    if (!accurateEnough(accuracy)) {
                        continue;
                    }
                    if (best == null || k > best.length() || (k == best.length() && accuracy > bestAccuracy)) {
                        best = candidate;
                        bestAccuracy = accuracy;
                    }
                }
            }
        }
        return best;
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

        // Insertion-ordered: the order decides which phrase is tried first, and the map
        // keeps the de-duplication linear (it was a list scanned per phrase). Phrases
        // are equal by their text. When a temporary (quick-reply) phrase has the same
        // text as a configured one, the quick reply's phrase — and so its expressions —
        // takes the configured one's place: the user clicked the quick reply, as single
        // quick-reply words already win by being looked up first.
        Map<IDictionary.IPhrase, IDictionary.IPhrase> ret = new LinkedHashMap<>();
        for (IDictionary.IFoundWord foundWord : foundWords) {
            if (foundWord.isPhrase()) {
                continue;
            }
            for (int i = 0; i < phrasesMaps.size(); i++) {
                List<IDictionary.IPhrase> phrases = phrasesMaps.get(i).get(foundWord.getFoundWord());
                if (phrases == null) {
                    continue;
                }
                boolean temporary = i == TEMPORARY_PHRASES;
                for (IDictionary.IPhrase phrase : phrases) {
                    if (temporary) {
                        ret.put(phrase, phrase);
                    } else {
                        ret.putIfAbsent(phrase, phrase);
                    }
                }
            }
        }

        return ret.values();
    }

    private static String getLanguageOrDefault(String languageCode) {
        return isNullOrEmpty(languageCode) ? DEFAULT_USER_LANGUAGE : languageCode;
    }

    @Override
    public Config getConfig() {
        return config;
    }
}
