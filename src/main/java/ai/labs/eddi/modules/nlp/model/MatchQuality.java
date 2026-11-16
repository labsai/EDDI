/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import java.util.Collection;

/**
 * Reads how good a dictionary match is. The accuracy and the corrected flag
 * live on {@link FoundDictionaryEntry}, not on the
 * {@link IDictionary.IFoundWord} interface, so a match that is not one (a test
 * double, a third-party dictionary's own type) counts as exact and uncorrected.
 */
public final class MatchQuality {
    private MatchQuality() {
    }

    /**
     * @return the match's accuracy on the 0..1 scale, {@code 1.0} when the match
     *         does not carry one
     */
    public static double accuracy(IDictionary.IFoundWord foundWord) {
        return foundWord instanceof FoundDictionaryEntry entry ? entry.getMatchingAccuracy() : 1.0;
    }

    /**
     * @return whether the match came from a correction (a typo, a case mismatch, a
     *         phonetic or merged-terms guess) rather than the input as typed
     */
    public static boolean corrected(IDictionary.IFoundWord foundWord) {
        return foundWord instanceof FoundDictionaryEntry entry && entry.isIsCorrected();
    }

    /** The lowest accuracy among the given matches; {@code 1.0} for none. */
    public static double lowestAccuracy(Collection<? extends IDictionary.IFoundWord> foundWords) {
        return foundWords.stream().mapToDouble(MatchQuality::accuracy).min().orElse(1.0);
    }

    /** Whether any of the given matches came from a correction. */
    public static boolean anyCorrected(Collection<? extends IDictionary.IFoundWord> foundWords) {
        return foundWords.stream().anyMatch(MatchQuality::corrected);
    }
}
