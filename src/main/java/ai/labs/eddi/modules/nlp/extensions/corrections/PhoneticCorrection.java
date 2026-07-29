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
    private final Map<String, List<IDictionary.IFoundWord>> soundexCodes;
    private final Map<String, List<IDictionary.IFoundWord>> metaphoneCodes;
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
            var foundWord = new FoundWord(word, true, 0.3);
            addCandidate(soundexCodes, calculateSoundexCode(word.getValue()), foundWord);
            addCandidate(metaphoneCodes, calculateMetaphoneCode(word.getValue()), foundWord);
        }));
    }

    private static void addCandidate(Map<String, List<IDictionary.IFoundWord>> codes, String code, IDictionary.IFoundWord foundWord) {
        if (code == null) {
            return;
        }

        List<IDictionary.IFoundWord> candidates = codes.computeIfAbsent(code, k -> new ArrayList<>());
        if (!candidates.contains(foundWord)) {
            candidates.add(foundWord);
        }
    }

    private String calculateMetaphoneCode(String word) {
        return doubleMetaphone.doubleMetaphone(word, true);
    }

    private String calculateSoundexCode(String word) {
        return refinedSoundex.soundex(word);
    }

    private List<IDictionary.IFoundWord> lookupPhonetic(String word) {
        // A word usually matches through both codes — collect into a set so the same
        // candidate is not offered to the parser twice.
        Set<IDictionary.IFoundWord> foundWords = new LinkedHashSet<>();

        List<IDictionary.IFoundWord> soundexMatches = soundexCodes.get(calculateSoundexCode(word));
        if (soundexMatches != null) {
            foundWords.addAll(soundexMatches);
        }

        List<IDictionary.IFoundWord> metaphoneMatches = metaphoneCodes.get(calculateMetaphoneCode(word));
        if (metaphoneMatches != null) {
            foundWords.addAll(metaphoneMatches);
        }

        return new ArrayList<>(foundWords);
    }

    @Override
    public List<IDictionary.IFoundWord> correctWord(String word, String userLanguage, List<IDictionary> temporaryDictionaries) {
        return lookupPhonetic(word);
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }
}
