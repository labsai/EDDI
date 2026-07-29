/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.modules.nlp.expressions.Expressions;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
public interface IDictionary {
    List<IFoundWord> NO_WORDS_FOUND = Collections.emptyList();

    /**
     * Whether a dictionary written for {@code dictionaryLanguageCode} applies to a
     * conversation turn held in {@code userLanguage}. A {@code null} or blank
     * dictionary language means "applies to every language".
     * <p>
     * This filter has to be applied on <em>every</em> path that consults a
     * dictionary — the direct lookup as well as each correction. A correction that
     * scans a language-mismatched dictionary re-introduces exactly the match the
     * filter exists to prevent: as soon as the token is otherwise unknown, the
     * foreign-language word comes back at distance 0 / accuracy 1.0.
     * <p>
     * Deliberately a static helper rather than a {@code default} method: call sites
     * are frequently handed Mockito mocks of this interface, and a mocked default
     * method would answer {@code false} instead of running this logic.
     *
     * @param dictionaryLanguageCode
     *            the dictionary's configured language, may be {@code null}
     * @param userLanguage
     *            the language of the current conversation turn
     * @return {@code true} if the dictionary may be consulted for this turn
     */
    static boolean appliesToLanguage(String dictionaryLanguageCode, String userLanguage) {
        return isNullOrEmpty(dictionaryLanguageCode) || dictionaryLanguageCode.equals(userLanguage);
    }

    default List<IWord> getWords() {
        return Collections.emptyList();
    }

    default List<IPhrase> getPhrases() {
        return Collections.emptyList();
    }

    default String getLanguageCode() {
        return null;
    }

    List<IFoundWord> lookupTerm(String value);

    boolean lookupIfKnown();

    default void setConfig(Map<String, Object> config) throws IllegalExtensionConfigurationException {
        // to be overridden if needed
    }

    interface IDictionaryEntry extends Comparable<IDictionaryEntry> {
        String getValue();

        Expressions getExpressions();

        String getLanguageCode();

        boolean isWord();

        boolean isPhrase();

        int getFrequency();
    }

    interface IWord extends IDictionaryEntry {
        boolean isPartOfPhrase();

        int getFrequency();
    }

    interface IRegEx extends IWord {
        boolean match(String lookup);
    }

    interface IPhrase extends IWord {
        List<IWord> getWords();
    }

    interface IFoundDictionaryEntry extends IDictionaryEntry {
    }

    interface IFoundWord extends IFoundDictionaryEntry {
        IWord getFoundWord();
    }

    interface IFoundPhrase extends IFoundWord {
        IPhrase getFoundPhrase();
    }

    interface IFoundRegEx extends IFoundWord {
        IRegEx getMatchingRegEx();
    }
}
