package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.commons.codec.language.RefinedSoundex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;


/**
 * @author ginccc
 */
public class PhoneticCorrection implements ICorrection {
    private HashMap<String, List<IDictionary.IFoundWord>> soundexCodes;
    private HashMap<String, List<IDictionary.IFoundWord>> metaphoneCodes;
    private RefinedSoundex refinedSoundex;
    private DoubleMetaphone doubleMetaphone;
    private boolean lookupIfKnown;

    public PhoneticCorrection(boolean lookupIfKnown) {
        this.lookupIfKnown = lookupIfKnown;
        refinedSoundex = new RefinedSoundex();
        doubleMetaphone = new DoubleMetaphone();

        metaphoneCodes = new HashMap<>();
        soundexCodes = new HashMap<>();
    }

    @Override
    public void init(List<IDictionary> dictionaries) {
        dictionaries.forEach(dictionary -> dictionary.getWords().
                forEach(word -> {
                    List<IDictionary.IFoundWord> foundWords =
                            Collections.singletonList(new FoundWord(word, true, 0.3));
                    soundexCodes.put(calculateSoundexCode(word.getValue()), foundWords);
                    metaphoneCodes.put(calculateMetaphoneCode(word.getValue()), foundWords);
                }));
    }

    private String calculateMetaphoneCode(String word) {
        return doubleMetaphone.doubleMetaphone(word, true);
    }

    private String calculateSoundexCode(String word) {
        return refinedSoundex.soundex(word);
    }

    private List<IDictionary.IFoundWord> lookupPhonetic(String word) {
        List<IDictionary.IFoundWord> foundWords = new ArrayList<>();

        String soundexCode = calculateSoundexCode(word);
        foundWords.addAll(soundexCodes.get(soundexCode));

        String metaphoneCode = calculateMetaphoneCode(word);
        foundWords.addAll(metaphoneCodes.get(metaphoneCode));

        return foundWords;
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
