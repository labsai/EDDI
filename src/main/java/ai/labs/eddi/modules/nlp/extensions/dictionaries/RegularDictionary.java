/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.model.*;

import java.util.*;
import java.util.stream.Collectors;

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

    @Override
    public List<IFoundWord> lookupTerm(String lookup) {
        List<IFoundWord> ret = phrases.stream().flatMap(phrase -> phrase.getWords().stream())
                .filter(partOfPhrase -> partOfPhrase.getValue().equals(lookup)).map(partOfPhrase -> new FoundWord(partOfPhrase, false, 1.0))
                .collect(Collectors.toList());

        phrases.stream().flatMap(phrase -> phrase.getWords().stream()).filter(partOfPhrase -> partOfPhrase.getValue().equalsIgnoreCase(lookup))
                .map(partOfPhrase -> new FoundWord(partOfPhrase, false, 0.9)).collect(Collectors.toList()).stream()
                .filter(foundWord -> !ret.contains(foundWord)).forEach(ret::add);

        IWord word;
        if ((word = words.get(lookup)) != null) {
            boolean isCaseSensitiveMatch = words.keySet().stream().parallel().anyMatch(key -> key.equals(lookup));
            ret.add(new FoundWord(word, !isCaseSensitiveMatch, isCaseSensitiveMatch ? 1.0 : 0.9));
        }

        if (ret.isEmpty() || lookupIfKnown) {
            regExs.stream().filter(regEx -> regEx.match(lookup))
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
    }

    public void setPhrases(List<IPhrase> phrases) {
        this.phrases = phrases;
        allWordsCache = null;
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
