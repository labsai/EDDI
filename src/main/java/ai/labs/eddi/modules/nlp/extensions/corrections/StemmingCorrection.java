package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import org.tartarus.snowball.SnowballStemmer;

import java.util.*;


/**
 * @author ginccc
 */
public class StemmingCorrection implements ICorrection {
    private boolean lookupIfKnown;
    private String language;
    private SnowballStemmer stemmer;
    private Map<String, List<IDictionary.IFoundWord>> stemmedWordMap = new HashMap<>();

    public StemmingCorrection(String language, boolean lookupIfKnown) {
        this.language = language;
        this.lookupIfKnown = lookupIfKnown;
    }

    @Override
    public void init(List<IDictionary> dictionaries) {
        stemmer = createNewStemmer();
        dictionaries.stream().flatMap(dictionary -> dictionary.getWords().stream()).
                forEach(word -> {
                    stemmer.setCurrent(word.getValue().toLowerCase());
                    stemmer.stem();
                    String stemmedWord = stemmer.getCurrent();
                    if (!this.stemmedWordMap.containsKey(stemmedWord)) {
                        this.stemmedWordMap.put(stemmedWord, new ArrayList<>());
                    }
                    this.stemmedWordMap.get(stemmedWord).add(new FoundWord(word, true, 0.5));
                });
    }

    private SnowballStemmer createNewStemmer() {
        try {
            Class<?> stemClass = Class.forName("org.tartarus.snowball.ext." + language + "Stemmer");
            return (SnowballStemmer) stemClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public List<IDictionary.IFoundWord> correctWord(String word, String userLanguage, List<IDictionary> temporaryDictionaries) {
        stemmer = createNewStemmer();
        stemmer.setCurrent(word.toLowerCase());
        stemmer.stem();
        String stemmedWord = stemmer.getCurrent();

        List<IDictionary.IFoundWord> foundWords = stemmedWordMap.get(stemmedWord);

        if (foundWords != null && !foundWords.isEmpty()) {
            Collections.sort(foundWords);
            return foundWords;
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public boolean lookupIfKnown() {
        return lookupIfKnown;
    }
}
