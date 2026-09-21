/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.commons.codec.language.RefinedSoundex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author ginccc
 */
public class PhoneticCorrection implements ICorrection {
    /**
     * A phonetic candidate together with the language of the dictionary it came
     * from. The phonetic index is built once in {@link #init(List)}, so the
     * dictionary's language has to be remembered here — otherwise the per-turn
     * language filter that the direct lookup applies could not be applied to
     * corrections.
     */
    private record PhoneticCandidate(String languageCode, IDictionary.IFoundWord foundWord) {
    }

    private final Map<String, List<PhoneticCandidate>> soundexCodes;
    private final Map<String, List<PhoneticCandidate>> metaphoneCodes;
    private final RefinedSoundex refinedSoundex;
    private final DoubleMetaphone doubleMetaphone;
    private final boolean lookupIfKnown;

    public PhoneticCorrection(boolean lookupIfKnown) {
        this.lookupIfKnown = lookupIfKnown;
        refinedSoundex = new RefinedSoundex();
        doubleMetaphone = new DoubleMetaphone();

        metaphoneCodes = new HashMap<>();
        soundexCodes = new HashMap<>();
    }

    @Override
    public void init(List<IDictionary> dictionaries) {
        // Phonetic codes are lossy by construction: night/knight/nite share one code.
        // Every word that hashes to a code has to be kept as a candidate — overwriting
        // the entry would discard all but the last word of each collision group.
        dictionaries.forEach(dictionary -> dictionary.getWords().forEach(word -> {
            var candidate = new PhoneticCandidate(dictionary.getLanguageCode(), new FoundWord(word, true, 0.3));
            addCandidate(soundexCodes, calculateSoundexCode(word.getValue()), candidate);
            addCandidate(metaphoneCodes, calculateMetaphoneCode(word.getValue()), candidate);
        }));
    }

    private static void addCandidate(Map<String, List<PhoneticCandidate>> codes, String code, PhoneticCandidate candidate) {
        if (code == null) {
            return;
        }

        List<PhoneticCandidate> candidates = codes.computeIfAbsent(code, k -> new ArrayList<>());
        if (!candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private String calculateMetaphoneCode(String word) {
        return doubleMetaphone.doubleMetaphone(word, true);
    }

    private String calculateSoundexCode(String word) {
        return refinedSoundex.soundex(word);
    }

    private List<IDictionary.IFoundWord> lookupPhonetic(String word, String userLanguage) {
        // A word usually matches through both codes — collect into a set so the same
        // candidate is not offered to the parser twice.
        Set<IDictionary.IFoundWord> foundWords = new LinkedHashSet<>();

        collectMatching(soundexCodes.get(calculateSoundexCode(word)), userLanguage, foundWords);
        collectMatching(metaphoneCodes.get(calculateMetaphoneCode(word)), userLanguage, foundWords);

        return new ArrayList<>(foundWords);
    }

    /**
     * Same language gate as the parser's direct lookup — a dictionary that is
     * skipped for a lookup must not sneak back in through a correction.
     */
    private static void collectMatching(List<PhoneticCandidate> candidates, String userLanguage, Set<IDictionary.IFoundWord> foundWords) {
        if (candidates == null) {
            return;
        }

        candidates.stream().filter(candidate -> IDictionary.appliesToLanguage(candidate.languageCode(), userLanguage))
                .map(PhoneticCandidate::foundWord).forEach(foundWords::add);
    }

    @Override
    public List<IDictionary.IFoundWord> correctWord(String word, String userLanguage, List<IDictionary> temporaryDictionaries) {
        return lookupPhonetic(word, userLanguage);
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }
}
