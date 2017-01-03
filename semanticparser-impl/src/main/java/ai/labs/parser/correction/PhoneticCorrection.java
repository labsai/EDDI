package ai.labs.parser.correction;

import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.IDictionary;
import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.commons.codec.language.RefinedSoundex;

import java.util.ArrayList;
import java.util.Arrays;
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
        for (IDictionary dictionary : dictionaries) {
            for (IDictionary.IWord word : dictionary.getWords()) {
                List<IDictionary.IFoundWord> foundWords = Arrays.asList(new IDictionary.IFoundWord[]{new FoundWord(word, true, 0.3)});
                soundexCodes.put(calculateSoundexCode(word.getValue()), foundWords);
                metaphoneCodes.put(calculateMetaphoneCode(word.getValue()), foundWords);
            }
        }
    }

    private String calculateMetaphoneCode(String word) {
        return doubleMetaphone.doubleMetaphone(word, true);
    }

    private String calculateSoundexCode(String word) {
        return refinedSoundex.soundex(word);
    }

    private IDictionary.IFoundWord[] lookupPhonetic(String word) {
        List<IDictionary.IFoundWord> foundWords = new ArrayList<>();

        String soundexCode = calculateSoundexCode(word);
        foundWords.addAll(soundexCodes.get(soundexCode));

        String metaphoneCode = calculateMetaphoneCode(word);
        foundWords.addAll(metaphoneCodes.get(metaphoneCode));

        return foundWords.toArray(new IDictionary.IFoundWord[foundWords.size()]);
    }

    @Override
    public IDictionary.IFoundWord[] correctWord(String word) {
        return lookupPhonetic(word);
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }
}
