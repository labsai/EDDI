/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.model.*;

import java.util.*;

/**
 * @author ginccc
 */
public class RegularDictionary implements IDictionary {
    private List<IPhrase> phrases = new LinkedList<>();
    private Map<String, IWord> words = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private List<IRegEx> regExs = new LinkedList<>();

    private boolean lookupIfKnown;
    private String languageCode;

    /**
     * Cached, flattened view of {@link #words} plus all words contained in
     * {@link #phrases}. {@code getWords()} is called once per input token by the
     * corrections (e.g. Damerau-Levenshtein), so rebuilding the whole list on every
     * call allocated the entire dictionary per token. Mutating the dictionary drops
     * the cache.
     * <p>
     * {@code volatile} because the dictionary instance is created once per workflow
     * (see {@code WorkflowStoreClientLibrary#createExecutableWorkflow}) and then
     * read by every concurrent conversation. Recomputing the list twice under a
     * race is harmless; publishing the inner list without a happens-before edge is
     * not.
     */
    private volatile List<IWord> allWordsCache;

    @Override
    public List<IWord> getWords() {
        // Read the volatile field once: a concurrent mutation could otherwise null it
        // between the check and the return.
        List<IWord> cached = allWordsCache;
        if (cached == null) {
            List<IWord> allWords = new LinkedList<>(words.values());
            phrases.stream().map(IPhrase::getWords).forEach(allWords::addAll);
            cached = Collections.unmodifiableList(allWords);
            allWordsCache = cached;
        }

        return cached;
    }

    @Override
    public String getLanguageCode() {
        return languageCode;
    }

    /**
     * Sets the ISO language code this dictionary is written for. The parser skips
     * dictionaries whose language does not match the current user language; a
     * {@code null} or blank value means "applies to every language".
     * <p>
     * Set this <em>before</em> adding words or phrases — entries are stamped with
     * the language code at creation time.
     */
    public void setLanguageCode(String languageCode) {
        this.languageCode = languageCode;
    }

    @Override
    public List<IPhrase> getPhrases() {
        return Collections.unmodifiableList(phrases);
    }

    /**
     * Index of the phrases' words: lower-cased value to the distinct words with
     * that value (one per spelling). Built lazily, dropped whenever phrases change.
     * <p>
     * {@link #lookupTerm(String)} runs for every input token, and it used to stream
     * every word of every phrase twice — and to return one match per phrase that
     * contains the word, plus a 0.9 case-insensitive copy of each exact match. All
     * of those are the same word to the phrase matcher (which compares words by
     * value), so every extra copy only multiplied the combinations the parser has
     * to enumerate.
     */
    private volatile Map<String, List<IWord>> phraseWordIndex;

    private Map<String, List<IWord>> phraseWordIndex() {
        Map<String, List<IWord>> index = phraseWordIndex;
        if (index == null) {
            Map<String, List<IWord>> built = new HashMap<>();
            for (IPhrase phrase : phrases) {
                for (IWord partOfPhrase : phrase.getWords()) {
                    List<IWord> spellings = built.computeIfAbsent(partOfPhrase.getValue().toLowerCase(Locale.ROOT), k -> new ArrayList<>(1));
                    if (spellings.stream().noneMatch(known -> known.getValue().equals(partOfPhrase.getValue()))) {
                        spellings.add(partOfPhrase);
                    }
                }
            }
            index = built;
            phraseWordIndex = index;
        }
        return index;
    }

    @Override
    public List<IFoundWord> lookupTerm(String lookup) {
        List<IFoundWord> ret = new ArrayList<>();

        List<IWord> phraseWords = phraseWordIndex().get(lookup.toLowerCase(Locale.ROOT));
        if (phraseWords != null) {
            // exact spelling first, then the case-insensitive ones
            phraseWords.stream().filter(partOfPhrase -> partOfPhrase.getValue().equals(lookup))
                    .forEach(partOfPhrase -> ret.add(new FoundWord(partOfPhrase, false, 1.0)));
            phraseWords.stream().filter(partOfPhrase -> !partOfPhrase.getValue().equals(lookup))
                    .forEach(partOfPhrase -> ret.add(new FoundWord(partOfPhrase, false, 0.9)));
        }

        IWord word;
        if ((word = words.get(lookup)) != null) {
            // The map is case-insensitive; the stored word's own spelling says whether
            // this was an exact hit. (This used to scan every key, on a parallel
            // stream, per input token.)
            boolean isCaseSensitiveMatch = word.getValue().equals(lookup);
            ret.add(new FoundWord(word, !isCaseSensitiveMatch, isCaseSensitiveMatch ? 1.0 : 0.9));
        }

        if (ret.isEmpty() || lookupIfKnown) {
            // One deadline for ALL the patterns of this lookup: a per-pattern budget
            // would let ten slow patterns cost ten budgets per token.
            long deadline = System.nanoTime() + RegEx.MATCH_BUDGET_NANOS;
            regExs.stream().takeWhile(regEx -> System.nanoTime() <= deadline).filter(regEx -> regEx.match(lookup, deadline))
                    .forEach(regEx -> ret.add(new FoundRegEx(new Word(lookup, regEx.getExpressions(), regEx.getLanguageCode()), regEx)));
        }

        return ret;
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }

    public void addWord(final String value, final Expressions expressions, int rating) {
        words.put(value, new Word(value, expressions, languageCode, rating, false));
        allWordsCache = null;
    }

    public void addRegex(final String regEx, Expressions expressions) {
        regExs.add(new RegEx(regEx, expressions));
    }

    public void addPhrase(String value, Expressions expressions) {
        phrases.add(new Phrase(value, expressions, languageCode));
        allWordsCache = null;
        phraseWordIndex = null;
    }

    public void setPhrases(List<IPhrase> phrases) {
        this.phrases = phrases;
        allWordsCache = null;
        phraseWordIndex = null;
    }

    public void setWords(Map<String, IWord> words) {
        this.words = words;
        allWordsCache = null;
    }

    public List<IRegEx> getRegExs() {
        return regExs;
    }

    public void setRegExs(List<IRegEx> regExs) {
        this.regExs = regExs;
    }

    public boolean isLookupIfKnown() {
        return lookupIfKnown;
    }

    public void setLookupIfKnown(boolean lookupIfKnown) {
        this.lookupIfKnown = lookupIfKnown;
    }
}
